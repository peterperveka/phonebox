# Phone Fingerprint Unlock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **For the owner (learning):** do the tasks in order. Each task has a "Why" paragraph — read it first; it explains the
> idea you are practising. Write the code yourself from the signatures and tests; look at the existing file named in
> "Pattern to copy" when you are stuck.

**Goal:** `sudo` and the locked GNOME screen accept a fingerprint on the paired Android phone; the password always keeps working.

**Architecture:** The phone holds a biometric-bound EC key and signs one-time challenges. For `sudo`, a root-owned Python PAM helper makes the challenge and verifies the signature itself; the PhoneBox agent only carries messages. For the screen, the agent makes and verifies the challenge and calls `loginctl unlock-session`.

**Tech Stack:** C# / .NET 10 + xUnit (agent), Python 3 stdlib + `python3-cryptography` + `unittest` (helper), Kotlin + framework `BiometricPrompt` + Android Keystore + JUnit 4 (phone), plain JS (panel).

**Spec:** `docs/superpowers/specs/2026-11-23-phone-fingerprint-unlock-design.md`

## Global Constraints

- Feature is **off by default** (`unlock.enabled = false`); with it off, nothing behaves differently.
- No new dependencies in the agent or the Android app. Helper: Python stdlib + `python3-cryptography` only.
- Android `minSdk` stays 21; the feature runs only when `Build.VERSION.SDK_INT >= 28`.
- Challenge format (exactly 7 `|`-separated fields, UTF-8):
  `phonebox-unlock-v1|<purpose>|<user>|<host>|<command>|<nonce>|<expiresAtMs>`
  - `purpose` ∈ `sudo`, `screen`; `nonce` = 64 lowercase hex chars (32 random bytes); `command` = `-` for `screen`.
  - `command` ≤ 120 chars, no `|`, no control characters.
- Key: EC P-256, public key exchanged as **base64 of X.509 SubjectPublicKeyInfo (DER)**.
- Signature: ECDSA P-256 / SHA-256, **DER encoded, base64**.
- Check code: first 20 hex chars of SHA-256(SPKI DER), uppercase, groups of 4 → `AB12 CD34 EF56 7890 1A2B`.
- Timings: sudo challenge expires 30 s after creation; helper waits ≤ 20 s; screen challenge lives 60 s; "phone present" = seen in the last 5 s (`ApprovalStore.PhoneConnected`).
- Helper prompt text (exact): `Touch the sensor on the phone, or press Enter to type the password.`
- Only one pending sudo request at a time; a new one ends the old one (it gets no signature).
- Every signature is single-use.

## Review Focus

1. **Agent not running / port closed** → helper returns 1 within 1 s, no traceback on the terminal (Task 5 test `test_main_returns_1_when_agent_down`).
2. **Phone answers after the helper gave up** (Enter or timeout) → the answer is ignored and returns `false`; the card disappears on the next state poll (Task 2 test `AnswerAfterCancelIsIgnored`).
3. **Replay** → the same screen signature used twice unlocks once; a signature for challenge A never verifies B (Task 1 `SignatureForOtherChallengeFails`, Task 3 `ScreenSignatureWorksOnlyOnce`).
4. **Unlock tapped when the screen is already unlocked** → no `loginctl` call, returns `false` (Task 3 `UnlockWhenNotLockedDoesNothing`).
5. **Garbage input** (non-base64 signature, broken key file, 6-field challenge) → verify returns false / helper returns 1, never throws (Task 1 `GarbageNeverThrows`, Task 5 `test_verify_garbage_is_false`).

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

- [ ] **Step 1: Write the failing tests** (generate a test key with `ECDsa.Create(ECCurve.NamedCurves.nistP256)`, export with `ExportSubjectPublicKeyInfo()`, sign with `SignData(bytes, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence)`)

```csharp
[Fact] public void CreateThenParseRoundTrips()  // Create("sudo","peter","fedora","sudo dnf up",123) → Parse gives same fields, Nonce.Length == 64
[Fact] public void TwoChallengesHaveDifferentNonces()
[Fact] public void ParseRejectsWrongShape()     // 6 fields, prefix "phonebox-unlock-v2", purpose "ssh", nonce "XYZ" → all null
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

### Task 2: The pending sudo request (`UnlockStore`) + config

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
- Modify: `agent/PhoneBox.Agent/settings.html` — next to `followComputerLockOption` (~line 766): `<label class="check" id="unlockOption"><input type="checkbox" data-bind="unlock.enabled"> <span data-i18n="s.unlock.enabled">Fingerprint unlock from the phone (sudo and the GNOME lock screen, Linux)</span></label>`, shown only when `osName === 'linux'` (copy line 3566)
- Test: `agent/PhoneBox.Agent.Tests/UnlockAccessTests.cs`

**Endpoint rules:**
- `/request`: 204 unless enabled + enrolled + `ApprovalStore.PhoneConnected`; 400 unless `Parse(..)?.Purpose == "sudo"`; else `RequestAsync(challenge, 20 s, ctx.RequestAborted)` → 200 `{signature}` or 204.
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

### Task 5: The sudo helper (Python)

**Why:** This is the security core. You learn PAM (`pam_exec` runs your program as root with `PAM_USER`, `PAM_TTY`,
`PAM_RHOST` in the environment; exit 0 = accept, anything else = go on to the password), and `select()` — waiting on
two things at once (the network and the keyboard).

**Files:**
- Create: `tools/unlock/phonebox_unlock.py` (installed later as `/usr/local/lib/phonebox/phonebox-unlock`, so it starts with `#!/usr/bin/python3 -I`)
- Create: `tools/unlock/test_phonebox_unlock.py`

**Interfaces:**
- Consumes: agent `/api/unlock/request` and `/api/unlock/key` on `127.0.0.1:8765`; challenge format from Global Constraints
- Produces (module functions):
  - `make_challenge(user: str, host: str, command: str, now_ms: int) -> str` — expiry `now_ms + 30_000`
  - `clean_command(raw: bytes) -> str` — `/proc/<pid>/cmdline` bytes: NUL → space, then same rules as C# `CleanCommand`
  - `verify(public_key_b64: str, challenge: str, signature_b64: str) -> bool` — never raises
  - `skip_reason(env: dict, key_exists: bool, locked: bool) -> str | None` — `"no key"`, `"remote"` (`PAM_RHOST` non-empty), `"no tty"` (`PAM_TTY` empty or not starting with `tty`/`pts`/`/dev/`), `"locked"`, else None
  - `check_code(public_key_b64: str) -> str` — same as C#
  - `main(argv: list[str], env: dict) -> int` — `argv[1:] == ["enroll"]` → enroll; otherwise the PAM flow
- Key file: `/etc/phonebox/<user>.pub`, one line, the base64 SPKI. Lock check: same two `loginctl` calls as Task 3 (`show-user <user>`).

**PAM flow in `main`:** skip_reason → return 1 · read parent cmdline (`/proc/{os.getppid()}/cmdline`) · `make_challenge` · open the tty (`/dev/<PAM_TTY>` unless it already starts with `/dev/`), write the exact prompt text **to the tty** (not stdout: pam_exec does not pass stdout through without its `stdout` option, which we do not use) · `http.client.HTTPConnection("127.0.0.1", 8765, timeout=1)` send POST · `select([conn.sock, tty], [], [], 20)` · tty readable → close connection, return 1 · socket readable → `getresponse()`, 200 → `verify` → 0 else 1 · any exception → 1.
**Enroll:** must run as root (`os.geteuid() == 0`) · GET `/api/unlock/key` · print the check code and `Does this match the code on the phone? [y/N]` · `y` → write `/etc/phonebox/$SUDO_USER.pub` mode 0644, dir 0755.

- [ ] **Step 1: Install test dependency** `sudo dnf install python3-cryptography`
- [ ] **Step 2: Failing tests** (`unittest`; sign with `cryptography`'s `ec.generate_private_key(ec.SECP256R1())`, `sign(data, ec.ECDSA(hashes.SHA256()))` — already DER)

```python
def test_challenge_has_seven_fields_and_30s_expiry(self)   # split('|') len 7, [1]=="sudo", int([6]) == now+30000, len([5])==64
def test_clean_command(self)                               # b"sudo\x00dnf\x00a|b" → "sudo dnf ab"; 300 bytes → len 120
def test_verify_good_signature(self)
def test_verify_other_challenge_is_false(self)
def test_verify_garbage_is_false(self)                     # bad base64 key / sig → False, no exception
def test_skip_reasons(self)                                # each of the 4 reasons + None for {"PAM_TTY":"/dev/pts/3"}
def test_check_code_matches_csharp_format(self)            # regex ^[0-9A-F]{4}( [0-9A-F]{4}){4}$
def test_main_returns_1_when_agent_down(self)              # point the port at a closed one (module constant AGENT_PORT patched), key file in a temp dir, fake tty → returns 1 in < 1 s
```

- [ ] **Step 3: Run** `python3 -I -m unittest tools/unlock/test_phonebox_unlock.py -v` from the repo root → FAIL (module missing)
- [ ] **Step 4: Implement** `phonebox_unlock.py`. Keep paths (`KEY_DIR`, `AGENT_PORT`) as module constants so tests can patch them.
- [ ] **Step 5: Run** → PASS
- [ ] **Step 6: Commit** `feat(unlock): sudo PAM helper`

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
  - `fun acceptable(p: Parts, expectedPurpose: String, nowMs: Long): Boolean` — purpose equals expected and `expiresAt > nowMs`
  - `fun promptTitle(p: Parts): String` — `sudo` → `"sudo — ${p.user}@${p.host}"`, `screen` → `"Unlock PC — ${p.user}@${p.host}"`
  - `fun promptDetail(p: Parts): String?` — `sudo` → `p.command`, `screen` → null
  - `fun checkCode(spki: ByteArray): String`

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun parsesAGoodChallenge()
@Test fun rejectsWrongShapes()                       // 6 fields, v2 prefix, purpose "ssh", short nonce → null
@Test fun screenButtonRefusesSudoChallenge()         // acceptable(sudoParts, "screen", now) == false
@Test fun expiredIsRefused()
@Test fun promptTextComesFromTheChallenge()          // promptTitle(sudo) == "sudo — peter@fedora", promptDetail == "sudo dnf up"
@Test fun checkCodeFormat()                          // matches ^[0-9A-F]{4}( [0-9A-F]{4}){4}$; equals the C# value for the same bytes (copy one fixed SPKI + code from a C# test run)
```

- [ ] **Step 2: Run** `cd android && ./gradlew testDebugUnitTest --tests '*UnlockChallengeTest'` → FAIL
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
    - `unlockSign(key, id: String, challenge: String, purpose: String)` — `parse` + `acceptable(purpose)` else report failure; `sign(...)`; POST `/api/unlock/answer {id, signature}` (sudo) or `/api/unlock/screen {challenge, signature}` (screen); then `window.pbUnlockDone(id, ok)` via `evaluateJavascript` on the UI thread
  - Network calls on a background thread (`AgentLink.request` blocks).

- [ ] **Step 1: Implement `UnlockKey` and the bridge methods.**
- [ ] **Step 2: Build** `cd android && ./gradlew assembleDebug` → BUILD SUCCESSFUL; `./gradlew testDebugUnitTest` → PASS
- [ ] **Step 3: Manual check** (install the debug APK; temporarily call `PhoneBox.unlockSetup(key)` from the WebView console via `chrome://inspect`): returns a 20-char code; the agent log prints the same code; `curl -s http://127.0.0.1:8765/api/unlock/key` shows the same `code`.
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
- `request` present → card like the approval card (buzz + wake like `renderApproval`), title/detail from the bridge's parse (no agent text), one button **Use fingerprint** → `unlockSign(key, id, challenge, "sudo")`; the card hides when `request` disappears.
- `locked && screen` → **Unlock PC** button over the panel → `unlockSign(key, "screen", screen, "screen")`.
- `pbUnlockDone(id, false)` → short "Not unlocked" text, no retry loop.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Run UI tests** `cd tools/ui-tests && npx playwright test` → no new failures.
- [ ] **Step 3: Manual check on the phone:** lock the PC (Super+L) → **Unlock PC** appears within ~2 s → fingerprint → GNOME unlocks. Cancel the prompt → stays locked, password works.
- [ ] **Step 4: Commit** `feat(unlock): panel card, unlock button and setup`

---

### Task 9: Install on this PC and end-to-end test

**Why:** Changing PAM is the one step that can lock you out, so you learn the safe way: backup, a root shell kept
open, test in a new terminal, undo ready.

**Files:**
- Create: `tools/unlock/README.md` — the install / test / undo steps below, so they live with the code.

- [ ] **Step 1:** Settings → PhoneBox self-update **off** (it would overwrite the fork's build). Install the agent and APK built from the branch.
- [ ] **Step 2:** Settings → tick **Fingerprint unlock** → on the phone **Set up fingerprint unlock** → note the code.
- [ ] **Step 3:** `sudo install -D -m 0755 tools/unlock/phonebox_unlock.py /usr/local/lib/phonebox/phonebox-unlock && sudo /usr/local/lib/phonebox/phonebox-unlock enroll` → the code matches → `y`. Check: `ls -l /etc/phonebox/` → `root root ... peterperveka.pub`.
- [ ] **Step 4:** Open a second terminal and run `sudo -i` there; **leave it open until Step 6 passes.**
- [ ] **Step 5:** `sudo cp /etc/pam.d/sudo /etc/pam.d/sudo.bak`, then in `/etc/pam.d/sudo` replace the line `auth sufficient pam_howdy.so no_confirmation` with
  `auth       sufficient   pam_exec.so quiet /usr/local/lib/phonebox/phonebox-unlock`
- [ ] **Step 6:** In a **new** terminal, each must behave as stated:
  - `sudo -k; sudo true` → prompt text appears, phone card shows `sudo true` → fingerprint → succeeds without a password
  - `sudo -k; sudo true` → press Enter → password prompt at once → correct password works
  - phone Wi-Fi off → `sudo -k; sudo true` → password prompt with no delay
  - `sudo -k; echo | sudo -S true` (no tty) → password path, no phone card
  - `ssh localhost sudo -k true` (if sshd runs) → no phone card
- [ ] **Step 7:** Undo if anything is wrong: `sudo cp /etc/pam.d/sudo.bak /etc/pam.d/sudo` (from the root shell).
- [ ] **Step 8: Commit** `docs(unlock): install, test and undo steps`
