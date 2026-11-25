# The Phone Puts Itself to Sleep: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After Super+L the phone screen goes off by itself within about 2 s, over Wi-Fi or USB. The sensor also comes back after Android's 30 s fingerprint lockout.

**Architecture:**
- **The service:** a no-events `AccessibilityService` that only exposes "lock the screen now" (`GLOBAL_ACTION_LOCK_SCREEN`), reached through the panel bridge.
- **The panel:** calls it once each time the unlock state's `locked` turns from false to true, while `display.followComputerLock` is on.
- **Waking:** a new intent from the agent's `am start` wakes a dark screen with a short wake lock.
- **The listener:** `UnlockListener` treats a temporary lockout as "stopped, retry in 30 s".

**Tech Stack:** Kotlin + JUnit 4, panel JS + Playwright.

**Spec:** `docs/superpowers/specs/2026-11-25-phone-sleeps-itself-design.md`. The lockout retry is an addition the implementer made after a device log (error 7 at 21:07:05) and has told the user about.

## Global Constraints

- **The service** is called `PanelSleepService`. It has `accessibilityEventTypes=""`, no window content and no gestures, and is guarded by `android.permission.BIND_ACCESSIBILITY_SERVICE`. `GLOBAL_ACTION_LOCK_SCREEN` needs API 28+.
- **Bridge methods** each take the bridge key first, and their names are added to `BRIDGE` in `main.js`:
  - `canSleep(): Boolean`
  - `sleepPhone(): Boolean`
  - `openAccessibilitySettings()`
- **Copy (English):**
  - `sleep.enable` = `Let the phone sleep with the PC`
  - `sleep.open` = `Turn on`
  - service description = `Turns the phone screen off when the PC locks. PhoneBox reads nothing on the screen.`
  - Add both keys to the 14 `i18n.js` locales and to the 9 `locale-*.js` files that carry `unlock.*` keys.
- **Lockout retry:** `FINGERPRINT_ERROR_LOCKOUT` (7) means stop, then retry after `LOCKOUT_RETRY_MS = 30_000`. `LOCKOUT_PERMANENT` (9) and anything else final means stop until the next `want(true)`.

## Review Focus

1. **Phone woken by PIN while the PC stays locked:** the phone must not be put back to sleep. Only a false→true edge sleeps. Pinned by `a phone woken while the PC stays locked is not slept again` (Task 3).
2. **Follow-lock off:** the phone never sleeps from the panel. Pinned by `follow-lock off never sleeps the phone` (Task 3).
3. **Service missing or disabled:** the hint and the button show, and nothing crashes. Pinned by `without the service the bar offers to turn it on` (Task 3).
4. **Lockout during a lock:** the sensor comes back after 30 s without a PC re-lock. A `want(false)` in between cancels the retry. Pinned by `lockoutRetriesAfterThirtySeconds` and `lockoutRetryCancelledByWantOff` (Task 1).
5. **The first state after a panel reload already says locked:** no sleep, because there was no edge seen. Otherwise a panel reload while the PC is locked would sleep the phone. Pinned by `a fresh panel on an already locked PC does not sleep the phone` (Task 3).

---

### Task 1: Lockout retries after 30 s

**Files:** Modify `android/app/src/main/java/net/oxog/phonebox/deskpanel/UnlockListener.kt`, `UnlockKey.kt`, `MainActivity.kt`. Test: `android/app/src/test/java/net/oxog/phonebox/deskpanel/UnlockListenerTest.kt`.

**Interfaces:**
- `Sensor.start(..., onError: (retryMs: Long?) -> Unit)` changes meaning:
  - `0L`: transient, keeps today's 1 s retry and its 3-in-a-row limit.
  - `> 0`: stop, report `"stopped"`, and retry after that many milliseconds.
  - `null`: final.
- `later: (delayMs: Long, block: () -> Unit) -> Unit` replaces the one-argument `later`.
- In `UnlockKey.fingerprintSensor`:
  - 5 or 1 → `0L`
  - 7 → `UnlockListener.LOCKOUT_RETRY_MS`
  - else → `null`
- A retry after a lockout clears `stopped` only while no `want(false)` came in between. Use a generation counter.

- [ ] **Step 1: Write failing tests.** First adapt the fake sensor and the `later` capture to the new signatures:
  - existing transient tests call `onError(0L)`;
  - existing final ones call `onError(null)`.

  Then add:
  - `lockoutRetriesAfterThirtySeconds`: `onError(30_000)` → reports `stopped`; the captured delay is 30 000; run it → `starts == 2`, reports end with `listening`.
  - `lockoutRetryCancelledByWantOff`: `onError(30_000)`, then `want(false)`, then run the delayed block → `starts == 1`.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*UnlockListenerTest*'` → compile FAIL.
- [ ] **Step 3: Implement**, and wire `later = { ms, r -> standbyHandler.postDelayed(r, ms) }`.
- [ ] **Step 4: Run** `./gradlew assembleDebug testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit**: `fix(unlock): the sensor comes back after Android's 30 s fingerprint lockout`

### Task 2: Accessibility service, bridge, wake on the agent's intent

**Files:**
- Create: `android/app/src/main/java/net/oxog/phonebox/deskpanel/PanelSleepService.kt` and `android/app/src/main/res/xml/panel_sleep_service.xml`.
- Modify: `AndroidManifest.xml`, `MainActivity.kt` (Bridge, `onNewIntent` l.511), `android/app/src/main/res/values/strings.xml` for the description.

**Interfaces:**
- `PanelSleepService`:
  - a `companion object` with `@Volatile var connected: PanelSleepService?`, set in `onServiceConnected` and cleared in `onUnbind` and `onDestroy`;
  - `fun lockNow(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`.
- Bridge:
  - `canSleep(key)`: `PanelSleepService.connected != null`, on API 28+ only.
  - `sleepPhone(key)` runs on the UI thread and returns the result of `lockNow()`.
  - `openAccessibilitySettings(key)` starts `Settings.ACTION_ACCESSIBILITY_SETTINGS` with `FLAG_ACTIVITY_NEW_TASK`.
- `onNewIntent`: when the display is off (`PowerManager.isInteractive == false`), take a `SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP` wake lock for 3 s. Add the `android.permission.WAKE_LOCK` manifest entry.

- [ ] **Step 1: Implement** (Android glue, no JVM test seam; the device check in Task 4 covers it).
- [ ] **Step 2: Build** with `./gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL. Confirm the merged manifest has the service with the permission: `grep -c PanelSleepService app/build/intermediates/merged_manifests/debug/*/AndroidManifest.xml` → ≥ 1.
- [ ] **Step 3: Commit**: `feat(panel): an accessibility service that turns the screen off when the PC locks`

### Task 3: The panel sleeps the phone on the lock edge

**Files:** Modify `android/app/src/main/assets/www/main.js` (`renderUnlock` / `loadUnlock`), `index.html` (unlock bar), `i18n.js` and `locale-*.js`. Test: `tools/ui-tests/unlock.spec.js`.

**Interfaces:**
- **Remembering the last lock state:** `lastLocked` starts as `null` and is set from every unlock state the panel receives.
- **Sleeping:** call `bridge.sleepPhone()` only when all of these hold:
  - `lastLocked === false`
  - the new state has `locked === true`
  - `cfg.display?.followComputerLock`
  - `typeof bridge.sleepPhone === 'function'`

  A `null` → `true` change (a fresh panel) never sleeps.
- **The bar line:** `<span id="ukSleep">` with the text `sleep.enable` and a `<button id="ukSleepOn">` with the text `sleep.open`. It shows while all of these hold:
  - follow-lock is on
  - the unlock state is enabled
  - `bridge.canSleep` exists and returns false

  The button calls `openAccessibilitySettings()`.

- [ ] **Step 1: Write failing tests.** Extend the stub:
  - `canSleep: () => window.__canSleep`
  - `sleepPhone: () => { window.__sleeps++; return true; }`
  - `openAccessibilitySettings: () => { window.__a11y++; }`
  - Set `cfg.display.followComputerLock` through the panel's stored cfg, the way other specs do.

  Tests:
  - `the phone sleeps once when the PC locks`: state unlocked, then locked → `__sleeps === 1`. A second locked poll → still 1.
  - `a phone woken while the PC stays locked is not slept again`: unlocked → locked → locked → locked → `__sleeps === 1`.
  - `a fresh panel on an already locked PC does not sleep the phone`: first state locked → `__sleeps === 0`.
  - `follow-lock off never sleeps the phone`: unlocked → locked with follow-lock off → 0.
  - `without the service the bar offers to turn it on`: `__canSleep = false` → `#ukSleep` visible with text `Let the phone sleep with the PC`; clicking `#ukSleepOn` → `__a11y === 1`. With `__canSleep = true` → hidden.
- [ ] **Step 2: Run** `npx playwright test unlock.spec.js` → the new tests FAIL.
- [ ] **Step 3: Implement** the panel changes and the copy.
- [ ] **Step 4: Run** `npx playwright test unlock.spec.js`, then the full suite → pass. Known unrelated flakes are listed in the earlier ledger.
- [ ] **Step 5: Commit**: `feat(panel): sleep the phone when the PC locks (follow the computer lock)`

### Task 4: README, install, check on the phone

- [ ] **Step 1: README.** In the "Sleep with the PC" paragraph:
  - on the phone, turn on Settings › Accessibility › PhoneBox; the panel offers it, and Android 13+ may first need App info › ⋮ › Allow restricted settings;
  - this works over Wi-Fi too;
  - over Wi-Fi, wake the phone with the button.
- [ ] **Step 2: Install** with `./scripts/install.sh`, then `assembleDebug` and `adb install -r`.
- [ ] **Step 3: Check with the user:**
  - turn the service on;
  - Super+L puts the phone to sleep within about 2 s, both on USB and on Wi-Fi;
  - one press with the finger left on the button unlocks the phone and the PC;
  - a PIN wake leaves the phone awake;
  - over USB, unlocking the PC by password wakes the phone.
- [ ] **Step 4: Commit**: `docs(unlock): the phone sleeps itself`
