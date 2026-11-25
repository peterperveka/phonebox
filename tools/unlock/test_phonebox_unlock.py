"""Tests for the PhoneBox PAM helper. Run: .venv-unlock/bin/python -m unittest discover -s tools/unlock -v"""
import base64
import json
import os
import pty
import re
import socket
import tempfile
import threading
import time
import unittest
from unittest import mock

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import phonebox_unlock as pu


def new_key(curve=None):
    priv = ec.generate_private_key(curve or ec.SECP256R1())
    der = priv.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return priv, base64.b64encode(der).decode()


def sign(priv, text):
    return base64.b64encode(priv.sign(text.encode(), ec.ECDSA(hashes.SHA256()))).decode()


class FakeAgent:
    """One-shot HTTP server on 127.0.0.1 that answers /api/unlock/request via a callback."""

    def __init__(self, handler):
        self.handler = handler  # challenge -> (status, body_bytes) or None to never answer
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(1)
        self.port = self.sock.getsockname()[1]
        self.challenge = None
        self.peer_closed = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self):
        try:
            conn, _ = self.sock.accept()
        except OSError:
            return
        with conn:
            data = b""
            while b"\r\n\r\n" not in data:
                chunk = conn.recv(4096)
                if not chunk:
                    return
                data += chunk
            head, body = data.split(b"\r\n\r\n", 1)
            length = int(re.search(rb"(?i)content-length:\s*(\d+)", head).group(1))
            while len(body) < length:
                body += conn.recv(4096)
            self.challenge = json.loads(body)["challenge"]
            answer = self.handler(self.challenge)
            if answer is None:
                conn.settimeout(5)
                try:
                    while conn.recv(4096):
                        pass
                except OSError:
                    pass
                self.peer_closed.set()
                return
            status, payload = answer
            reason = {200: "OK", 204: "No Content", 400: "Bad Request"}[status]
            conn.sendall(f"HTTP/1.1 {status} {reason}\r\nContent-Length: {len(payload)}\r\n"
                         f"Content-Type: application/json\r\nConnection: close\r\n\r\n".encode() + payload)

    def close(self):
        self.sock.close()


def closed_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.priv, self.pub = new_key()
        path = os.path.join(self.tmp.name, "alice.pub")
        with open(path, "w") as f:
            f.write(self.pub + "\n")
        os.chmod(path, 0o644)
        self.keyfile = path
        for p in (mock.patch.object(pu, "KEY_DIR", self.tmp.name),
                  mock.patch.object(pu, "KEY_OWNER_UID", os.geteuid()),
                  mock.patch.object(pu, "is_locked", lambda user: False)):
            p.start()
            self.addCleanup(p.stop)
        self.addCleanup(self.tmp.cleanup)

    def pty_env(self):
        master, slave = pty.openpty()
        self.addCleanup(os.close, master)
        self.addCleanup(os.close, slave)
        return master, {"PAM_SERVICE": "sudo", "PAM_USER": "alice", "PAM_TTY": os.ttyname(slave)}


class PureFunctions(unittest.TestCase):
    def test_challenge_has_seven_fields_and_30s_expiry(self):
        now = 1_700_000_000_000
        parts = pu.make_challenge("sudo", "alice", "box", "sudo dnf up", now).split("|")
        self.assertEqual(len(parts), 7)
        self.assertEqual(parts[0], "phonebox-unlock-v1")
        self.assertEqual(parts[1], "sudo")
        self.assertEqual(int(parts[6]), now + 30000)
        self.assertEqual(len(parts[5]), 64)
        self.assertRegex(parts[5], r"^[0-9a-f]{64}$")

    def test_challenge_cleans_user_and_host(self):
        parts = pu.make_challenge("sudo", "al|ice\n", "bo|x\x01", "c", 0).split("|")
        self.assertEqual(len(parts), 7)
        self.assertEqual(parts[2:4], ["alice", "box"])

    def test_challenge_nonce_is_fresh(self):
        a = pu.make_challenge("sudo", "a", "b", "c", 0).split("|")[5]
        b = pu.make_challenge("sudo", "a", "b", "c", 0).split("|")[5]
        self.assertNotEqual(a, b)

    def test_clean_command(self):
        self.assertEqual(pu.clean_command(b"sudo\x00dnf\x00a|b"), "sudo dnf ab")
        self.assertEqual(pu.clean_command(b"sudo\x00ls\x00"), "sudo ls")
        self.assertEqual(len(pu.clean_command(b"x" * 300)), 120)
        self.assertEqual(pu.clean_command(b"a\nb\x1b[31mc\x7f"), "ab[31mc")
        self.assertEqual(pu.clean_command(b""), "-")

    def test_verify_good_signature(self):
        priv, pub = new_key()
        ch = pu.make_challenge("sudo", "alice", "box", "ls", 0)
        self.assertTrue(pu.verify(pub, ch, sign(priv, ch)))

    def test_verify_other_challenge_is_false(self):
        priv, pub = new_key()
        ch = pu.make_challenge("sudo", "alice", "box", "ls", 0)
        other = pu.make_challenge("sudo", "alice", "box", "ls", 0)
        self.assertFalse(pu.verify(pub, other, sign(priv, ch)))

    def test_verify_garbage_is_false(self):
        priv, pub = new_key()
        ch = "x"
        good = sign(priv, ch)
        self.assertFalse(pu.verify("!!not base64", ch, good))
        self.assertFalse(pu.verify(pub, ch, "!!not base64"))
        self.assertFalse(pu.verify(base64.b64encode(b"junk").decode(), ch, good))
        self.assertFalse(pu.verify(pub, ch, base64.b64encode(b"junk").decode()))
        self.assertFalse(pu.verify(None, ch, good))
        self.assertFalse(pu.verify(pub, ch, None))
        self.assertFalse(pu.verify(pub, ch, ""))

    def test_verify_rejects_other_curves(self):
        priv, pub = new_key(ec.SECP384R1())
        ch = "x"
        self.assertFalse(pu.verify(pub, ch, sign(priv, ch)))

    def test_skip_reasons(self):
        ok = {"PAM_SERVICE": "sudo", "PAM_TTY": "/dev/pts/3"}
        self.assertIsNone(pu.skip_reason(ok, True, False))
        self.assertIsNone(pu.skip_reason({**ok, "PAM_TTY": "pts/3"}, True, False))
        self.assertIsNone(pu.skip_reason({**ok, "PAM_TTY": "tty2"}, True, False))
        self.assertEqual(pu.skip_reason({**ok, "PAM_SERVICE": "login"}, True, False), "service")
        self.assertEqual(pu.skip_reason({}, True, False), "service")
        self.assertEqual(pu.skip_reason(ok, False, False), "no key")
        self.assertEqual(pu.skip_reason({**ok, "PAM_RHOST": "10.0.0.2"}, True, False), "remote")
        self.assertEqual(pu.skip_reason({**ok, "PAM_TTY": ""}, True, False), "no tty")
        self.assertEqual(pu.skip_reason({"PAM_SERVICE": "sudo"}, True, False), "no tty")
        self.assertEqual(pu.skip_reason({**ok, "PAM_TTY": ":0"}, True, False), "no tty")
        self.assertEqual(pu.skip_reason(ok, True, True), "locked")

    def test_mode_by_service(self):
        self.assertEqual(pu.mode({"PAM_SERVICE": "sudo"}), "sudo")
        self.assertEqual(pu.mode({"PAM_SERVICE": "sudo-i"}), "sudo")
        self.assertEqual(pu.mode({"PAM_SERVICE": "polkit-1"}), "polkit")
        self.assertIsNone(pu.mode({"PAM_SERVICE": "login"}))
        self.assertIsNone(pu.mode({"PAM_SERVICE": "gdm-password"}))
        self.assertIsNone(pu.mode({}))

    def test_polkit_mode_needs_no_tty(self):
        self.assertIsNone(pu.skip_reason({"PAM_SERVICE": "polkit-1"}, True, False))

    def test_polkit_challenge_has_dash_command(self):
        parts = pu.make_challenge("polkit", "alice", "box", "rm -rf /", 0).split("|")
        self.assertEqual(parts[1], "polkit")
        self.assertEqual(parts[4], "-")

    def test_check_code_matches_csharp_format(self):
        _, pub = new_key()
        self.assertRegex(pu.check_code(pub), r"^[0-9A-F]{4}( [0-9A-F]{4}){4}$")
        self.assertIsNone(pu.check_code("!!"))

    def test_valid_user(self):
        self.assertTrue(pu.valid_user("alice"))
        for bad in ("", None, "../etc/x", "a/b", ".hidden", "..", "a\x00b", "a" * 65):
            self.assertFalse(pu.valid_user(bad), bad)


class KeyFile(Base):
    def test_loads_key(self):
        self.assertEqual(pu.load_key("alice"), self.pub)

    def test_missing_key(self):
        self.assertIsNone(pu.load_key("bob"))

    def test_bad_user_never_touches_disk(self):
        with mock.patch("os.open", side_effect=AssertionError("opened")):
            self.assertIsNone(pu.load_key("../alice"))
            self.assertIsNone(pu.load_key(".alice"))

    def test_writable_key_is_rejected(self):
        os.chmod(self.keyfile, 0o666)
        self.assertIsNone(pu.load_key("alice"))

    def test_foreign_owner_is_rejected(self):
        with mock.patch.object(pu, "KEY_OWNER_UID", os.geteuid() + 1):
            self.assertIsNone(pu.load_key("alice"))

    def test_symlink_is_rejected(self):
        os.symlink(self.keyfile, os.path.join(self.tmp.name, "carol.pub"))
        self.assertIsNone(pu.load_key("carol"))

    def test_garbage_key_is_rejected(self):
        with open(self.keyfile, "w") as f:
            f.write("not a key\n")
        self.assertIsNone(pu.load_key("alice"))


class SudoFlow(Base):
    def run_main(self, env, port):
        with mock.patch.object(pu, "AGENT_PORT", port):
            t0 = time.monotonic()
            rc = pu.main(["phonebox-unlock"], env)
            return rc, time.monotonic() - t0

    def test_main_returns_1_when_agent_down(self):
        _, env = self.pty_env()
        rc, took = self.run_main(env, closed_port())
        self.assertEqual(rc, 1)
        self.assertLess(took, 1)

    def test_good_signature_accepts(self):
        master, env = self.pty_env()
        agent = FakeAgent(lambda ch: (200, json.dumps({"signature": sign(self.priv, ch)}).encode()))
        self.addCleanup(agent.close)
        rc, _ = self.run_main(env, agent.port)
        self.assertEqual(rc, 0)
        parts = agent.challenge.split("|")
        self.assertEqual(parts[1:3], ["sudo", "alice"])
        self.assertIn(pu.PROMPT.encode(), os.read(master, 4096))

    def test_signature_by_other_key_rejects(self):
        _, env = self.pty_env()
        other, _ = new_key()
        agent = FakeAgent(lambda ch: (200, json.dumps({"signature": sign(other, ch)}).encode()))
        self.addCleanup(agent.close)
        self.assertEqual(self.run_main(env, agent.port)[0], 1)

    def test_signature_over_other_challenge_rejects(self):
        _, env = self.pty_env()
        agent = FakeAgent(lambda ch: (200, json.dumps({"signature": sign(self.priv, ch + "x")}).encode()))
        self.addCleanup(agent.close)
        self.assertEqual(self.run_main(env, agent.port)[0], 1)

    def test_204_rejects(self):
        _, env = self.pty_env()
        agent = FakeAgent(lambda ch: (204, b""))
        self.addCleanup(agent.close)
        self.assertEqual(self.run_main(env, agent.port)[0], 1)

    def test_malformed_200_rejects(self):
        for body in (b"not json", b"[]", b'{"signature": 5}', b"{}"):
            _, env = self.pty_env()
            agent = FakeAgent(lambda ch, body=body: (200, body))
            self.addCleanup(agent.close)
            self.assertEqual(self.run_main(env, agent.port)[0], 1, body)

    def test_expired_answer_rejects(self):
        _, env = self.pty_env()
        agent = FakeAgent(lambda ch: (200, json.dumps({"signature": sign(self.priv, ch)}).encode()))
        self.addCleanup(agent.close)
        real = pu.now_ms()
        times = iter([real, real + 31_000])
        with mock.patch.object(pu, "now_ms", lambda: next(times)):
            self.assertEqual(self.run_main(env, agent.port)[0], 1)

    def test_enter_cancels_and_closes_connection(self):
        master, env = self.pty_env()
        agent = FakeAgent(lambda ch: None)  # never answers
        self.addCleanup(agent.close)
        threading.Timer(0.3, lambda: os.write(master, b"\n")).start()
        rc, took = self.run_main(env, agent.port)
        self.assertEqual(rc, 1)
        self.assertLess(took, 2)
        self.assertTrue(agent.peer_closed.wait(2), "helper did not close the connection")

    def test_timeout_rejects(self):
        _, env = self.pty_env()
        agent = FakeAgent(lambda ch: None)
        self.addCleanup(agent.close)
        with mock.patch.object(pu, "SUDO_WAIT", 0.2):
            rc, took = self.run_main(env, agent.port)
        self.assertEqual(rc, 1)
        self.assertLess(took, 1.5)

    def test_skips_without_contacting_agent(self):
        cases = [
            {"PAM_SERVICE": "login", "PAM_USER": "alice", "PAM_TTY": "/dev/pts/1"},
            {"PAM_SERVICE": "sudo", "PAM_USER": "bob", "PAM_TTY": "/dev/pts/1"},
            {"PAM_SERVICE": "sudo", "PAM_USER": "alice", "PAM_TTY": "/dev/pts/1", "PAM_RHOST": "h"},
            {"PAM_SERVICE": "sudo", "PAM_USER": "alice"},
            {"PAM_SERVICE": "sudo", "PAM_USER": "../alice", "PAM_TTY": "/dev/pts/1"},
            {"PAM_SERVICE": "sudo", "PAM_TTY": "/dev/pts/1"},
        ]
        with mock.patch("http.client.HTTPConnection", side_effect=AssertionError("contacted agent")):
            for env in cases:
                self.assertEqual(pu.main(["x"], env), 1, env)

    def test_locked_skips(self):
        _, env = self.pty_env()
        with mock.patch.object(pu, "is_locked", lambda user: True), \
                mock.patch("http.client.HTTPConnection", side_effect=AssertionError("contacted agent")):
            self.assertEqual(pu.main(["x"], env), 1)

    def test_tty_outside_dev_is_refused(self):
        env = {"PAM_SERVICE": "sudo", "PAM_USER": "alice", "PAM_TTY": "/dev/../tmp/x"}
        with mock.patch("http.client.HTTPConnection", side_effect=AssertionError("contacted agent")):
            self.assertEqual(pu.main(["x"], env), 1)

    def test_unexpected_error_returns_1_silently(self):
        _, env = self.pty_env()
        with mock.patch.object(pu, "make_challenge", side_effect=RuntimeError("boom")), \
                mock.patch("sys.stderr") as err, mock.patch("sys.stdout") as out:
            self.assertEqual(pu.main(["x"], env), 1)
        err.write.assert_not_called()
        out.write.assert_not_called()


class PolkitFlow(Base):
    env = {"PAM_SERVICE": "polkit-1", "PAM_USER": "alice"}

    def test_polkit_mode_waits_without_tty(self):
        agent = FakeAgent(lambda ch: None)
        self.addCleanup(agent.close)
        with mock.patch.object(pu, "AGENT_PORT", agent.port), mock.patch.object(pu, "POLKIT_WAIT", 0.2), \
                mock.patch.object(pu, "open_tty", side_effect=AssertionError("tty opened")), \
                mock.patch("sys.stdout") as out:
            t0 = time.monotonic()
            rc = pu.main(["phonebox-unlock"], dict(self.env))
            took = time.monotonic() - t0
        self.assertEqual(rc, 1)
        self.assertLess(took, 1)
        out.write.assert_not_called()
        self.assertEqual(agent.challenge.split("|")[1], "polkit")
        self.assertEqual(agent.challenge.split("|")[4], "-")

    def test_polkit_good_signature_accepts(self):
        agent = FakeAgent(lambda ch: (200, json.dumps({"signature": sign(self.priv, ch)}).encode()))
        self.addCleanup(agent.close)
        with mock.patch.object(pu, "AGENT_PORT", agent.port), \
                mock.patch.object(pu, "open_tty", side_effect=AssertionError("tty opened")):
            self.assertEqual(pu.main(["x"], dict(self.env)), 0)

    def test_polkit_204_rejects(self):
        agent = FakeAgent(lambda ch: (204, b""))
        self.addCleanup(agent.close)
        with mock.patch.object(pu, "AGENT_PORT", agent.port):
            self.assertEqual(pu.main(["x"], dict(self.env)), 1)


class Enroll(Base):
    def setUp(self):
        super().setUp()
        self.dir = os.path.join(self.tmp.name, "etc-phonebox")
        p = mock.patch.object(pu, "KEY_DIR", self.dir)
        p.start()
        self.addCleanup(p.stop)

    def serve_key(self, status=200, body=None):
        ready = threading.Event()
        srv = socket.socket()
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        self.addCleanup(srv.close)
        payload = json.dumps(body if body is not None else {"publicKey": self.pub, "code": "x"}).encode()

        def run():
            conn, _ = srv.accept()
            with conn:
                data = b""
                while b"\r\n\r\n" not in data:
                    data += conn.recv(4096)
                reason = "OK" if status == 200 else "Not Found"
                conn.sendall(f"HTTP/1.1 {status} {reason}\r\nContent-Length: {len(payload)}\r\n"
                             f"Connection: close\r\n\r\n".encode() + payload)
        threading.Thread(target=run, daemon=True).start()
        return srv.getsockname()[1]

    def enroll(self, port, answer, euid=0, sudo_user="alice"):
        env = {"SUDO_USER": sudo_user} if sudo_user else {}
        with mock.patch.object(pu, "AGENT_PORT", port), mock.patch("os.geteuid", return_value=euid), \
                mock.patch("builtins.input", return_value=answer), mock.patch("builtins.print") as pr:
            rc = pu.main(["phonebox-unlock", "enroll"], env)
        return rc, " ".join(str(a) for c in pr.call_args_list for a in c.args)

    def test_enroll_writes_key_on_yes(self):
        rc, out = self.enroll(self.serve_key(), "y")
        self.assertEqual(rc, 0)
        self.assertIn(pu.check_code(self.pub), out)
        path = os.path.join(self.dir, "alice.pub")
        with open(path) as f:
            self.assertEqual(f.read(), self.pub + "\n")
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o644)
        self.assertEqual(os.stat(self.dir).st_mode & 0o777, 0o755)

    def test_enroll_no_writes_nothing(self):
        rc, _ = self.enroll(self.serve_key(), "")
        self.assertNotEqual(rc, 0)
        self.assertFalse(os.path.exists(os.path.join(self.dir, "alice.pub")))

    def test_enroll_needs_root(self):
        rc, _ = self.enroll(closed_port(), "y", euid=1000)
        self.assertNotEqual(rc, 0)
        self.assertFalse(os.path.exists(self.dir))

    def test_enroll_needs_valid_sudo_user(self):
        for user in (None, "../root", ".x"):
            rc, _ = self.enroll(closed_port(), "y", sudo_user=user)
            self.assertNotEqual(rc, 0)

    def test_enroll_not_enrolled(self):
        rc, _ = self.enroll(self.serve_key(status=404, body={}), "y")
        self.assertNotEqual(rc, 0)

    def test_enroll_rejects_bad_key(self):
        _, p384 = new_key(ec.SECP384R1())
        rc, _ = self.enroll(self.serve_key(body={"publicKey": p384}), "y")
        self.assertNotEqual(rc, 0)
        self.assertFalse(os.path.exists(os.path.join(self.dir, "alice.pub")))


if __name__ == "__main__":
    unittest.main()
