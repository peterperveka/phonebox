# Phone fingerprint unlock (Linux) — design

Date: 2026-11-23 · Branch: `feature/phone-fingerprint-unlock` · Fork: `peterperveka/phonebox`

## Intent

Comfort: instead of typing the password, the owner touches the fingerprint sensor of the phone that already runs the
PhoneBox panel, and the Linux PC accepts it for:

1. `sudo` in a terminal,
2. unlocking the locked GNOME screen,
3. GNOME's system password dialogs (polkit) — above all **1Password's "unlock with system authentication"**, which
   also unlocks the 1Password browser extension; also software installs, settings etc. *(Added 2026-11-25.)*

The owner wants to write the code himself from the plan, to learn, so every part must stay small, readable and
explainable. Simplicity beats features.

## Goals

- `sudo` asks the phone first; a fingerprint on the phone is enough. No phone / no answer → normal password prompt.
- polkit dialogs (1Password unlock, …) ask the phone first the same way; the phone card has **Use password** to end the
  wait at once; otherwise the password field appears after 10 s; no phone → at once.
- When the GNOME session is locked, the panel shows an **Unlock PC** button; button + fingerprint unlocks the session.
- The password always keeps working, at every moment, with no extra delay when the phone is not around.
- Security: a valid fingerprint answer cannot be forged by anything on the LAN or by a process that only runs as the
  user (see Threat model).

## Non-goals

- The first login after boot (GDM login screen). Password stays, like macOS Touch ID after a restart.
- 1Password's first unlock after a PC restart (1Password always demands the master password then).
- Website passwords outside 1Password.
- Windows, macOS, iOS.
- Phones older than Android 9 (API 28). The owner's phone is a Xiaomi 2109119DG (Android 11+). On older phones the
  feature is simply not offered.
- The live-stream CPU / reconnect problem (separate piece of work).
- Upstream pull request. Possible later, not a goal now.

## Anti-goals (a failure even if it "works")

- Any change that can lock the owner out of his own machine (broken PAM, broken lock screen).
- The GNOME lock screen waiting for the phone before the password can be typed.
- The agent (running as the user, writable install in `~/.local/share`) deciding whether `sudo` succeeds.
- A design too complex to learn from.

## Approaches considered

- **A. One PAM helper for everything** (`sudo` and the GNOME lock screen via `gdm-password`). One mechanism, but the
  lock screen would show no password field until the helper times out, and editing the GNOME PAM stack risks a
  lockout. Rejected.
- **B. PAM helper for `sudo` only + `loginctl unlock-session` for the screen.** ← **chosen by the owner.** Lock screen
  untouched and never slowed down; two small, separate parts.
- **C. Fake fingerprint reader (an `fprintd` D-Bus service).** Looks native in GNOME, but a root D-Bus service, lots
  of code and hard debugging. Rejected as too complex to learn from.

## Threat model

- **LAN attacker:** already blocked by PhoneBox's TLS + paired key. On top of that every answer is a signature over a
  fresh one-time challenge, so a recorded answer cannot be replayed.
- **Malware running as the user:** it can rewrite the agent binary and talk to `127.0.0.1:8765`. Therefore, for
  `sudo`, the agent is only a **messenger**: the root-owned helper creates the challenge and verifies the signature
  itself with a root-owned public key. For the screen unlock the agent may verify on its own — anything running as
  the user can already call `loginctl unlock-session`, so this adds no new power.
- **Phone in someone else's hand:** the signing key lives in the Android Keystore and can only be used right after a
  strong biometric (class 3) check, every single time.

## How it works

### 1. Phone key (Android, Kotlin) — one-time setup

- The app creates an EC P-256 key pair in the Android Keystore, alias `phonebox-unlock`:
  `PURPOSE_SIGN`, `DIGEST_SHA256`, `setUserAuthenticationRequired(true)`, auth **per use**
  (API 30+: `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`; API 28–29:
  `setUserAuthenticationValidityDurationSeconds(-1)`), `setInvalidatedByBiometricEnrollment(true)`.
- Uses the framework `android.hardware.biometrics.BiometricPrompt` (API 28+) with a `CryptoObject(Signature)`.
  `MainActivity` is a plain `Activity`, the framework prompt works with it — **no new dependency**
  (androidx.biometric would force `FragmentActivity`).
- The public key (X.509 / SubjectPublicKeyInfo, base64) is sent once to the agent: `POST /api/unlock/enroll`.
  The agent stores it in its config. **The native code** (not the panel, not the agent) computes and shows the check
  code: the first 20 hex chars (80 bits) of SHA-256 of the key, in groups of 4 (`AB12 CD34 EF56 7890 1A2B`). The
  agent is untrusted for `sudo`, so the code must come from the Keystore key itself.
- **The phone decides what it signs, not the agent.** Before the BiometricPrompt opens, the Kotlin code parses the
  challenge and checks it: prefix `phonebox-unlock-v1`, expiry in the future, purpose `sudo`, `polkit` or `screen`.
  The prompt text is built from the parsed challenge (“sudo apt install … — peter@fedora” / “System authentication —
  peter@fedora” / “Unlock PC — peter@fedora”), never from text the agent sends. The **Unlock PC** button only ever
  signs a `screen` challenge; the request card only `sudo` or `polkit` ones.
- One phone, one key (one active Keystore alias, one key in the agent, one file per user in `/etc/phonebox/`). Setting
  it up again replaces the key, but only once the agent accepted the new one: the new key is made under a spare alias
  (`phonebox-unlock-a`/`-b`, the active one named in a preference) and the old one is deleted after HTTP 200, so a
  failed re-setup keeps the working key. After a new setup the owner runs
  `sudo /usr/local/lib/phonebox/phonebox-unlock enroll` again (the setup message says so).
- `/api/unlock/enroll` is refused (403) while `unlock.enabled` is off. For the screen unlock the agent prefers the
  root-owned `/etc/phonebox/<user>.pub` when it exists (an unreadable/missing file falls back to the enrolled key; a
  file with invalid content refuses), so a key enrolled later from another paired device cannot unlock the screen.

### 2. Sudo helper (Python, root-owned) — `phonebox-unlock`

- Installed to `/usr/local/lib/phonebox/phonebox-unlock` (root:root, 0755). Public key per user in
  `/etc/phonebox/<user>.pub` (root:root, 0644).
- `sudo /usr/local/lib/phonebox/phonebox-unlock enroll` (run once by the owner, and again after every new phone setup): asks the agent for the enrolled public key, prints its check
  code, the owner compares it with the code on the phone and confirms, the key is written to `/etc/phonebox/`.
- PAM: the phone line **replaces** the existing Howdy (face unlock) line in `/etc/pam.d/sudo` (owner's decision: he
  does not use face unlock), and the same line is added at the top of `/etc/pam.d/polkit-1`, which is first copied
  from the vendor file `/usr/lib/pam.d/polkit-1` (on Fedora there is no `/etc/pam.d/polkit-1`; a file there replaces
  the vendor stack completely). These are the only two PAM files touched:
  `auth sufficient pam_exec.so quiet /usr/local/lib/phonebox/phonebox-unlock`
  (`sudo-i` includes `sudo`, so `sudo -i` is covered too.)
- Two modes, chosen by `PAM_SERVICE`: **`sudo`** (described below) and **`polkit-1`** (differences after the list).
  Any other service → exit 1.
- Flow when called by `pam_exec` (user in `PAM_USER`):
  1. **Skip at once (exit 1 → password)** when: no key file for the user; `PAM_RHOST` is set; there is no
     terminal (`PAM_TTY` empty or not openable — scripts, IDEs); the user's graphical session is locked (owner is
     away); or the request is not from a local session ("remote"). sudo does not set `PAM_RHOST`, so the helper finds
     the requester's logind session itself: it walks up from its parent pid via `/proc/<pid>/stat` (at most 8 hops)
     until a `/proc/<pid>/cgroup` names a `session-<N>.scope`, then reads `loginctl show-session <N> -p Remote -p Type`
     and goes on only if `Remote=no` (polkit additionally needs `Type` `wayland` or `x11`). GNOME starts terminals and
     gnome-shell in the user's systemd manager (`user@<uid>.service`), outside any session scope; that case is vouched
     for by the user's graphical `Display` session (`Remote=no`, `Type` wayland/x11). No session found, or any error,
     → skip.
  2. Make the challenge: `phonebox-unlock-v1|sudo|<user>|<hostname>|<command>|<32 random bytes, hex>|<expiry unix ms>`.
     `<command>` is the `sudo …` command line, read by the helper (root) from `/proc/<parent pid>/cmdline`, with `|`
     and control characters removed and at most 120 chars (a cut command ends in `…`). The phone shows it in the
     prompt's multi-line description.
  3. Print to the terminal: **“Touch the sensor on the phone, or press Enter to type the password.”**
  4. `POST http://127.0.0.1:8765/api/unlock/request` with the challenge. The agent answers at once with “no phone” if no
     phone was seen in the last 5 s (existing `ApprovalStore.PhoneConnected`) → exit 1. Otherwise the helper waits
     with `select()` on **both** the HTTP socket and the terminal (`/dev/<PAM_TTY>`, opened by the helper):
     - Enter on the terminal → tell the agent to cancel (`DELETE /api/unlock/{id}`), exit 1 → password prompt at once;
     - signature from the phone → step 5;
     - nothing for **20 s** → exit 1.
  5. Verify with `python3-cryptography` (ECDSA P-256, SHA-256) against the key file **and** the exact challenge bytes
     it made itself. Valid and not expired → exit 0 (sudo passes). Anything else → exit 1.
- **polkit mode** (`PAM_SERVICE=polkit-1`, run by the setuid-root `polkit-agent-helper-1` behind GNOME's dialog):
  there is no terminal, so the "no tty" skip and the Enter key do not apply. Skips that remain: no key file, remote
  (`PAM_RHOST`, or no local graphical session found as above), session locked. Challenge purpose `polkit`, command `-` (PAM does not tell the helper which polkit
  action is being authorised). No text is printed. Wait at most **10 s**; the phone's **Use password** answers the
  request with "cancelled" → the agent ends the long-poll at once → exit 1 → GNOME shows the password field.
- Any error, timeout or missing agent → exit 1, so `sudo` / the dialog just asks for the password. The password therefore always
  works: phone away → no delay; phone nearby → one Enter.

### 3. Agent (C#) — messenger + screen unlock

New file `UnlockService.cs`, opt-in setting `unlock.enabled` (off by default) in `config.json`.

- `POST /api/unlock/enroll` (phone API): store the phone's public key.
- `GET  /api/unlock/key` (loopback only): the stored public key, for `phonebox-unlock enroll`.
- `POST /api/unlock/request` (loopback only): from the helper (purpose `sudo` or `polkit`); hold the request (like
  `ApprovalStore`) and long-poll until the phone answers (signature or cancel) or the helper's wait ends.
- `POST /api/unlock/{id}` (phone API): the phone's signature (or “cancelled”).
- The pending request reaches the phone the same way approvals do (snapshot polled by the panel / live stream event).
- **Screen lock:** a background check reads the user's graphical session `LockedHint` (`loginctl show-session
  <id> -p LockedHint`) every second and tells the panel `pcLocked: true/false`. When the owner taps **Unlock PC**,
  the agent creates its own challenge (`phonebox-unlock-v1|screen|…`), the phone signs it, the agent verifies it with
  the stored key and runs `loginctl unlock-session <id>`. GNOME Shell listens to logind's Unlock signal and
  dismisses the lock screen.

### 4. Panel (JavaScript, `android/app/src/main/assets/www/`)

- A card like the approval card (title/detail from the native parse of the challenge) with **Use fingerprint** →
  native bridge opens the BiometricPrompt and posts the result, and **Use password** → posts "cancelled".
- When `pcLocked` is true: an **Unlock PC** button (overlay, not a widget the owner must place).
- In settings: **Set up fingerprint unlock** (creates the key, shows the check code) and **Remove**.

## Install / undo (the owner does these by hand)

1. Build + install the agent and the app from the branch.
2. Settings → enable fingerprint unlock → set up on the phone → note the check code.
3. `sudo dnf install python3-cryptography` (not installed on this machine yet), then
   `sudo install -D -m 0755 tools/unlock/phonebox-unlock /usr/local/lib/phonebox/phonebox-unlock`, then
   `sudo /usr/local/lib/phonebox/phonebox-unlock enroll` (compare the 20-char code with the phone).
4. **Keep a root shell open** (`sudo -i` in a second terminal), back up `/etc/pam.d/sudo`, replace the `pam_howdy`
   line with the phone line, test in a new terminal: `sudo -k; sudo true` with the finger, then again with Enter +
   password, then with the phone's Wi-Fi off. Undo = restore the backup.
   (Howdy also sits in `/etc/pam.d/gdm-password`; this feature does not touch that file. Removing Howdy there or
   uninstalling it is a separate, optional step for the owner.)
5. polkit: root shell open; `sudo cp /usr/lib/pam.d/polkit-1 /etc/pam.d/polkit-1` (there is no `/etc` copy on
   Fedora, and an `/etc` file with only the new line would drop the password path), then add the line above
   `auth include system-auth` in the new `/etc/pam.d/polkit-1`. Undo = `rm /etc/pam.d/polkit-1` (falls back to the
   vendor file). Test: `pkexec true` → dialog → finger; again → **Use password** → password works; lock 1Password, unlock it
   with system authentication → finger; phone Wi-Fi off → password field at once.
6. Turn off PhoneBox's self-update while running the fork (it would replace the build with upstream).

## Constraints

- Lives in the fork; existing behaviour must not change while `unlock.enabled` is off.
- Follow the repo's conventions (tests in `PhoneBox.Agent.Tests`, `CONTRIBUTING.md`, i18n strings for new UI text —
  English first, others may fall back).
- `minSdk` stays 21; the feature checks `Build.VERSION.SDK_INT >= 28` at runtime.

## Accepted risk

polkit challenges carry no command, so the phone can only say "System authentication". A process running as the user
that triggers a polkit prompt gets the same card as 1Password would. Same mitigations as below; accepted.


Like Touch ID for `sudo` on a Mac: malware running as the user could start its own `sudo` and hope for a habitual
touch. Mitigated by showing the real command (read by the root helper, signed, displayed by the phone from the signed
bytes) and by skipping when there is no terminal or the session is locked. Not removed entirely; accepted.

## Left to the builder

- Exact JSON shapes of the new endpoints and how the request reaches the panel (reuse approval polling vs. stream).
- How the agent finds the graphical session id (`loginctl list-sessions` + `Type=wayland/x11`, or `$XDG_SESSION_ID`).
- Wording and look of the card and the Unlock button; tests to write.
