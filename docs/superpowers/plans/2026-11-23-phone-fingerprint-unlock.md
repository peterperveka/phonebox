# Phone Fingerprint Unlock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> Implemented by agents in the worktree `.claude/worktrees/fingerprint` (branch `feature/phone-fingerprint-unlock`).
> The "Why" paragraphs stay: the owner reads the finished code to learn from it. Task 9 (PAM, `/usr/local`) is done
> **by the owner by hand** — agents never run `sudo` or edit `/etc`.

**Goal:** `sudo`, GNOME's polkit dialogs (1Password system unlock) and the locked GNOME screen accept a fingerprint on the paired Android phone; the password always keeps working.

**Architecture:** The phone holds a biometric-bound EC key and signs one-time challenges. For `sudo` and polkit, a root-owned Python PAM helper makes the challenge and verifies the signature itself; the PhoneBox agent only carries messages. For the screen, the agent makes and verifies the challenge and calls `loginctl unlock-session`.

**Tech Stack:** C# / .NET 10 + xUnit (agent), Python 3 stdlib + `python3-cryptography` + `unittest` (helper), Kotlin + framework `BiometricPrompt` + Android Keystore + JUnit 4 (phone), plain JS (panel).

**Spec:** `docs/superpowers/specs/2026-11-23-phone-fingerprint-unlock-design.md`

## Global Constraints

- Feature is **off by default** (`unlock.enabled = false`); with it off, nothing behaves differently.
- No new dependencies in the agent or the Android app. Helper: Python stdlib + `python3-cryptography` only.
- Android `minSdk` stays 21; the feature runs only when `Build.VERSION.SDK_INT >= 28`.
- Challenge format (exactly 7 `|`-separated fields, UTF-8):
  `phonebox-unlock-v1|<purpose>|<user>|<host>|<command>|<nonce>|<expiresAtMs>`
  - `purpose` ∈ `sudo`, `polkit`, `screen`; `nonce` = 64 lowercase hex chars (32 random bytes); `command` = `-` for `polkit` and `screen`.
  - "Request purposes" (helper → agent → phone card) = `sudo`, `polkit`. The Unlock PC button signs only `screen`.
  - `command` ≤ 120 chars, no `|`, no control characters.
- Key: EC P-256, public key exchanged as **base64 of X.509 SubjectPublicKeyInfo (DER)**.
- Signature: ECDSA P-256 / SHA-256, **DER encoded, base64**.
- Check code: first 20 hex chars of SHA-256(SPKI DER), uppercase, groups of 4 → `AB12 CD34 EF56 7890 1A2B`.
- Timings: request challenge expires 30 s after creation; helper waits ≤ 20 s (`sudo`) / ≤ 10 s (`polkit`); screen challenge lives 60 s; "phone present" = seen in the last 5 s (`ApprovalStore.PhoneConnected`).
- Helper prompt text (exact): `Touch the sensor on the phone, or press Enter to type the password.`
- Only one pending request at a time; a new one ends the old one (it gets no signature).
- Phone card buttons: **Use fingerprint** and **Use password** (the latter answers the request with `signature: null` = cancelled).
- Android build: JDK **21** (Gradle 8.11.1 does not run on the system's JDK 25), SDK at `~/Android/Sdk` with `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`.
- Every signature is single-use.

## Review Focus

1. **Agent not running / port closed** → helper returns 1 within 1 s, no traceback on the terminal (Task 5 test `test_main_returns_1_when_agent_down`).
2. **Phone answers after the helper gave up** (Enter or timeout) → the answer is ignored and returns `false`; the card disappears on the next state poll (Task 2 test `AnswerAfterCancelIsIgnored`).
3. **Replay** → the same screen signature used twice unlocks once; a signature for challenge A never verifies B (Task 1 `SignatureForOtherChallengeFails`, Task 3 `ScreenSignatureWorksOnlyOnce`).
4. **Unlock tapped when the screen is already unlocked** → no `loginctl` call, returns `false` (Task 3 `UnlockWhenNotLockedDoesNothing`).
5. **polkit dialog with the phone nearby but ignored** → password field appears after 10 s; **Use password** on the phone → at once (Task 5 `test_polkit_mode_waits_without_tty`, Task 2 `PhoneCancelReturnsNull`, Task 9 manual).
6. **Garbage input** (non-base64 signature, broken key file, 6-field challenge) → verify returns false / helper returns 1, never throws (Task 1 `GarbageNeverThrows`, Task 5 `test_verify_garbage_is_false`).

---

## Task 0: Android build environment (no repo changes)

**Why:** The phone app must be built to test Tasks 6–8; this machine has no Android SDK and only JDK 25.

- [ ] **Step 1: JDK 21 in user space.** Download Eclipse Temurin 21 (Linux x64 `.tar.gz`) from `https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse` into its own empty directory, extract to `~/.local/share/jdk-21`. Check: `~/.local/share/jdk-21/bin/java -version` → `21.`.
- [ ] **Step 2: Android command-line tools.** Download the current `commandlinetools-linux-*_latest.zip` (dl.google.com, linked from `https://developer.android.com/studio#command-line-tools-only`), extract so that `~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager` exists.
- [ ] **Step 3: SDK packages.** With `JAVA_HOME=~/.local/share/jdk-21`: `sdkmanager --sdk_root=$HOME/Android/Sdk "platforms;android-35" "build-tools;35.0.0" "platform-tools"`; accept the licenses (the owner approved installing the SDK; tell him the licenses were accepted).
- [ ] **Step 4: Point the build at it.** Create `android/local.properties` with `sdk.dir=/home/peterperveka/Android/Sdk` (must be git-ignored — check with `git check-ignore android/local.properties`; never commit it).
- [ ] **Step 5: Baseline build.** `cd android && JAVA_HOME=~/.local/share/jdk-21 ./gradlew assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL` before any change. Every later Android command in this plan uses this `JAVA_HOME`.

---

## File map

| File | Responsibility |
|---|---|
| `agent/PhoneBox.Agent/UnlockChallenge.cs` (new) | create / parse / verify challenges, check code — pure functions |
| `agent/PhoneBox.Agent/UnlockStore.cs` (new) | the one pending sudo request, long-poll, answer |
| `agent/PhoneBox.Agent/Unix/LoginSession.cs` (new) | find graphical session, read `LockedHint`, unlock (via `Sh.Run`) |
| `agent/PhoneBox.Agent/UnlockService.cs` (new) | screen challenge + screen unlock; lock-change events |
| `agent/PhoneBox.Agent/Config.cs` (modify) | `UnlockConfig` |
| `agent/PhoneBox.Agent/Program.cs` (modify) | endpoints, DI registration |
| `agent/PhoneBox.Agent/Lan.cs:89` (modify) | phone-allowed paths |
| `agent/PhoneBox.Agent/settings.html` (modify) | on/off checkbox |
| `tools/unlock/phonebox_unlock.py` (new) | the PAM helper (+ `enroll`) |
| `tools/unlock/test_phonebox_unlock.py` (new) | helper tests |
| `android/.../deskpanel/UnlockChallenge.kt` (new) | parse / accept / prompt text / check code — pure |
| `android/.../deskpanel/UnlockKey.kt` (new) | Keystore key + BiometricPrompt signing |
| `android/.../deskpanel/MainActivity.kt` (modify) | JS bridge methods |
| `android/app/src/main/AndroidManifest.xml` (modify) | `USE_BIOMETRIC` |
| `android/app/src/main/assets/www/main.js`, `index.html`, `style.css` (modify) | sudo card, Unlock PC button, Set up button |

(`android/...` = `android/app/src/main/java/net/oxog/phonebox/deskpanel`)

## Agent HTTP API (used by Tasks 4, 5, 7, 8)

| Method & path | Who calls | Body → Reply |
|---|---|---|
| `GET /api/unlock/state` | phone | → `{enabled, enrolled, locked, screen: "<challenge>"\|null, request: {id, challenge}\|null}` |
| `POST /api/unlock/enroll` | phone | `{publicKey}` → 200 |
| `POST /api/unlock/answer` | phone | `{id, signature\|null}` → 200 / 404 |
| `POST /api/unlock/screen` | phone | `{challenge, signature}` → 200 unlocked / 403 |
| `GET /api/unlock/key` | helper (loopback) | → `{publicKey, code}` / 404 |
| `POST /api/unlock/request` | helper (loopback) | `{challenge}` → 200 `{signature}` / 204 (no phone, disabled, timeout) / 400 |

The helper **cancels by closing the connection** (no DELETE endpoint): the aborted request's `CancellationToken`
ends the pending request. This replaces the spec's `DELETE /api/unlock/{id}` (spec leaves endpoint shapes to the builder).

---

### Task 1: Challenges in C#

**Why:** Everything else stands on this. You learn what a challenge–response is: the verifier invents a random
one-time text, the phone signs it, and only the holder of the private key can produce a signature that the public
key accepts — for *that exact text*.

**Files:**
- Create: `agent/PhoneBox.Agent/UnlockChallenge.cs`
- Test: `agent/PhoneBox.Agent.Tests/UnlockChallengeTests.cs`

**Interfaces:**
- Produces:
  - `public sealed record UnlockChallengeParts(string Purpose, string User, string Host, string Command, string Nonce, long ExpiresAt)`
  - `public static class UnlockChallenge`
    - `const string Prefix = "phonebox-unlock-v1"`
    - `static string Create(string purpose, string user, string host, string command, long expiresAtMs)`
    - `static UnlockChallengeParts? Parse(string challenge)` — null unless 7 fields, prefix matches, purpose valid, nonce 64 lowercase hex, expiry parses
    - `static string CleanCommand(string command)` — drop `|` and chars < 0x20, trim, cut to 120
    - `static bool Verify(string publicKeyB64, string challenge, string signatureB64)` — never throws
    - `static string? CheckCode(string publicKeyB64)` — null on bad key
    - `static bool IsRequestPurpose(string purpose)` — `sudo` or `polkit`

- [ ] **Step 1: Write the failing tests** (generate a test key with `ECDsa.Create(ECCurve.NamedCurves.nistP256)`, export with `ExportSubjectPublicKeyInfo()`, sign with `SignData(bytes, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence)`)

```csharp
[Fact] public void CreateThenParseRoundTrips()  // Create("sudo","peter","fedora","sudo dnf up",123) → Parse gives same fields, Nonce.Length == 64
[Fact] public void TwoChallengesHaveDifferentNonces()
[Fact] public void ParseRejectsWrongShape()     // 6 fields, prefix "phonebox-unlock-v2", purpose "ssh", nonce "XYZ" → all null
[Fact] public void ParseAcceptsAllThreePurposes() // "sudo", "polkit", "screen" → not null
[Fact] public void OnlySudoAndPolkitAreRequestPurposes() // IsRequestPurpose("sudo") && IsRequestPurpose("polkit") && !IsRequestPurpose("screen")
[Fact] public void CleanCommandStripsPipesAndControlChars() // "a|b\nc" → "abc"; 200×'x' → Length 120
[Fact] public void ValidSignatureVerifies()
[Fact] public void SignatureForOtherChallengeFails()
[Fact] public void GarbageNeverThrows()         // Verify("!!", c, "??") == false; Verify(key, c, "") == false
[Fact] public void CheckCodeIsTwentyHexInGroupsOfFour() // Matches ^[0-9A-F]{4}( [0-9A-F]{4}){4}$ and equals SHA-256 of SPKI hex[..20]
```

- [ ] **Step 2: Run, expect compile failure**
  Run: `dotnet test agent/PhoneBox.Agent.Tests --filter UnlockChallengeTests` → FAIL (`UnlockChallenge` does not exist)
- [ ] **Step 3: Implement `UnlockChallenge`.** Nonce: `RandomNumberGenerator.GetBytes(32)` → `Convert.ToHexStringLower`. Verify: `ECDsa.ImportSubjectPublicKeyInfo`, `VerifyData(..., DSASignatureFormat.Rfc3279DerSequence)`, whole body in `try/catch → false`.
- [ ] **Step 4: Run tests** → PASS
- [ ] **Step 5: Commit** `feat(unlock): challenge create/parse/verify`

---

### Task 2: The pending request (`UnlockStore`) + config

**Why:** The helper's HTTP call must *wait* until the phone answers. You learn the long-poll pattern with a
`TaskCompletionSource`: one side awaits a task, the other side completes it later.

**Pattern to copy:** `ApprovalStore.cs` (same idea, more features) and its tests `ApprovalOutcomeTests.cs`
(note the `[Collection]` because `PhoneLastSeen` is static).

**Files:**
- Create: `agent/PhoneBox.Agent/UnlockStore.cs`
- Modify: `agent/PhoneBox.Agent/Config.cs` — add `public UnlockConfig Unlock { get; set; } = new();` to `AgentConfig`, and
  `public sealed class UnlockConfig { public bool Enabled { get; set; } public string? PublicKey { get; set; } }`
- Test: `agent/PhoneBox.Agent.Tests/UnlockStoreTests.cs`

**Interfaces:**
- Consumes: `PushStore.Emit(string kind, JsonElement? data)`, `ApprovalStore.PhoneConnected`
- Produces:
  - `public sealed record UnlockRequest(string Id, string Challenge)`
  - `public sealed class UnlockStore(PushStore events, ILogger<UnlockStore> log)`
    - `Task<string?> RequestAsync(string challenge, TimeSpan timeout, CancellationToken ct)` — signature or null; emits `"unlock"` when it starts and when it ends
    - `UnlockRequest? Pending()`
    - `bool Answer(string id, string? signature)` — false if id is not the pending one

- [ ] **Step 1: Failing tests**

```csharp
[Fact] public async Task AnswerCompletesTheRequest()        // start RequestAsync, Pending()!.Id → Answer(id,"sig") → awaited == "sig", Pending() == null
[Fact] public async Task PhoneCancelReturnsNull()           // Answer(id, null) → null
[Fact] public async Task TimeoutReturnsNull()               // timeout 50 ms → null, Pending() == null
[Fact] public async Task ClosedConnectionEndsTheRequest()   // cancel the CancellationToken → null, Pending() == null
[Fact] public async Task AnswerAfterCancelIsIgnored()       // after ct cancel: Answer(oldId,"sig") == false
[Fact] public async Task NewRequestEndsTheOldOne()          // second RequestAsync → first returns null
[Fact] public void UnlockIsOffByDefault()                   // new AgentConfig().Unlock.Enabled == false
```

- [ ] **Step 2: Run** `dotnet test agent/PhoneBox.Agent.Tests --filter UnlockStoreTests` → FAIL
- [ ] **Step 3: Implement.** Id: 16 random hex. Keep `(UnlockRequest, TaskCompletionSource<string?>)` in one field guarded by a `Lock`; `TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously)`. Use `Task.WhenAny(tcs.Task, Task.Delay(timeout, ct))`, and clear the field in `finally` only if it is still this request.
- [ ] **Step 4: Run** → PASS
- [ ] **Step 5: Commit** `feat(unlock): pending sudo request store and config`

---

### Task 3: Screen lock + unlock (`LoginSession`, `UnlockService`)

**Why:** You learn how GNOME and systemd-logind talk: GNOME sets `LockedHint` on your session when it locks, and
listens for logind's `Unlock` signal, which `loginctl unlock-session <id>` sends. You also learn to put the
outside world (shell commands) behind an interface so the logic can be tested.

**Files:**
- Create: `agent/PhoneBox.Agent/Unix/LoginSession.cs`, `agent/PhoneBox.Agent/UnlockService.cs`
- Test: `agent/PhoneBox.Agent.Tests/UnlockServiceTests.cs`

**Interfaces:**
- Consumes: `UnlockChallenge.Create/Parse/Verify`, `ConfigStore.Get().Unlock`, `PushStore.Emit`, `Sh.Run(string tool, params string[] args) → string?`
- Produces:
  - `public interface ILoginSession { string? GraphicalSessionId(); bool IsLocked(string id); bool Unlock(string id); }`
  - `public sealed class LoginSession : ILoginSession` —
    `GraphicalSessionId`: `loginctl show-user <Environment.UserName> -p Display --value` (empty → null);
    `IsLocked`: `loginctl show-session <id> -p LockedHint --value` == `"yes"`;
    `Unlock`: `loginctl unlock-session <id>` (non-null output → true)
  - `public sealed class UnlockService(ConfigStore cfg, ILoginSession session, PushStore events, ILogger<UnlockService> log, TimeProvider clock) : BackgroundService`
    - `bool Locked { get; }` — refreshed every 1 s in `ExecuteAsync`, emits `"unlock"` on change; always false unless Linux + enabled
    - `string? ScreenChallenge()` — null unless enabled, enrolled and locked; same challenge until it expires (60 s), then a new one
    - `bool UnlockScreen(string challenge, string signatureB64)` — challenge must equal the one issued, unexpired, verify OK, still locked → `session.Unlock`; the issued challenge is cleared either way (single use)

- [ ] **Step 1: Failing tests** (fake `ILoginSession` with a `Locked` flag and an `UnlockCalls` counter; fake clock like `ApprovalOutcomeTests.Clock`; key from Task 1 test helper)

```csharp
[Fact] public void NoChallengeWhenDisabledOrNotEnrolledOrUnlocked()
[Fact] public void ChallengeIsScreenPurposeAndStableUntilExpiry() // Parse(..).Purpose == "screen", Command == "-"; same string at +59 s, new one at +61 s
[Fact] public void ValidSignatureUnlocks()                         // UnlockCalls == 1
[Fact] public void ScreenSignatureWorksOnlyOnce()                  // second UnlockScreen with same args == false, UnlockCalls == 1
[Fact] public void SudoChallengeIsRefused()                        // a valid signature over a "sudo" challenge → false
[Fact] public void UnlockWhenNotLockedDoesNothing()                // Locked=false → false, UnlockCalls == 0
```

- [ ] **Step 2: Run** `--filter UnlockServiceTests` → FAIL
- [ ] **Step 3: Implement** both classes. Make `Refresh()` (one lock check) `internal` so tests call it without the loop.
- [ ] **Step 4: Run** → PASS
- [ ] **Step 5: Manual check:** `loginctl show-user $USER -p Display --value` prints an id; lock the screen (Super+L) and from SSH or a timer `loginctl show-session <id> -p LockedHint --value` prints `yes`.
- [ ] **Step 6: Commit** `feat(unlock): GNOME screen lock state and unlock`

---

### Task 4: Endpoints, access rules, settings switch

**Why:** You learn how the agent separates *who* may call *what*: the phone reaches only an allow-list of paths
(`Lan.AllowedOverLan`, also used for USB), while the helper's endpoints must stay on `127.0.0.1`.

**Files:**
- Modify: `agent/PhoneBox.Agent/Program.cs` (register `TimeProvider.System` if not already registered, `UnlockStore`, `ILoginSession → LoginSession`, `UnlockService` as singleton + hosted service; map the 6 endpoints from the API table next to `/api/approvals` ~line 913)
- Modify: `agent/PhoneBox.Agent/Lan.cs:89` — allow exactly `/api/unlock/state`, `/api/unlock/enroll`, `/api/unlock/answer`, `/api/unlock/screen` (exact `==`, not `StartsWithSegments("/api/unlock")`)
- Modify: `agent/PhoneBox.Agent/settings.html` — next to `followComputerLockOption` (~line 766): `<label class="check" id="unlockOption"><input type="checkbox" data-bind="unlock.enabled"> <span data-i18n="s.unlock.enabled">Fingerprint unlock from the phone (sudo, password dialogs such as 1Password, and the GNOME lock screen; Linux)</span></label>`, shown only when `osName === 'linux'` (copy line 3566)
- Test: `agent/PhoneBox.Agent.Tests/UnlockAccessTests.cs`

**Endpoint rules:**
- `/request`: 204 unless enabled + enrolled + `ApprovalStore.PhoneConnected`; 400 unless `Parse(..)` is not null and `IsRequestPurpose(parts.Purpose)`; else `RequestAsync(challenge, 20 s, ctx.RequestAborted)` → 200 `{signature}` or 204 (a polkit helper's shorter wait ends it earlier by closing the connection).
- `/enroll`: 400 unless `CheckCode(publicKey) != null`; save to `Unlock.PublicKey`, log the check code at info level.
- `/key`: 404 unless enrolled; `{publicKey, code}`.

- [ ] **Step 1: Failing tests**

```csharp
[Theory] [InlineData("/api/unlock/state")] [InlineData("/api/unlock/enroll")] [InlineData("/api/unlock/answer")] [InlineData("/api/unlock/screen")]
public void PhonePathsAreAllowed(string p)        // Lan.AllowedOverLan(p) == true
[Theory] [InlineData("/api/unlock/request")] [InlineData("/api/unlock/key")] [InlineData("/api/unlock")]
public void HelperPathsStayLocal(string p)        // Lan.AllowedOverLan(p) == false
```

- [ ] **Step 2: Run** `--filter UnlockAccessTests` → FAIL
- [ ] **Step 3: Implement** the Lan change, endpoints, DI, settings checkbox.
- [ ] **Step 4: Run the whole suite** `dotnet test agent/PhoneBox.Agent.Tests` → all PASS (nothing else broke)
- [ ] **Step 5: Manual check:** run the agent from the branch (`dotnet run --project agent/PhoneBox.Agent` after stopping the installed one), `curl -s http://127.0.0.1:8765/api/unlock/state` → `{"enabled":false,...}`; tick the box in settings → `enabled:true`; `curl -X POST -d '{"challenge":"x"}' -H 'Content-Type: application/json' http://127.0.0.1:8765/api/unlock/request -w '%{http_code}'` → `204`.
- [ ] **Step 6: Commit** `feat(unlock): agent endpoints and settings switch`

---

### Task 5: The PAM helper (Python) — `sudo` and polkit modes

**Why:** This is the security core. You learn PAM (`pam_exec` runs your program as root with `PAM_USER`, `PAM_TTY`,
`PAM_RHOST` in the environment; exit 0 = accept, anything else = go on to the password), and `select()` — waiting on
two things at once (the network and the keyboard).

**Files:**
- Create: `tools/unlock/phonebox_unlock.py` (installed later as `/usr/local/lib/phonebox/phonebox-unlock`, so it starts with `#!/usr/bin/python3 -I`). Module constants: `KEY_DIR`, `AGENT_PORT = 8765`, `SUDO_WAIT = 20`, `POLKIT_WAIT = 10`.
- Create: `tools/unlock/test_phonebox_unlock.py`

**Interfaces:**
- Consumes: agent `/api/unlock/request` and `/api/unlock/key` on `127.0.0.1:8765`; challenge format from Global Constraints
- Produces (module functions):
  - `make_challenge(purpose: str, user: str, host: str, command: str, now_ms: int) -> str` — `purpose` is `sudo` or `polkit` (`polkit` → command `-`); expiry `now_ms + 30_000`
  - `mode(env: dict) -> str | None` — `PAM_SERVICE` `sudo`/`sudo-i` → `"sudo"`, `polkit-1` → `"polkit"`, else None
  - `clean_command(raw: bytes) -> str` — `/proc/<pid>/cmdline` bytes: NUL → space, then same rules as C# `CleanCommand`
  - `verify(public_key_b64: str, challenge: str, signature_b64: str) -> bool` — never raises
  - `skip_reason(env: dict, key_exists: bool, locked: bool) -> str | None` — `"service"` (mode None), `"no key"`, `"remote"` (`PAM_RHOST` non-empty), `"no tty"` (**sudo mode only**: `PAM_TTY` empty or not starting with `tty`/`pts`/`/dev/`), `"locked"`, else None
  - `check_code(public_key_b64: str) -> str` — same as C#
  - `main(argv: list[str], env: dict) -> int` — `argv[1:] == ["enroll"]` → enroll; otherwise the PAM flow
- Key file: `/etc/phonebox/<user>.pub`, one line, the base64 SPKI. Lock check: same two `loginctl` calls as Task 3 (`show-user <user>`).

**PAM flow in `main` (sudo mode):** skip_reason → return 1 · read parent cmdline (`/proc/{os.getppid()}/cmdline`) · `make_challenge` · open the tty (`/dev/<PAM_TTY>` unless it already starts with `/dev/`), write the exact prompt text **to the tty** (not stdout: pam_exec does not pass stdout through without its `stdout` option, which we do not use) · `http.client.HTTPConnection("127.0.0.1", 8765, timeout=1)` send POST · `select([conn.sock, tty], [], [], 20)` · tty readable → close connection, return 1 · socket readable → `getresponse()`, 200 → `verify` → 0 else 1 · any exception → 1.
**polkit mode:** skip_reason → 1 · `make_challenge("polkit", …, "-", …)` · no tty, prints nothing · POST · wait on the socket only, at most `POLKIT_WAIT` (`select([conn.sock], [], [], POLKIT_WAIT)`) · 200 → verify → 0 / 1; 204 (phone pressed **Use password**, no phone, disabled) or timeout → 1.
**Enroll:** must run as root (`os.geteuid() == 0`) · GET `/api/unlock/key` · print the check code and `Does this match the code on the phone? [y/N]` · `y` → write `/etc/phonebox/$SUDO_USER.pub` mode 0644, dir 0755.

- [ ] **Step 1: Test dependency.** `python3-cryptography` is not installed system-wide and agents do not run `sudo`: `python3 -m venv .venv-unlock && .venv-unlock/bin/pip install cryptography` in the worktree root (add `.venv-unlock/` to the repo's `.git/info/exclude`), and run the tests with `.venv-unlock/bin/python`. The owner installs `python3-cryptography` with dnf in Task 9.
- [ ] **Step 2: Failing tests** (`unittest`; sign with `cryptography`'s `ec.generate_private_key(ec.SECP256R1())`, `sign(data, ec.ECDSA(hashes.SHA256()))` — already DER)

```python
def test_challenge_has_seven_fields_and_30s_expiry(self)   # make_challenge("sudo", …): split('|') len 7, [1]=="sudo", int([6]) == now+30000, len([5])==64
def test_clean_command(self)                               # b"sudo\x00dnf\x00a|b" → "sudo dnf ab"; 300 bytes → len 120
def test_verify_good_signature(self)
def test_verify_other_challenge_is_false(self)
def test_verify_garbage_is_false(self)                     # bad base64 key / sig → False, no exception
def test_skip_reasons(self)                                # each reason + None for {"PAM_SERVICE":"sudo","PAM_TTY":"/dev/pts/3"}
def test_mode_by_service(self)                             # sudo, sudo-i → "sudo"; polkit-1 → "polkit"; login, gdm-password → None
def test_polkit_mode_needs_no_tty(self)                    # skip_reason({"PAM_SERVICE":"polkit-1"}, True, False) is None
def test_polkit_challenge_has_dash_command(self)           # parts = make_challenge("polkit", …).split('|'); parts[1] == "polkit" and parts[4] == "-"
def test_polkit_mode_waits_without_tty(self)               # a listening socket that never answers as the agent (patch AGENT_PORT), POLKIT_WAIT patched to 0.2 → main returns 1 within 1 s, no tty opened
def test_check_code_matches_csharp_format(self)            # regex ^[0-9A-F]{4}( [0-9A-F]{4}){4}$
def test_main_returns_1_when_agent_down(self)              # point the port at a closed one (module constant AGENT_PORT patched), key file in a temp dir, fake tty → returns 1 in < 1 s
```

- [ ] **Step 3: Run** `.venv-unlock/bin/python -m unittest discover -s tools/unlock -v` from the repo root → FAIL (module missing)
- [ ] **Step 4: Implement** `phonebox_unlock.py`. Keep paths (`KEY_DIR`, `AGENT_PORT`) as module constants so tests can patch them.
- [ ] **Step 5: Run** → PASS
- [ ] **Step 6: Commit** `feat(unlock): PAM helper for sudo and polkit`

---

### Task 6: Challenge rules on the phone (Kotlin, pure)

**Why:** The phone must not trust the agent. You learn to make the decision ("is this something I am willing to
sign, and what do I tell the user?") from the signed bytes only — and to keep that logic in a pure function that a
plain JUnit test can check without a phone.

**Pattern to copy:** `WakePacket.kt` + `src/test/.../WakeTest.kt`.

**Files:**
- Create: `android/app/src/main/java/net/oxog/phonebox/deskpanel/UnlockChallenge.kt`
- Test: `android/app/src/test/java/net/oxog/phonebox/deskpanel/UnlockChallengeTest.kt`

**Interfaces:**
- Produces: `object UnlockChallenge`
  - `data class Parts(val purpose: String, val user: String, val host: String, val command: String, val nonce: String, val expiresAt: Long)`
  - `fun parse(s: String): Parts?` — same rules as C# `Parse`
  - `const val MODE_REQUEST = "request"`, `const val MODE_SCREEN = "screen"`
  - `fun acceptable(p: Parts, mode: String, nowMs: Long): Boolean` — `request` accepts purpose `sudo`/`polkit`, `screen` accepts only `screen`; and `expiresAt > nowMs`
  - `fun promptTitle(p: Parts): String` — `sudo` → `"sudo — ${p.user}@${p.host}"`, `polkit` → `"System authentication — ${p.user}@${p.host}"`, `screen` → `"Unlock PC — ${p.user}@${p.host}"`
  - `fun promptDetail(p: Parts): String?` — `sudo` → `p.command`, otherwise null
  - `fun checkCode(spki: ByteArray): String`

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun parsesAGoodChallenge()
@Test fun rejectsWrongShapes()                       // 6 fields, v2 prefix, purpose "ssh", short nonce → null
@Test fun screenButtonRefusesSudoChallenge()         // acceptable(sudoParts, MODE_SCREEN, now) == false
@Test fun requestCardRefusesScreenChallenge()        // acceptable(screenParts, MODE_REQUEST, now) == false; sudo and polkit parts → true
@Test fun polkitPromptText()                         // promptTitle == "System authentication — peter@fedora", promptDetail == null
@Test fun expiredIsRefused()
@Test fun promptTextComesFromTheChallenge()          // promptTitle(sudo) == "sudo — peter@fedora", promptDetail == "sudo dnf up"
@Test fun checkCodeFormat()                          // matches ^[0-9A-F]{4}( [0-9A-F]{4}){4}$; equals the C# value for the same bytes (copy one fixed SPKI + code from a C# test run)
```

- [ ] **Step 2: Run** `cd android && JAVA_HOME=~/.local/share/jdk-21 ./gradlew testDebugUnitTest --tests '*UnlockChallengeTest'` → FAIL
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS
- [ ] **Step 5: Commit** `feat(unlock): phone-side challenge rules`

---

### Task 7: Key + fingerprint on the phone, JS bridge

**Why:** You learn the Android Keystore: the private key is created inside secure hardware and never leaves it, and
with "user authentication required, per use" Android lets you use it only inside a successful BiometricPrompt. That
is what turns "touch the sensor" into a signature nobody can forge.

**Files:**
- Create: `android/.../deskpanel/UnlockKey.kt`
- Modify: `android/.../deskpanel/MainActivity.kt` (bridge object, next to `setVolumeKeys` ~line 465)
- Modify: `android/app/src/main/AndroidManifest.xml` — `<uses-permission android:name="android.permission.USE_BIOMETRIC" />`

**Interfaces:**
- Consumes: `UnlockChallenge` (Task 6), `AgentLink.request(method, pathAndQuery, body: ByteArray?, contentType: String?) → Reply`, API table above
- Produces:
  - `class UnlockKey(private val activity: Activity)` (all `@RequiresApi(28)`)
    - `fun exists(): Boolean`
    - `fun create(): ByteArray` — alias `phonebox-unlock`, EC `secp256r1`, `PURPOSE_SIGN`, `DIGEST_SHA256`, `setUserAuthenticationRequired(true)`, API ≥ 30 `setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)` else `setUserAuthenticationValidityDurationSeconds(-1)`, `setInvalidatedByBiometricEnrollment(true)`; returns `public.encoded` (SPKI)
    - `fun remove()`
    - `fun sign(challenge: String, title: String, detail: String?, onDone: (String?) -> Unit)` — framework `BiometricPrompt` (API ≥ 30 `setAllowedAuthenticators(BIOMETRIC_STRONG)`), negative button "Cancel", `CryptoObject(Signature("SHA256withECDSA") initialised with the private key)`; success → base64 (NO_WRAP) DER signature; cancel / error / key invalidated → null
  - Bridge (`@JavascriptInterface`, first arg `key`, check `ok(key)`):
    - `unlockSupported(key): Boolean` — SDK ≥ 28
    - `unlockSetup(key): String` — `create()`, POST `/api/unlock/enroll`, return `checkCode` ("" on failure)
    - `unlockCode(key): String` — check code of the existing key or ""
    - `unlockSign(key, id: String, challenge: String, mode: String)` — `mode` is `UnlockChallenge.MODE_REQUEST` or `MODE_SCREEN`; `parse` + `acceptable(mode)` else report failure; `sign(...)`; POST `/api/unlock/answer {id, signature}` (request) or `/api/unlock/screen {challenge, signature}` (screen); then `window.pbUnlockDone(id, ok)` via `evaluateJavascript` on the UI thread
    - `unlockPrompt(key, challenge: String): String` — JSON `{"title","detail"}` from `promptTitle`/`promptDetail`, or `""` if `parse` fails — the card shows this, never agent text
  - Network calls on a background thread (`AgentLink.request` blocks).

- [ ] **Step 1: Implement `UnlockKey` and the bridge methods.**
- [ ] **Step 2: Build** `cd android && JAVA_HOME=~/.local/share/jdk-21 ./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, tests PASS
- [ ] **Step 3: Manual check — owner, phone on the data cable** (`adb install -r android/app/build/outputs/apk/debug/*.apk`; call `PhoneBox.unlockSetup(key)` from the WebView console via `brave://inspect`): returns a 20-char code; the agent log prints the same code; `curl -s http://127.0.0.1:8765/api/unlock/key` shows the same `code`. Agents do not wait for this; they list it in their report.
- [ ] **Step 4: Commit** `feat(unlock): phone key and fingerprint signing`

---

### Task 8: Panel UI

**Why:** You connect the pieces the user sees. You learn how the panel already reacts to agent events
(`main.js:1395`) and polls (`main.js:1338`), and reuse that instead of adding a new channel.

**Pattern to copy:** the approval card — `loadApprovals` / `renderApproval` in `main.js:1182-1260` and `#approval` in `index.html` / `style.css`.

**Files:** `android/app/src/main/assets/www/main.js`, `index.html`, `style.css`

**Interfaces:**
- Consumes: `GET /api/unlock/state`, bridge from Task 7
- Produces: `loadUnlock()`, `renderUnlock()`, global `window.pbUnlockDone(id, ok)`

**Behaviour:**
- `loadUnlock()` runs on the same 5 s tick as `loadApprovals`, on stream events `unlock`, and on reconnect (lines 2507, 2594). Does nothing unless `state.enabled` and `unlockSupported`.
- `enabled && !enrolled` → a small **Set up fingerprint unlock** button; tap → `unlockSetup` → show the returned code for 30 s with the text "Compare this code with `sudo phonebox-unlock enroll` on the PC".
- `request` present → card like the approval card (buzz + wake like `renderApproval`), title/detail from `unlockPrompt(key, challenge)` (no agent text; `""` → don't show the card). Two buttons: **Use fingerprint** → `unlockSign(key, id, challenge, "request")`; **Use password** → `POST /api/unlock/answer {id, signature: null}`. The card hides when `request` disappears.
- `locked && screen` → **Unlock PC** button over the panel → `unlockSign(key, "screen", screen, "screen")`.
- i18n keys (English in the default locale): `unlock.useFingerprint` "Use fingerprint", `unlock.usePassword` "Use password", `unlock.unlockPc` "Unlock PC", `unlock.setup` "Set up fingerprint unlock", `unlock.failed` "Not unlocked".
- `pbUnlockDone(id, false)` → short "Not unlocked" text, no retry loop.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Run UI tests** `cd tools/ui-tests && npx playwright test` → no new failures.
- [ ] **Step 3: Rebuild the APK** (Task 7 Step 2 command) → BUILD SUCCESSFUL.
- [ ] **Step 4: Manual check — owner, on the phone:** lock the PC (Super+L) → **Unlock PC** appears within ~2 s → fingerprint → GNOME unlocks. Cancel the prompt → stays locked, password works.
- [ ] **Step 5: Commit** `feat(unlock): panel card, unlock button and setup`

---

### Task 9: Install on this PC and end-to-end test — **owner, by hand**

**Why:** Changing PAM is the one step that can lock you out, so you learn the safe way: backup, a root shell kept
open, test in a new terminal, undo ready.

**Files:**
- Create: `tools/unlock/README.md` — the install / test / undo steps below, so they live with the code. (Agents write and commit this file; the owner runs the steps.)

- [ ] **Step 1:** Settings → PhoneBox self-update **off** (it would overwrite the fork's build). Install the agent and APK built from the branch.
- [ ] **Step 2:** Settings → tick **Fingerprint unlock** → on the phone **Set up fingerprint unlock** → note the code.
- [ ] **Step 3:** `sudo dnf install python3-cryptography`, then `sudo install -D -m 0755 tools/unlock/phonebox_unlock.py /usr/local/lib/phonebox/phonebox-unlock && sudo /usr/local/lib/phonebox/phonebox-unlock enroll` → the code matches → `y`. Check: `ls -l /etc/phonebox/` → `root root ... peterperveka.pub`.
- [ ] **Step 4:** Open a second terminal and run `sudo -i` there; **leave it open until Step 6 passes.**
- [ ] **Step 5:** `sudo cp /etc/pam.d/sudo /etc/pam.d/sudo.bak`, then in `/etc/pam.d/sudo` replace the line `auth sufficient pam_howdy.so no_confirmation` with
  `auth       sufficient   pam_exec.so quiet /usr/local/lib/phonebox/phonebox-unlock`
- [ ] **Step 6:** In a **new** terminal, each must behave as stated:
  - `sudo -k; sudo true` → prompt text appears, phone card shows `sudo true` → fingerprint → succeeds without a password
  - `sudo -k; sudo true` → press Enter → password prompt at once → correct password works
  - phone Wi-Fi off → `sudo -k; sudo true` → password prompt with no delay
  - `sudo -k; echo | sudo -S true` (no tty) → password path, no phone card
  - `ssh localhost sudo -k true` (if sshd runs) → no phone card
- [ ] **Step 7: polkit / 1Password.** Root shell still open. `sudo cp /etc/pam.d/polkit-1 /etc/pam.d/polkit-1.bak`, then add the same `auth sufficient pam_exec.so quiet /usr/local/lib/phonebox/phonebox-unlock` line **above** `auth include system-auth`. Test:
  - `pkexec true` → GNOME dialog, phone card "System authentication — …" → fingerprint → passes
  - `pkexec true` → **Use password** on the phone → password field at once → password works
  - `pkexec true` → do nothing → password field after ~10 s
  - lock 1Password, unlock with system authentication → fingerprint → unlocked; the Brave extension is unlocked too
  - phone Wi-Fi off → `pkexec true` → password field at once
- [ ] **Step 8:** Undo if anything is wrong (from the root shell): `cp /etc/pam.d/sudo.bak /etc/pam.d/sudo`, `cp /etc/pam.d/polkit-1.bak /etc/pam.d/polkit-1`.
- [ ] **Step 9: Commit** (agents, before handing over) `docs(unlock): install, test and undo steps`
