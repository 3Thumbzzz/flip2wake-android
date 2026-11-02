# Flip2Wake

Wake your Android device by **turning it over and back** instead of pressing the power button.

A small background service watches the accelerometer while the screen is off. When the device
rotates at least *N°* away from where it started and comes back again within the time limit,
the screen turns on (to the lock screen, just like the power button).

## Download

Every push to `main` is built by GitHub Actions. Grab **Flip2Wake.apk** from the
[latest release](../../releases/tag/latest) and install it (allow "install unknown apps").

## Settings (in the app)

| Setting | Range | Default | Meaning |
|---|---|---|---|
| Flip-to-wake service | on / off | off | Starts/stops the background service. Restarts automatically after reboot if left on. |
| Turning angle needed | 60°–180° | 180° | How far to turn the device from wherever it is right now before turning it back. 180° = a full flip. |
| Tolerance | ±5°–45° | ±20° | Error room on both halves: the turn may fall this far short, and the device only needs to come back to within this many degrees of where it started. |
| Maximum time for the whole movement | 0.3–5.0 s | 1.5 s | The whole over-and-back must finish inside this window. |

The app also has a **live sensor test** that shows the current tilt and flashes when a gesture is
detected, so you can tune the settings before relying on them.

The gesture is measured from wherever the device is resting when you start, so it works
face-up on a table, face-down, or held in your hand. The turning direction doesn't matter:
left or right, over the long or short edge, back the way it came, or continuing round the same
way until it's back where it started all count. (Spinning it flat on a table doesn't count, since
that doesn't change which way gravity points.)

Example: angle 180° ± 20° means turn it at least 160° over, then back to within 20° of the start.

## Setup tips

1. Turn the service on and allow notifications (Android 13+). The persistent notification
   is required by Android for background sensor access.
2. Tap **Disable battery optimisation**, otherwise Android may kill the service while
   the screen is off. On Samsung/Xiaomi/etc., also set the app's battery usage to "Unrestricted".
3. On Android 14+, if the screen doesn't wake reliably, tap **Allow full-screen wake (fallback)**.

## How it works

- `FlipDetector.java`: pure-Java gesture detector. Uses Android's gyro-fused gravity sensor
  when available (accurate during fast turns), otherwise a low-pass-filtered accelerometer.
  Keeps a short history and looks for "resting start → turned far enough → back near start"
  inside the time window, with a 1.5 s cooldown.
- `FlipWakeService.java`: foreground service. Only listens to the sensor while the screen is
  **off** (holds a partial wake lock then so samples keep arriving). On detection it acquires a
  screen wake lock with `ACQUIRE_CAUSES_WAKEUP`. If the screen is still off 350 ms later it
  falls back to a full-screen-intent notification that opens an invisible `WakeActivity` with
  `turnScreenOn`.
- `BootReceiver.java`: restarts the service after boot / app update.

**Battery:** keeping the CPU partly awake to read the accelerometer at ~50 Hz while the screen
is off costs some standby battery (typically a few % per day, depending on device).

## Building locally

Requires JDK 17 and the Android SDK (API 35).

```
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (signed with the debug key).
