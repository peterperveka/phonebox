# Phone sleeps with the PC, one touch wakes both

Follow-up to [2026-11-25-touch-to-unlock-screen-design.md](2026-11-25-touch-to-unlock-screen-design.md).

## Intent

Locking the PC (Super+L) also puts the phone to sleep. One press of the phone's power button with the finger left on
it unlocks the phone (its own fingerprint unlock) **and** the PC. The phone is used only as the PhoneBox panel.

## Goals

- Linux: "Follow the computer lock" (today macOS only) sleeps the phone when the session locks and wakes it with the
  panel when it unlocks.
- One touch: the phone's own fingerprint unlock opens the unlock key for a few seconds; the panel, back in front,
  signs the screen challenge at once, with no second touch.
- Everything from the touch-to-unlock design keeps working when the phone is awake.

## Non-goals

- No change to what sudo/polkit show: their fingerprint dialog with the command stays.
- No unlock of the PC from a phone unlocked by PIN or pattern.

## What the spike showed (2026-11-25, Xiaomi 2109119DG, Android 14, secure keyguard)

A key with `setUserAuthenticationParameters(10, AUTH_BIOMETRIC_STRONG)` signed 0.26 s after the phone was unlocked by
fingerprint on the power button (the panel resumes while `isKeyguardLocked` is still true). After a PIN unlock it threw
`UserNotAuthenticatedException` every time.

## Approaches

1. **One key with a 5 s window for everything** (chosen by the user). The key opens for 5 s after any strong
   biometric (the phone's own unlock, the touch listener, the sudo dialog). Simple; one re-setup + `enroll`. Cost: the
   hardware no longer ties one touch to one signature; the app still shows the sudo dialog before signing a request.
2. Two keys: per-use for sudo/polkit, 5 s window for the screen. Keeps sudo's hardware guarantee; PC holds two keys,
   `enroll` compares two codes. More work for no practical gain on a dedicated phone.
3. Sleep, then two touches (no key change). Rejected: the user wants one touch.

## Design

### Key (phone)

- New keys are created with `setUserAuthenticationParameters(5, AUTH_BIOMETRIC_STRONG)` (Android 11+; on 9–10
  `setUserAuthenticationValidityDurationSeconds(5)`), still `setInvalidatedByBiometricEnrollment(true)`.
- Signing no longer uses a `CryptoObject`: authenticate first (BiometricPrompt / FingerprintManager without a crypto
  object, strong biometrics only), then `initSign` + sign within the window. A `UserNotAuthenticatedException` from
  `initSign` means "needs a fingerprint", not an unusable key.
- An existing per-use key (window 0 / -1, read via `KeyInfo`) counts as missing: the panel offers **Set up fingerprint
  unlock** again, and after setup the user runs `sudo /usr/local/lib/phonebox/phonebox-unlock enroll` once more.

### On wake (phone)

- When the panel resumes and the PC is locked (agent state `locked` with a `screen` challenge), the app tries
  `signScreenNow` with the key straight away, without starting the sensor. Success unlocks the PC and reports
  "unlocked". `UserNotAuthenticatedException` (PIN unlock, or more than 5 s since the fingerprint) falls back to
  listening on the sensor as today.
- It tries once per resume, and only while the panel wants to listen (PC locked, no sudo card).

### Follow the computer lock on Linux (agent)

- `SessionLockMonitor` also runs on Linux, reading the lock from the same logind `ILoginSession` the unlock feature
  uses (graphical session id → `IsLocked`). The macOS code stays as it is.
- The same `ScreenSynchronizer` path: locked → `KEYCODE_SLEEP`; unlocked → wake, dismiss keyguard, start the panel.
- The paths that today check `OperatingSystem.IsMacOS()` next to `FollowComputerLock` (`AdbReverse.cs` ~535 and
  ~842) use "lock sync supported" (macOS or Linux).
- Settings: the "Follow the computer lock" checkbox shows on Linux too; its text names "the computer" instead of
  macOS in every locale.

## Left to the builder

- Exact names; where the "try on resume" lives (suggested: `UnlockListener.visible(true)` gets a first "try without
  the sensor" step, so it stays unit-testable).
- Tests: Kotlin unit tests for the try-on-resume step and its fallback; agent tests for Linux lock sync with a fake
  `ILoginSession`; UI test for the checkbox on Linux.
- README: the new key needs one re-setup and `enroll`; turn on "Follow the computer lock".
