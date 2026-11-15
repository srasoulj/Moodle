# Moodle

A deliberately tiny, **fully offline** Android app that logs your mood in a moment.

* **One entry point:** a permanent notification with five emojis, worst to best:
  😡 😞 🙂 🤩 🥰 (stored as 1–5). Tap one, anywhere, any time.
* Every tap records **mood + time + GPS position + a selfie**, saved together. There is no way to
  skip the selfie or the position, and backing out saves nothing (no half-records).
* The app itself is a **month calendar** that shows every emoji of each day at a glance. Tap a day to
  see each moment's selfie, time and coordinates (tap the coordinates to open your maps app), and
  delete moments you logged by accident.
* **No internet.** The `INTERNET` permission is not in the APK (verified with `aapt2`), cloud backup
  is switched off, and selfies live in the app's private storage (not in your gallery).

## Install

1. On the phone, open this repo on GitHub and download `apk/moodle-1.2.apk` (it lives on the pull-request branch until that is merged).
2. Allow "install unknown apps" for your browser/file manager when Android asks, then install.
3. **Open Moodle once**, and grant Notifications, Camera and Location (precise).
   Android doesn't run an app's background receivers until it has been opened once, so this first
   launch is also what puts the emoji notification up.
4. Pull down the notification shade and tap an emoji.

Requires Android 9 (API 28) or newer.

## How a moment is recorded

1. Tap an emoji → the capture screen opens (if the phone is locked, Android asks you to unlock first).
2. The front camera opens **and GPS starts searching at the same time**.
3. Tap the shutter, review, then **Save moment** (or **Retake**).
4. Save is enabled as soon as there is a position. Rough is enough, so this is quick:
   * a last-known location from the last 15 minutes is used **immediately**, so Save is available the
     moment the screen opens;
   * any fresh fix within ~5 km (network, fused, or another app's fix) replaces it right away, and a
     precise GPS fix (100 m or better) ends the search as exact;
   * if nothing usable appears within ~8 s, the best fresh fix or the last-known location of any age
     is used; if there is nothing at all, Save stays disabled and you can **Retry** (or Cancel).
   Anything that isn't a precise GPS fix is stored flagged **approximate**. Either the precise or the
   approximate location permission is accepted.

Time is taken from the moment you tapped the emoji, together with the time zone you were in, so days
stay correct when you travel.

## Things worth knowing

* **"Permanent" notification.** Since Android 14 users can swipe away even ongoing notifications.
  Moodle re-posts it immediately when that happens, after a reboot, and after an app update; opening
  the app also restores it. If you switch notifications off in system settings the app shows a
  "Setup needed" banner with a Fix button.
* **No silent selfies.** Android doesn't let apps take background photos, so the camera screen is
  always shown. That also means you frame the shot.
* **Your data stays on the phone only.** Because backup is disabled, uninstalling the app or changing
  phones loses the history. There is no export feature (by design, to stay minimal).
* **Signing key is committed on purpose** (`moodle-release.jks`, password `moodle-local`). Every rebuild
  must carry the same signature, otherwise Android refuses to update the app and you would have to
  uninstall it, which deletes all moments. This is a personal, sideloaded app; don't reuse the key for
  anything else. Bump `versionCode` in `app/build.gradle.kts` for each build you install over the old one.

## Building

```
export ANDROID_HOME=/path/to/android-sdk   # needs platforms;android-36 and build-tools;35.0.0
./gradlew testDebugUnitTest lintDebug assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

Stack: Kotlin, Jetpack Compose (Material 3), CameraX, platform `LocationManager` (no Play Services),
plain SQLite. Tests: 50 JVM tests (88 runs: the behaviour suites run on both Android 9 / API 28, the minimum, and
Android 15 / API 35). They cover the pure calendar logic, SQLite, the notification wiring, the GPS
fallback timing, and the real activities driven through Robolectric: calendar → day → delete, and the
capture/save/cancel flows.

## Honest limitations

There was no emulator or phone where this was built, so it is verified by compilation, lint and the
Robolectric tests. On a real phone the camera capture and review screens have been seen working; the
parts still worth watching are real-world GPS timing indoors and how each phone's notification shade
lays out the emoji row.
