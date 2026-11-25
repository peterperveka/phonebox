# The phone puts itself to sleep when the PC locks

Follow-up to [2026-11-25-sleep-with-pc-one-touch-design.md](2026-11-25-sleep-with-pc-one-touch-design.md).

## Intent

After one press of Super+L the phone must reliably go to sleep shortly after, over Wi-Fi as well as USB. The user
then wakes it with the power button, finger left on it, which unlocks the phone and the PC (already built).

## Why not the agent

The agent sleeps phones with `adb shell input keyevent KEYCODE_SLEEP`. This Xiaomi refuses injected keys
(`SecurityException … INJECT_EVENTS`) unless "USB debugging (Security settings)" is on, and that setting needs a SIM,
which the phone does not have. Over Wi-Fi there is no ADB at all. `am start` over USB does work.

## Goals

- When "Follow the computer lock" is on and the PC locks, the panel turns the phone screen off and locks it within
  about 2 s, with nothing but the app's own connection to the agent (USB or Wi-Fi).
- The phone's fingerprint keeps working on the next wake (one-touch unlock stays).
- Over USB, unlocking the PC still brings the phone up (the agent's `am start`).

## Non-goals

- Sleeping the phone when the PC is unreachable (the user does not need it now).
- Waking the phone over Wi-Fi when the PC unlocks: the user wakes it with the button.

## Approaches

1. **An accessibility service in PhoneBox** (chosen). `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` (Android 9+)
   behaves like the power button: screen off, keyguard locked, fingerprint allowed on the next unlock. The user
   enables it once in Settings › Accessibility (Android 13+ may first need "Allow restricted settings" in the app
   info, since the app is sideloaded).
2. The phone's own screen timeout (the panel stops holding the screen on). No permission, but not immediate, and it
   never happens with "Stay awake while charging" on. Rejected: the user wants certainty.
3. Device administrator `lockNow()`. Rejected: Android then requires the PIN for the next unlock, which kills the
   one-touch fingerprint unlock.

## Design

- **The service:** `PanelSleepService : AccessibilityService`. It listens to no events (empty event types, no
  window content). It only exposes "lock the screen now" while it is connected, and it is registered in the manifest
  with `BIND_ACCESSIBILITY_SERVICE` and a short description of what it does. Bridge calls:
  - `canSleep()`: whether the service is connected;
  - `sleepPhone()`: `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`, returning false without the service;
  - `openAccessibilitySettings()`.
- **When it sleeps:** the panel already learns the lock from `/api/unlock/state` (`locked`, through the agent's push
  events and the 5 s poll). When `cfg.display.followComputerLock` is on and `locked` turns from false to true, the
  panel calls `sleepPhone()` once. It does not repeat while the PC stays locked, so a phone woken by PIN stays awake
  (the panel then shows the touch hint as today). The agent's own `KEYCODE_SLEEP` stays: it still works on other
  phones, and a double sleep is harmless.
- **Missing permission:** while follow-lock is on and `canSleep()` is false, the unlock bar shows one line, "Let the
  phone sleep with the PC", with a button that opens the Accessibility settings. It disappears once the service is
  on.
- **Wake over USB:** when the agent's `am start` reaches a sleeping phone, the panel turns the screen on (a short
  `ACQUIRE_CAUSES_WAKEUP` wake lock on a new intent from the agent, or `setTurnScreenOn`). This needs a check on the
  device. The keyguard then asks for the fingerprint as usual.

## Left to the builder

- The exact condition source (the unlock state carries `locked` only when the unlock feature is on and enrolled; that
  is the case here, so the design uses it, and documents that follow-lock sleep on the panel needs the unlock feature).
- Copy for the new strings (`sleep.enable`, the accessibility service description) in all locales, like the other
  unlock strings.
- Tests: Playwright for "locks once per lock, only with follow-lock on, hint and button without the service";
  Kotlin has no pure logic here beyond the bridge.
