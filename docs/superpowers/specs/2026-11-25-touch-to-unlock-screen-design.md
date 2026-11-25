# Touch to unlock the PC screen (no fingerprint dialog)

Follow-up to [2026-11-23-phone-fingerprint-unlock-design.md](2026-11-23-phone-fingerprint-unlock-design.md).

## Intent

While the PC is locked, the user puts a finger on the phone's sensor and the PC unlocks: no tap on **Unlock PC**, no
Android fingerprint dialog. The phone is used only as the PhoneBox panel, so the panel may own the sensor while the PC
is locked.

## Goals

- Screen unlock by touching the sensor, nothing else.
- Works however long the PC has been locked (screen challenges live 60 s and are single-use).
- The same Keystore key and the same agent check as today: only an enrolled finger can sign.

## Non-goals

- sudo and polkit keep the system dialog (`BiometricPrompt`): it is the only place that shows *what* is approved.
- No agent changes.

## Anti-goals

- A touch that unlocks without the fingerprint-bound key (e.g. a plain tap-to-unlock).
- The panel holding the sensor while the PC is unlocked, the panel is not in front, or the phone screen is off.

## What the spike showed (2026-11-25, Xiaomi 2109119DG, Android 14, side sensor)

- The deprecated `FingerprintManager.authenticate(CryptoObject(signature), …)` works from the foreground panel with
  no system UI; the Keystore key (`AUTH_BIOMETRIC_STRONG`, per use) signs after it. Wrong fingers report
  `onAuthenticationFailed` and listening continues.
- Signing the challenge fetched *before* the touch fails (agent log `same=False`): the challenge had been used by an
  earlier attempt or had expired while waiting. Fetching `/api/unlock/state` *after* the touch and signing that
  challenge worked three times in a row.

## Approaches

1. **Listen with `FingerprintManager`, fetch the challenge after the touch** (chosen). No dialog; the existing key
   and agent check stay; needs only the deprecated but present API.
2. Keep `BiometricPrompt`, open it automatically when the PC locks. Still shows the dialog the user wants gone.
3. A key usable for N seconds after any phone authentication, unlocked by a tap. Not a finger touch, and weaker.

## Design

- **When the panel listens:** the agent state says enabled, enrolled, locked, with a screen challenge; the panel is
  resumed; the phone screen is on; Android 9+ and `FingerprintManager` has hardware and an enrolled finger. It stops
  when any of these ends (PC unlocked, `onPause`, screen off) and on activity destroy.
- **Ownership:** native code owns the listening session. The panel calls `unlockListen()` / `unlockStopListening()`
  (bridge key as for every call); repeat calls are harmless. Native reports through `window.pbUnlockListen(state)`
  with `"listening"`, `"unlocked"`, `"failed"` (the PC refused or was unreachable; listening continues) and
  `"stopped"` (an error from Android, e.g. lockout after 5 bad fingers, or the API unavailable).
- **On a successful touch:** on a background thread, GET `/api/unlock/state`, take `screen`, require
  `UnlockChallenge.parse` + `acceptable(…, MODE_SCREEN, now)`, feed its UTF-8 bytes to the authenticated `Signature`,
  POST `/api/unlock/screen`. No challenge (PC already unlocked) means nothing is sent. Afterwards listening restarts
  while the conditions still hold (a new `CryptoObject` per touch).
- **Wrong finger:** keep listening; a short haptic.
- **What the user sees:** while listening, the bar shows "Touch the sensor to unlock the PC" (`unlock.touch`, in
  every locale like the other unlock strings) instead of the **Unlock PC** button. After `"stopped"`, or when
  listening is not possible, the **Unlock PC** button with the dialog comes back, as today. `"failed"` shows the
  existing "Not unlocked" message briefly.
- **The Unlock PC button** gets the same fix: after the dialog succeeds it fetches the current challenge and signs that
  one, instead of the challenge from the last poll.
- **sudo / polkit requests:** unchanged. A request card takes priority: while one is shown, the panel stops listening
  so the dialog can use the sensor, and resumes afterwards.

## Left to the builder

- Exact class split (a small `UnlockListener` next to `UnlockKey` is suggested), names, and log tags.
- Tests: Kotlin unit tests for the pure parts (when to listen, challenge choice), Playwright UI tests in
  `tools/ui-tests/unlock.spec.js` for the bar text, the fallback button and stop-on-unlock, with the bridge stubbed.
- README update in `tools/unlock/README.md` (screen unlock section).
