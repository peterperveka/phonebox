#!/usr/bin/python3 -I
"""PhoneBox phone fingerprint unlock: PAM helper for sudo and polkit.

pam_exec runs this as root. It builds a one-time challenge, asks the local PhoneBox agent
(127.0.0.1 only) to have the phone sign it, and checks the signature itself against the
root-owned key in KEY_DIR. Exit 0 = accept; anything else = PAM goes on to the password.
Every error path returns 1 and prints nothing.

    phonebox-unlock           PAM flow (PAM_SERVICE sudo / sudo-i / polkit-1)
    sudo phonebox-unlock enroll   store the agent's paired phone key for $SUDO_USER
"""
import base64
import hashlib
import http.client
import json
import os
import re
import secrets
import select
import signal
import socket
import stat
import subprocess
import sys
import termios
import time
import unicodedata

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except Exception:  # no cryptography: no key can be loaded, so every request falls through to the password
    hashes = serialization = ec = None

KEY_DIR = "/etc/phonebox"
KEY_OWNER_UID = 0
AGENT_HOST = "127.0.0.1"
AGENT_PORT = 8765
SUDO_WAIT = 20
POLKIT_WAIT = 10
EXPIRY_MS = 30_000
LOGINCTL = "/usr/bin/loginctl"
PREFIX = "phonebox-unlock-v1"
PROMPT = "Touch the sensor on the phone, or press Enter to type the password."
MAX_COMMAND = 120
MAX_FIELD = 64
MAX_BODY = 8192

_USER_RE = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.@-]{0,62}\$?")
_STRIP_CATEGORIES = {"Cc", "Cf", "Cs", "Co", "Cn", "Zl", "Zp"}


def now_ms() -> int:
    return int(time.time() * 1000)


def _clean(text: str, limit: int) -> str:
    kept = "".join(c for c in text if c != "|" and unicodedata.category(c) not in _STRIP_CATEGORIES).strip()
    return kept[:limit] or "-"


def clean_command(raw: bytes) -> str:
    """/proc/<pid>/cmdline bytes -> one line: NUL -> space, no '|' or control chars, at most 120 chars."""
    return _clean(raw.replace(b"\x00", b" ").decode("utf-8", errors="replace"), MAX_COMMAND)


def make_challenge(purpose: str, user: str, host: str, command: str, now_ms: int) -> str:
    if purpose not in ("sudo", "polkit"):
        raise ValueError("purpose")
    cmd = "-" if purpose == "polkit" else _clean(command, MAX_COMMAND)
    return "|".join((PREFIX, purpose, _clean(user, MAX_FIELD), _clean(host, MAX_FIELD), cmd,
                     secrets.token_hex(32), str(int(now_ms) + EXPIRY_MS)))


def mode(env: dict):
    service = env.get("PAM_SERVICE")
    if service in ("sudo", "sudo-i"):
        return "sudo"
    if service == "polkit-1":
        return "polkit"
    return None


def valid_user(user) -> bool:
    """A plain account name that is safe as a file name in KEY_DIR (no '/', no leading '.')."""
    return isinstance(user, str) and _USER_RE.fullmatch(user) is not None


def _parse_key(public_key_b64: str):
    """(der, key) for a base64 SPKI EC P-256 public key; raises otherwise."""
    der = base64.b64decode(public_key_b64, validate=True)
    key = serialization.load_der_public_key(der)
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("not an EC P-256 key")
    return der, key


def verify(public_key_b64: str, challenge: str, signature_b64: str) -> bool:
    """ECDSA P-256 / SHA-256 DER signature (base64) over the UTF-8 challenge. Never raises."""
    try:
        _, key = _parse_key(public_key_b64)
        key.verify(base64.b64decode(signature_b64, validate=True), challenge.encode("utf-8"),
                   ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


def check_code(public_key_b64: str):
    """First 20 hex chars of SHA-256(SPKI DER), uppercase, in groups of 4; None for a bad key."""
    try:
        der, _ = _parse_key(public_key_b64)
    except Exception:
        return None
    hexa = hashlib.sha256(der).hexdigest().upper()[:20]
    return " ".join(hexa[i:i + 4] for i in range(0, 20, 4))


def skip_reason(env: dict, key_exists: bool, locked: bool):
    m = mode(env)
    if m is None:
        return "service"
    if not key_exists:
        return "no key"
    if env.get("PAM_RHOST"):
        return "remote"
    if m == "sudo" and not (env.get("PAM_TTY") or "").startswith(("tty", "pts", "/dev/")):
        return "no tty"
    if locked:
        return "locked"
    return None


def load_key(user: str):
    """The user's enrolled public key from KEY_DIR, or None unless it is root-owned, not writable by others and valid."""
    if not valid_user(user):
        return None
    try:
        dst = os.stat(KEY_DIR)
        if not stat.S_ISDIR(dst.st_mode) or dst.st_uid != KEY_OWNER_UID or dst.st_mode & 0o022:
            return None
        fd = os.open(os.path.join(KEY_DIR, user + ".pub"),
                     os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC)
    except OSError:
        return None
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_uid != KEY_OWNER_UID or st.st_mode & 0o022:
            return None
        data = os.read(fd, 4096)
    except OSError:
        return None
    finally:
        os.close(fd)
    try:
        text = data.decode("ascii").strip()
        _parse_key(text)
        return text
    except Exception:
        return None


def _loginctl(*args) -> str:
    out = subprocess.run([LOGINCTL, *args], capture_output=True, timeout=2, check=True,
                         env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"}, stdin=subprocess.DEVNULL)
    return out.stdout.decode("utf-8", errors="replace").strip()


def is_locked(user: str) -> bool:
    """True while the user's graphical session is locked. Any error counts as locked (password only)."""
    try:
        session = _loginctl("show-user", user, "-p", "Display", "--value")
        if not session:
            return False
        return _loginctl("show-session", session, "-p", "LockedHint", "--value") == "yes"
    except Exception:
        return True


def open_tty(pam_tty: str) -> int:
    path = os.path.normpath(pam_tty if pam_tty.startswith("/dev/") else "/dev/" + pam_tty)
    if not path.startswith("/dev/"):
        raise ValueError("tty outside /dev")
    fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_CLOEXEC)
    try:
        if not stat.S_ISCHR(os.fstat(fd).st_mode) or not os.isatty(fd):
            raise ValueError("not a tty")
    except BaseException:
        os.close(fd)
        raise
    return fd


class _Deadline(Exception):
    pass


def _on_deadline(signum, frame):
    raise _Deadline()


def _ask_phone(challenge: str, public_key: str, wait: float, tty) -> int:
    """POST the challenge to the agent, wait for the answer (or Enter on the tty), verify it.

    The whole exchange is bounded by `wait`: select gets the remaining time, and the response
    reads run under a real-time interval timer plus a socket timeout of the remaining time.
    """
    deadline = time.monotonic() + wait
    expires = int(challenge.rsplit("|", 1)[1])
    conn = http.client.HTTPConnection(AGENT_HOST, AGENT_PORT, timeout=1)
    old_handler = signal.signal(signal.SIGALRM, _on_deadline)
    try:
        conn.request("POST", "/api/unlock/request", body=json.dumps({"challenge": challenge}).encode("utf-8"),
                     headers={"Content-Type": "application/json"})
        watch = [conn.sock] if tty is None else [conn.sock, tty]
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return 1
        ready, _, _ = select.select(watch, [], [], remaining)
        if not ready:
            return 1
        if tty is not None and tty in ready:
            try:
                os.read(tty, 1024)  # consume the Enter so the password prompt does not see an empty line
            except OSError:
                pass
            return 1
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return 1
        conn.sock.settimeout(remaining)
        signal.setitimer(signal.ITIMER_REAL, remaining)
        resp = conn.getresponse()
        if resp.status != 200:
            return 1
        raw = resp.read(MAX_BODY + 1)
        signal.setitimer(signal.ITIMER_REAL, 0)
        if len(raw) > MAX_BODY:
            return 1
        data = json.loads(raw)
        signature = data.get("signature") if isinstance(data, dict) else None
        if not isinstance(signature, str) or now_ms() > expires:
            return 1
        return 0 if verify(public_key, challenge, signature) else 1
    except _Deadline:
        return 1
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, old_handler)
        conn.close()


def _pam(env: dict) -> int:
    m = mode(env)
    user = env.get("PAM_USER")
    if m is None or not valid_user(user):
        return 1
    key = load_key(user)
    if skip_reason(env, key is not None, False) is not None:
        return 1
    if skip_reason(env, True, is_locked(user)) is not None:
        return 1
    host = socket.gethostname()
    if m == "polkit":
        return _ask_phone(make_challenge("polkit", user, host, "-", now_ms()), key, POLKIT_WAIT, None)
    with open(f"/proc/{os.getppid()}/cmdline", "rb") as f:
        command = clean_command(f.read(4096))
    challenge = make_challenge("sudo", user, host, command, now_ms())
    tty = open_tty(env["PAM_TTY"])
    try:
        saved = termios.tcgetattr(tty)
        quiet = termios.tcgetattr(tty)
        quiet[3] &= ~termios.ECHO  # keep canonical mode; a password typed before Enter must not show
        os.write(tty, (PROMPT + "\n").encode("utf-8"))
        termios.tcsetattr(tty, termios.TCSANOW, quiet)
        try:
            return _ask_phone(challenge, key, SUDO_WAIT, tty)
        finally:
            termios.tcsetattr(tty, termios.TCSAFLUSH, saved)  # restore, dropping any type-ahead
    finally:
        os.close(tty)


def _fail(message: str) -> int:
    print(message, file=sys.stderr)
    return 1


def _enroll(env: dict) -> int:
    """Interactive (run with sudo): fetch the paired phone key from the agent and store it for $SUDO_USER."""
    if os.geteuid() != 0:
        print("phonebox-unlock enroll must run as root: sudo phonebox-unlock enroll", file=sys.stderr)
        return 2
    user = env.get("SUDO_USER")
    if not valid_user(user) or user == "root":
        print("Run this with sudo from your own account.", file=sys.stderr)
        return 2
    conn = http.client.HTTPConnection(AGENT_HOST, AGENT_PORT, timeout=5)
    try:
        conn.request("GET", "/api/unlock/key")
        resp = conn.getresponse()
        raw = resp.read(MAX_BODY + 1)
        status = resp.status
    except (OSError, http.client.HTTPException):
        return _fail("Could not reach the PhoneBox agent on this computer. Is it running?")
    finally:
        conn.close()
    if status == 404:
        return _fail("No phone key yet: turn on fingerprint unlock in PhoneBox on the phone first.")
    try:
        if status != 200 or len(raw) > MAX_BODY:
            raise ValueError("status")
        data = json.loads(raw)
        public_key = data.get("publicKey") if isinstance(data, dict) else None
        code = check_code(public_key) if isinstance(public_key, str) else None
        if code is None:
            raise ValueError("key")
    except ValueError:
        return _fail("The PhoneBox agent sent an invalid reply.")
    print(f"Check code: {code}")
    try:
        answer = input("Does this match the code on the phone? [y/N] ")
    except (EOFError, KeyboardInterrupt):
        return _fail("\nCancelled. Nothing was saved.")
    if answer.strip().lower() not in ("y", "yes"):
        return _fail("Cancelled. Nothing was saved.")
    final = os.path.join(KEY_DIR, user + ".pub")
    tmp = os.path.join(KEY_DIR, "." + user + ".pub.tmp")  # never a valid user name, so never read as a key
    try:
        os.makedirs(KEY_DIR, mode=0o755, exist_ok=True)
        os.chmod(KEY_DIR, 0o755)
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, 0o644)
        try:
            os.fchmod(fd, 0o644)
            os.write(fd, (public_key + "\n").encode("ascii"))
            os.fsync(fd)
        finally:
            os.close(fd)
        os.replace(tmp, final)
    except OSError as e:
        return _fail(f"Could not save {final}: {e.strerror or e}")
    print(f"Saved {final}")
    return 0


def main(argv: list, env: dict) -> int:
    try:
        if argv[1:] == ["enroll"]:
            return _enroll(env)
        return _pam(env)
    except BaseException:  # fail closed, silently: PAM goes on to the password
        return 1


if __name__ == "__main__":
    try:
        code = main(sys.argv, dict(os.environ))
    except BaseException:
        code = 1
    try:
        sys.stdout.flush()
    except BaseException:
        pass
    os._exit(code)  # no interpreter teardown output, ever
