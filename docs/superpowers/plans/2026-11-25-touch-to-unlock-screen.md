# Touch to Unlock the PC Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While the PC is locked, a touch on the phone's fingerprint sensor unlocks it, with no tap and no Android dialog.

**Architecture:** A pure Kotlin `UnlockListener<S>` state machine decides when the sensor is listened to (panel wants it ∧ activity visible ∧ not stopped) and restarts it after each touch. An Android `FingerprintSensor` drives the deprecated `FingerprintManager` with a fresh Keystore `Signature` per start. After a touch, the app fetches the *current* screen challenge from the agent, signs it and posts it. The panel only says "listen / don't" and shows a hint or the fallback button.

**Tech Stack:** Kotlin (Android 9+ paths, JUnit 4 unit tests), panel JS (ES modules, Playwright UI tests), no agent changes.

**Spec:** `docs/superpowers/specs/2026-11-25-touch-to-unlock-screen-design.md`

## Global Constraints

- Screen unlock only. sudo/polkit keep `BiometricPrompt` (`UnlockKey.sign` behaviour for `MODE_REQUEST` unchanged).
- No agent changes. Endpoints used: `GET /api/unlock/state` (`{enabled, enrolled, locked, screen, request}`), `POST /api/unlock/screen` (`{challenge, signature}`, 200 = unlocked).
- Same key: `UnlockKey`'s Keystore key, `SHA256withECDSA`, signature base64 `NO_WRAP` of DER.
- A challenge is signed only if `UnlockChallenge.parse` succeeds and `acceptable(p, MODE_SCREEN, now)`.
- Every bridge method takes the bridge key first and is added to the `BRIDGE` list in `main.js`.
- New copy: `unlock.touch` = `Touch the sensor to unlock the PC` (English); added to all 14 locales in `i18n.js` and all 12 `locale-*.js` files.
- Before Task 1: discard the spike (`git checkout -- android/ agent/`) so only committed code remains.

## Review Focus

1. **Locked for minutes before the touch** → still unlocks (challenge fetched after the touch; expired ones rejected). Pinned: `pickScreen` tests (Task 1), device check (Task 4).
2. **Phone screen off / panel paused while the PC is locked** → sensor released; listening resumes on resume without the panel doing anything. Pinned: `UnlockListenerTest.invisibleCancelsAndVisibleRestarts` (Task 1).
3. **5 bad fingers (lockout) or sensor error** → stopped, Unlock PC button returns; the next lock tries listening again. Pinned: `errorStopsUntilWantedAgain` (Task 1), `stopped brings the button back` (Task 3).
4. **Touch just after the PC was unlocked by password** (no screen challenge) → nothing sent, no "Not unlocked". Pinned: `nothingToUnlockReportsNothing` (Task 1).
5. **The panel re-renders often and repeats "listen"** → one sensor session, not many; no restart after "don't listen" arrives during an in-flight unlock. Pinned: `repeatedWantStartsOnce`, `wantOffDuringUnlockDoesNotRestart` (Task 1).

---

### Task 1: `UnlockListener` state machine and `pickScreen`

**Files:**
- Create: `android/app/src/main/java/net/oxog/phonebox/deskpanel/UnlockListener.kt`
- Modify: `android/app/src/main/java/net/oxog/phonebox/deskpanel/UnlockChallenge.kt` (add `pickScreen`)
- Test: `android/app/src/test/java/net/oxog/phonebox/deskpanel/UnlockListenerTest.kt`, `.../UnlockChallengeTest.kt`

**Interfaces:**
- Produces:
  - `fun UnlockChallenge.pickScreen(screen: String?, nowMs: Long): String?`: `screen` if it parses and is acceptable in `MODE_SCREEN`, else null.
  - ```kotlin
    class UnlockListener<S>(
        private val sensor: Sensor<S>,
        private val unlock: (S) -> Boolean?,      // runs on background; true unlocked, false refused, null nothing to unlock
        private val report: (String) -> Unit,     // "listening" | "unlocked" | "failed" | "stopped"
        private val background: (() -> Unit) -> Unit,
        private val main: (() -> Unit) -> Unit,
    ) {
        interface Sensor<S> {
            fun available(): Boolean
            /** Starts one session; returns a cancel function, or null if it could not start. */
            fun start(onTouch: (S) -> Unit, onMiss: () -> Unit, onError: () -> Unit): (() -> Unit)?
        }
        fun want(on: Boolean)       // from the panel; on=false also clears "stopped"
        fun visible(on: Boolean)    // resumed && screen on
    }
    ```
  - Rules: listens iff `wanted && visible && !stopped && sensor.available()`; idempotent; `"listening"` reported on each start; touch → session ends → `background { unlock }` → `main { report; reconsider }`; `null` result reports nothing; `onMiss` keeps the session (the Android side buzzes); `onError` or `start` returning null → `stopped = true`, report `"stopped"`. Not thread-safe: all public calls and callbacks on `main`.

- [ ] **Step 1: Discard the spike**: `git checkout -- android/ agent/`; `git status --short` shows nothing under `android/` or `agent/`.
- [ ] **Step 2: Write failing tests.** `UnlockChallengeTest`: `pickScreenAcceptsAFreshScreenChallenge` (`challenge("screen","-")` at `now` → same string), `pickScreenRejectsExpiredOtherPurposeAndNull` (expiresAt ≤ now → null; `"sudo"` → null; `null`/`""` → null). `UnlockListenerTest` with a fake sensor (counts starts/cancels, exposes the last callbacks) and direct `background`/`main`:
  - `listensOnlyWhenWantedAndVisible`: want(true) alone → 0 starts; + visible(true) → 1 start, reports `["listening"]`.
  - `repeatedWantStartsOnce`: want(true)×3, visible(true)×2 → 1 start.
  - `touchUnlocksAndListensAgain`: unlock returns true → reports `listening, unlocked, listening`; 2 starts.
  - `refusedReportsFailedAndListensAgain`: false → `…, failed, listening`.
  - `nothingToUnlockReportsNothing`: null → `listening, listening`.
  - `missKeepsTheSession`: onMiss → 1 start, 0 cancels.
  - `invisibleCancelsAndVisibleRestarts`: visible(false) → 1 cancel; visible(true) → 2 starts.
  - `errorStopsUntilWantedAgain`: onError → `stopped`; visible toggles start nothing; want(false), want(true) → starts again.
  - `unavailableSensorNeverStarts`: available()=false → 0 starts, reports `["stopped"]` on first want∧visible.
  - `wantOffDuringUnlockDoesNotRestart`: with `background` deferred, touch, want(false), run deferred → no second start.
- [ ] **Step 3: Run**: `cd android && JAVA_HOME=$HOME/.local/share/jdk-21 ./gradlew testDebugUnitTest --tests '*UnlockListenerTest*' --tests '*UnlockChallengeTest*'`. Expected: compilation FAIL (unresolved `UnlockListener`, `pickScreen`).
- [ ] **Step 4: Implement** `UnlockListener` and `pickScreen` per the Interfaces block.
- [ ] **Step 5: Run** the same command. Expected: PASS, and the existing tests in both files still pass.
- [ ] **Step 6: Commit**: `feat(unlock): listener state machine for touch-to-unlock, pick the current screen challenge`.

### Task 2: Android wiring: sensor, fresh-challenge signing, bridge, lifecycle

**Files:**
- Modify: `android/app/src/main/java/net/oxog/phonebox/deskpanel/UnlockKey.kt`
- Modify: `android/app/src/main/java/net/oxog/phonebox/deskpanel/MainActivity.kt` (bridge near `unlockSign` ~l.925; `onResume`/`onPause`/`onDestroy`; the `ACTION_SCREEN_ON/OFF` receiver ~l.226)

**Interfaces:**
- Consumes: `UnlockListener<Signature>`, `UnlockChallenge.pickScreen` (Task 1).
- Produces (used by Task 3):
  - Bridge `@JavascriptInterface fun unlockListen(key: String, on: Boolean)` → `listener.want(on)` on the UI thread. No-op below API 28.
  - Panel callback `window.pbUnlockListen(state)` with the four `report` strings.
  - `UnlockKey.signer(): Signature?` becomes `internal` (fresh, un-authenticated, per start).
  - `UnlockKey.prompt(title: String, detail: String?, onDone: (Signature?) -> Unit)`: the existing `BiometricPrompt` flow, returning the authenticated `Signature` instead of signing. `sign(...)` keeps its signature and is reimplemented on top of `prompt`.
  - `UnlockKey.fingerprintSensor(): UnlockListener.Sensor<Signature>`: `available()` = API ≥ 28 ∧ `FingerprintManager` non-null ∧ `isHardwareDetected` ∧ `hasEnrolledFingerprints()`; `start` gets `signer()` (null → return null), calls `authenticate(CryptoObject(sig), CancellationSignal(), 0, cb, mainHandler)` and returns `{ signal.cancel() }`. `onAuthenticationSucceeded` → `onTouch(result.cryptoObject.signature)`, `onAuthenticationFailed` → `onMiss`, `onAuthenticationError` → `onError` unless the code is `FINGERPRINT_ERROR_CANCELED` (5), caused by our own cancel. `onAuthenticationHelp` is ignored.
  - `MainActivity.signScreenNow(sig: Signature): Boolean?` (blocking, off the UI thread): GET `/api/unlock/state`, read `screen`, `pickScreen(screen, now)` → null ⇒ return null; `sig.update(utf8)`, sign, POST `/api/unlock/screen` `{challenge, signature}`, return `code == 200`. Exceptions → false.

- [ ] **Step 1: Wire** the listener in `MainActivity` (`background` = new thread, `main` = `runOnUiThread`, `report` = `evaluateJavascript("window.pbUnlockListen && window.pbUnlockListen(<quoted>)")` when not destroyed). `onMiss` calls the existing haptic path for a short buzz. `visible(true)` in `onResume` and on `ACTION_SCREEN_ON`; `visible(false)` in `onPause`, on `ACTION_SCREEN_OFF` and in `onDestroy`.
- [ ] **Step 2: The Unlock PC button fix**: in `unlockSign`, `MODE_SCREEN` now calls `prompt(...)`, then on a background thread `signScreenNow(sig)`. `pbUnlockDone("screen", result == true)` and `unlockBusy` handled as today. `MODE_REQUEST` unchanged.
- [ ] **Step 3: Build**: `cd android && JAVA_HOME=$HOME/.local/share/jdk-21 ./gradlew assembleDebug testDebugUnitTest`. Expected: BUILD SUCCESSFUL, all unit tests pass.
- [ ] **Step 4: Commit**: `feat(unlock): listen for a touch while the PC is locked and sign the current challenge`.

### Task 3: Panel: ask to listen, hint, fallback, copy

**Files:**
- Modify: `android/app/src/main/assets/www/main.js` (`BRIDGE` list l.67, `renderUnlock` ~l.1463, new `window.pbUnlockListen`)
- Modify: `android/app/src/main/assets/www/index.html:67-70` (add `<span id="ukTouch" role="status" hidden data-i18n="unlock.touch">Touch the sensor to unlock the PC</span>` inside `#unlockBar`)
- Modify: `android/app/src/main/assets/www/i18n.js` (14 locales), `android/app/src/main/assets/www/locale-*.js` (12 files): `unlock.touch`
- Test: `tools/ui-tests/unlock.spec.js`

**Interfaces:**
- Consumes: `bridge.unlockListen(on)`, `window.pbUnlockListen(state)` (Task 2).
- Rules: `want = !!(s.locked && s.screen && !r && typeof bridge.unlockListen === 'function')`. Call `bridge.unlockListen(want)` only when `want` changes (and once `false` when the state goes away). `listen` state: `'listening'` → `#ukTouch` shown, `#ukScreen` hidden; `'stopped'` → `#ukScreen` shown (the dialog path) until `want` turns false; `'unlocked'` → `loadUnlock()`; `'failed'` → `unlockMessage(t('unlock.failed'), 4000)`, hint stays. With no `unlockListen` (an older app) the bar behaves exactly as today.

- [ ] **Step 1: Write failing tests** in `unlock.spec.js`. Extend the `Android` stub with `unlockListen: (key, on) => window.__listens.push(on)` behind a `listen = true` option. Tests:
  - `while the PC is locked the app is asked to listen and the touch hint shows`: state `{enabled, enrolled, locked:true, screen:'c', request:null}` → `__listens` = `[true]`; `#ukTouch` has text `Touch the sensor to unlock the PC` after `pbUnlockListen('listening')`; `#ukScreen` hidden.
  - `stopped brings the Unlock PC button back`: then `pbUnlockListen('stopped')` → `#ukScreen` visible, `#ukTouch` hidden.
  - `unlocking the PC stops listening`: then `__state.locked=false, screen=null`, `loadUnlock()` → last of `__listens` is `false`, length 2.
  - `a sudo card stops listening`: then `__state.request = {id:'r', challenge:'c'}`, `loadUnlock()` → last is `false`.
  - `failed shows Not unlocked and keeps listening`: `pbUnlockListen('failed')` → `#ukMsg` = `Not unlocked`; `__listens` still `[true]`.
  - `an app without unlockListen keeps the Unlock PC button`: `listen:false` → `#ukScreen` visible, `#ukTouch` hidden.
- [ ] **Step 2: Run**: `cd tools/ui-tests && npx playwright test unlock.spec.js`. Expected: the new tests FAIL; the existing ones pass.
- [ ] **Step 3: Implement** per Rules; add `unlock.touch` everywhere (English text above; translate it for the other locales like the neighbouring `unlock.*` keys).
- [ ] **Step 4: Run**: `npx playwright test unlock.spec.js`, then the whole suite `npx playwright test`. Expected: all pass.
- [ ] **Step 5: Commit**: `feat(unlock): panel asks the app to listen while the PC is locked, touch hint and fallback`.

### Task 4: README, install and check on the phone

**Files:**
- Modify: `tools/unlock/README.md` (section "Screen unlock (GNOME lock screen)")

- [ ] **Step 1: README**: replace the screen-unlock paragraph with: lock with Super+L; within about 2 s the phone shows "Touch the sensor to unlock the PC"; touch the sensor (don't press it); the PC unlocks. A wrong finger keeps listening; after 5 wrong fingers Android stops the sensor for a while and the **Unlock PC** button with the dialog returns. The Slovak summary at the top gets one line on this.
- [ ] **Step 2: Install**: `./scripts/install.sh` (clean agent, no diagnostics), `cd android && JAVA_HOME=$HOME/.local/share/jdk-21 ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **Step 3: Check on the phone** (with the user): three unlocks in a row; one after waiting more than 60 s locked; one wrong finger then the right one; phone screen off then on while locked, then touch; unlock by password, then touch (nothing happens, no message). Expected `agent.log`: `Screen unlocked from the phone: True` for each touch unlock.
- [ ] **Step 4: Commit**: `docs(unlock): touch to unlock the screen`.
