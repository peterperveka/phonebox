# Sleep With the PC, One Touch Wakes Both: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Super+L puts the phone to sleep. One power-button press, with the finger left on it, unlocks the phone and the PC.

**Architecture:**
- **Phone.** The unlock key becomes a 5 s window key, opened by any strong biometric. Signing becomes "authenticate first (no `CryptoObject`), then sign within the window".
- **Wake path.** `UnlockListener` gains a quick try: when the panel becomes active within 5 s of a resume, it signs straight away without the sensor, and falls back to the sensor when that fails.
- **Agent.** "Follow the computer lock" reads the lock from logind on Linux.

**Tech Stack:** Kotlin + JUnit 4, C# (.NET 10) + xUnit, panel/settings JS + Playwright.

**Spec:** `docs/superpowers/specs/2026-11-25-sleep-with-pc-one-touch-design.md`

## Global Constraints

- **Key window:** `WINDOW_SECONDS = 5`, `AUTH_BIOMETRIC_STRONG` on Android 11+, and `setUserAuthenticationValidityDurationSeconds(5)` on 9–10. Keep `setInvalidatedByBiometricEnrollment(true)`.
- **Old keys:** a per-use key (validity ≤ 0) is reported as missing (`""` code), so the panel offers setup. It is not deleted, because the rotation deletes it after the new enroll succeeds.
- **sudo/polkit:** the dialog with the command still runs before every request signature.
- **Linux lock read:** `ILoginSession.GraphicalSessionId()` gives the session id, then `IsLocked(id)`. No session means `null` ("unavailable").
- **macOS:** behaviour unchanged.
- **Existing work:** no change to the touch-to-unlock behaviour from `2026-11-25-touch-to-unlock-screen-design.md`.

## Review Focus

1. **Fingerprint used for something else, then the PC locks.** Example: a sudo approved by fingerprint, then Super+L within 5 s. The PC must not unlock by itself. The quick try only runs within 5 s of a *resume*. Pinned by `noQuickTryWhenWantArrivesLongAfterResume` (Task 1).
2. **Phone woken by PIN, or by the agent at PC unlock.** There should be no "Not unlocked" and no loop: the quick try returns `null`, then the sensor starts. Pinned by `quickTryNullStartsTheSensorSilently` (Task 1).
3. **Phone slept before the panel learned of the lock** (want arrives after the resume). The quick try still runs. Pinned by `wantSoonAfterResumeTriesQuick` (Task 1).
4. **Old per-use key after the update.** Setup is offered, and sudo falls back to the password until re-enrolled. Pinned by `outdatedKeyCountsAsMissing` (Task 2) and the device check (Task 4).
5. **No graphical session on Linux** (SSH-only, or the agent started outside the session). Lock sync reports unavailable, and the phone is not slept. Pinned by `NoSessionIsUnavailable` (Task 3).

---

### Task 1: `UnlockListener` quick try after a resume

**Files:**
- Modify: `android/app/src/main/java/net/oxog/phonebox/deskpanel/UnlockListener.kt`
- Test: `android/app/src/test/java/net/oxog/phonebox/deskpanel/UnlockListenerTest.kt`

**Interfaces:**
- Produces: two new constructor parameters, placed after `later`:
  - `tryNow: () -> Boolean?` runs on `background`. `true` means unlocked, `false` means refused, `null` means not authenticated or nothing to unlock.
  - `now: () -> Long` gives milliseconds.
- `QUICK_MS = 5_000`.
- **Rules:**
  - `visible(true)` after `visible(false)` (or at the first call) records `resumedAt = now()`.
  - When `reconsider` finds the listener active, no session running, not busy, and `now() - resumedAt <= QUICK_MS` with the quick try not yet used for this resume:
    - mark it used and set `busy`;
    - run `background { tryNow() }`, then `main`;
    - on `true` report `"unlocked"`, on `false` report `"failed"`, on `null` report nothing;
    - finally clear `busy` and call `reconsider()`, which starts the sensor.
  - `sensor.start` is not called before the quick try finishes.

- [ ] **Step 1: Write failing tests.** Use a fake clock `var clock = 0L` and a counter for `tryNow` returning `var quick: Boolean? = null`. Existing tests keep passing with `tryNow = { null }` and the clock at 0. In those tests a quick try runs first and returns null, so update only `reports` / `starts` expectations if a test reads them before the sensor starts, and ledger the change.
  - `resumeTriesWithoutTheSensorFirst`: `quick=true`; want(true), visible(true) → `tryNow` called once; reports start with `"unlocked"`; then `starts == 1`.
  - `quickTryNullStartsTheSensorSilently`: `quick=null` → reports `["listening"]`, `starts == 1`.
  - `quickTryFalseReportsFailedThenListens`: `quick=false` → `["failed","listening"]`.
  - `wantSoonAfterResumeTriesQuick`: visible(true) at 0, want(true) at `clock=3000` → `tryNow` called once.
  - `noQuickTryWhenWantArrivesLongAfterResume`: visible(true) at 0, want(true) at `clock=6000` → `tryNow` never called; `starts == 1`.
  - `oneQuickTryPerResume`: a quick null, then visible(false), visible(true) at `clock=100` → 2 calls; a touch then a restart in the same resume → still 2.
- [ ] **Step 2: Run.** `cd android && JAVA_HOME=$HOME/.local/share/jdk-21 ./gradlew testDebugUnitTest --tests '*UnlockListenerTest*'`. Expected: compile FAIL (no `tryNow` / `now` parameter).
- [ ] **Step 3: Implement** per Rules. In `MainActivity`, pass `tryNow = { null }` and `now = { SystemClock.elapsedRealtime() }` for now, so the build stays green. Task 2 wires the real `tryNow`.
- [ ] **Step 4: Run** the same command plus `assembleDebug`. Expected: PASS, BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.** `feat(unlock): try the unlock without the sensor right after the panel resumes`

### Task 2: 5 s window key, sign after authentication

**Files:**
- Modify: `android/app/src/main/java/net/oxog/phonebox/deskpanel/UnlockKey.kt`, `UnlockKeyRotation.kt`, `MainActivity.kt`
- Test: `android/app/src/test/java/net/oxog/phonebox/deskpanel/UnlockKeyRotationTest.kt`

**Interfaces:**
- Consumes: `UnlockListener(… tryNow, now)` (Task 1).
- Produces:
  - `UnlockKeyRotation.State.OUTDATED`, with `code(OUTDATED, _) == ""`.
  - `UnlockKey.WINDOW_SECONDS = 5`, `create()` with the window.
  - `UnlockKey.signNow(data: ByteArray): ByteArray?`: `initSign` + `update` + `sign` with the active key, or null on `UserNotAuthenticatedException` or any failure.
  - `probe()`:
    - `UserNotAuthenticatedException` from `initSign` means OK.
    - A key whose `KeyInfo.userAuthenticationValidityDurationSeconds <= 0` is `OUTDATED`.
  - `prompt(title, detail, onDone: (Boolean) -> Unit)`: `BiometricPrompt` without `CryptoObject`, `BIOMETRIC_STRONG`.
  - `sign(challenge, title, detail, onDone: (String?) -> Unit)`: `prompt`, then `signNow`, then base64 `NO_WRAP`.
  - `fingerprintSensor(miss)`: an `UnlockListener.Sensor<Unit>`. It calls `authenticate(null, …)`; `available()` also requires the active key to exist.
  - `MainActivity.signScreenNow(): Boolean?` (no parameter) uses `signNow`:
    - no challenge → null;
    - `signNow` null → null;
    - POST → `code == 200`.
  - Listener wiring: `unlock = { signScreenNow() }`, `tryNow = { signScreenNow() }`.
  - The button path is `prompt` → `signScreenNow()`. For the panel, `null` counts as not unlocked (`false`), as today.

- [ ] **Step 1: Write a failing test.** `outdatedKeyCountsAsMissing`: `assertEquals("", UnlockKeyRotation.code(State.OUTDATED, byteArrayOf(1)))`.
- [ ] **Step 2: Run.** `./gradlew testDebugUnitTest --tests '*UnlockKeyRotationTest*'`. Expected: compile FAIL (`OUTDATED` unresolved).
- [ ] **Step 3: Implement** per Interfaces. Remove `signer()` once nothing uses it.
- [ ] **Step 4: Run** `./gradlew assembleDebug testDebugUnitTest`. Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 5: Commit.** `feat(unlock): a 5 s window key opened by any fingerprint, sign after authenticating`

### Task 3: Follow the computer lock on Linux

**Files:**
- Create: `agent/PhoneBox.Agent/ComputerLock.cs`
- Modify:
  - `agent/PhoneBox.Agent/AdbReverse.cs` (constructor, `ScreenSync` ~l.450, ~l.535, ~l.842)
  - `agent/PhoneBox.Agent/SessionLockMonitor.cs`
  - `agent/PhoneBox.Agent/settings.html` (~l.4031)
  - the `s.kiosk.followLock` text in `android/app/src/main/assets/www/i18n.js` and each `locale-*.js` that has it
- Test: `agent/PhoneBox.Agent.Tests/ComputerLockTests.cs`, `tools/ui-tests/bridge-settings.spec.js` (or the settings spec that already sets `d.os`)

**Interfaces:**
- Produces:
  - `internal static class ComputerLock`
    - `public static bool Supported => OperatingSystem.IsMacOS() || OperatingSystem.IsLinux();`
    - `public static bool? Read(ILoginSession? login)`: macOS → `MacSessionLock.IsLocked()`; Linux → `login?.GraphicalSessionId() is { } id ? login.IsLocked(id) : null`; anything else → `null`.
  - `AdbReverse` gets the optional constructor parameter `ILoginSession? login = null`. `ScreenSync` uses `() => ComputerLock.Read(login)`.
  - The two `FollowComputerLock && OperatingSystem.IsMacOS()` checks in `AdbReverse` and the one in `SessionLockMonitor` use `ComputerLock.Supported`.
  - `SessionLockMonitor` warning text: `"Lock sync is unavailable; start the agent in the desktop session"`.
  - `settings.html`: `followComputerLockOption` shows when `osName === 'macos' || osName === 'linux'`.
  - English copy for `s.kiosk.followLock`: `Follow the computer lock: turn the phone screen off when the computer locks; wake it and open PhoneBox when it unlocks (ADB required; a phone secured by a PIN or password unlocks with its fingerprint or manually)`. Every other locale says the same with "the computer" in place of "macOS".

- [ ] **Step 1: Write failing tests.** `ComputerLockTests`, with a fake `ILoginSession`, runs only on Linux (`if (!OperatingSystem.IsLinux()) return;`):
  - `LockedSessionIsLocked`: id `"7"`, locked → `true`.
  - `UnlockedSessionIsUnlocked`: → `false`.
  - `NoSessionIsUnavailable`: `GraphicalSessionId()` returns null → `null`.
  - `NoLoginServiceIsUnavailable`: `Read(null)` → `null`.

  Add a UI test, `the follow-the-lock option shows on Linux`: with `os: 'linux'`, `#followComputerLockOption` is visible. With `os: 'windows'` it is hidden.
- [ ] **Step 2: Run.** `dotnet test agent/PhoneBox.Agent.Tests --filter ComputerLockTests` and `cd tools/ui-tests && npx playwright test bridge-settings.spec.js -g "follow-the-lock"`. Expected: compile FAIL (`ComputerLock` missing), and the UI test FAILs (hidden on Linux).
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `dotnet test agent/PhoneBox.Agent.Tests` (whole suite) and the UI test. Expected: all pass.
- [ ] **Step 5: Commit.** `feat(agent): follow the computer lock on Linux (logind), phone sleeps with the PC`

### Task 4: README, install, re-enroll and check on the phone

**Files:**
- Modify: `tools/unlock/README.md`

- [ ] **Step 1: README.** In the screen-unlock section, add one paragraph:
  - turn on **Follow the computer lock**;
  - Super+L sleeps the phone;
  - press the power button and leave the finger on it, and the phone and the PC unlock;
  - a PIN unlock needs a touch on the sensor afterwards.

  After "After every new setup", add: "Updating to the 5 s window key needs one new setup on the phone and `enroll`." Add one Slovak summary line.
- [ ] **Step 2: Install.** Run `./scripts/install.sh`, then `assembleDebug` and `adb install -r`.
- [ ] **Step 3: With the user.**
  - On the phone: setup → code.
  - On the PC: `sudo /usr/local/lib/phonebox/phonebox-unlock enroll` and compare the codes.
  - Settings: tick **Follow the computer lock**.
- [ ] **Step 4: Check with the user.**
  - Super+L → the phone sleeps. One press with the finger left on it → the PC unlocks.
  - Wake the phone by PIN → the hint shows, then a touch unlocks.
  - Phone awake: lock the PC, then touch → it unlocks (the touch-to-unlock path still works).
  - sudo: the dialog shows the command and the fingerprint passes.

  Expected in `agent.log`: `Screen unlocked from the phone: True`, plus `phone screen asleep` / `awake`.
- [ ] **Step 5: Commit.** `docs(unlock): sleep with the PC, one touch wakes both`
