# Skyline Weather

An Android home-screen weather widget with a hand-drawn sky. The UI is a single WebView page; the
widget is rendered natively into a `RemoteViews` bitmap, so it works without opening the app.

Data comes from [Open-Meteo](https://open-meteo.com/) — no API key, no account, no tracking.

## What it does

- 4×2 resizable widget: current conditions, today's range, six-hour forecast with sunrise/sunset
  slotted in at its real time, and a sky that changes with the weather and time of day.
- Follows your location: the stored place is re-checked on every refresh and rewritten when you
  move more than ~1.5 km, reverse-geocoded to a city name.
- Refreshes every 15 minutes while the phone is awake, immediately when you unlock it, when the
  network comes back, and when you open the app.
- Exact wakeup alarms during 07:00–23:00; overnight it falls back to non-wakeup alarms so it does
  not rouse the phone (the same trade vivo's own weather widget makes).
- Optional **Shizuku** integration: the app appears in Shizuku's app list and, once allowed, starts a
  watchdog with shell identity that revives the widget process within about a minute of it being
  killed. Without Shizuku everything still works, just with the scheduler's ~15 minute recovery.
- A foreground service keeps the refresh chain alive on devices that freeze background apps. It
  shows one silent, minimum-importance notification — that is required by Android for a foreground
  service, and it is not optional.

## Install

Download the APK from the [latest release](../../releases/latest) and install it. Android will ask
you to allow installing from unknown sources, because this is not on the Play Store.

Requires Android 8.0 (API 26) or newer; built and tested against Android 17 / API 37.

**Releases are signed with a private release key** (`CN=Skyline Weather`), which is not in this
repository. That is what Android uses to allow in-place updates, so keep your copy of the keystore —
losing it means future versions cannot be installed over the old one without an uninstall.

Reports before 8.60 were debug-signed; installing 8.60 over one of those requires an uninstall
first (different signature), which removes the widget from the home screen.

## Screenshots

| Widget (home screen) | App |
|---|---|
| ![Widget](docs/widget-fresh-2348.jpg) | ![App](docs/app-follows-location-1240.jpg) |

## Build

The project builds without Gradle: `aapt2` for resources, `javac` for sources, `d8`, `zipalign`
and `apksigner` from the Android build-tools.

```sh
cd android
./build.sh            # produces build/SkylineWeather.apk
./build.sh <serial>   # also installs to that adb device
```

`build.sh` expects:

- Android SDK at `~/Library/Android/sdk` with `build-tools/36.0.0` and `platforms/android-36`
- JDK 17 at `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`
- A debug keystore at `~/.android/debug.keystore`

The Shizuku SDK jars are vendored in `android/libs/` (`shizuku-api`, `shizuku-provider`,
`shizuku-aidl`, all 13.1.5), so the build needs no network access. All three are required — the
provider alone crashes on `IShizukuApplication` without the `aidl` jar.

Adjust the paths at the top of the script for your machine.

The script signs with `~/keystores/skyline-release.jks` when that file exists (override with
`SKYLINE_KS` and `SKYLINE_KS_PASS_FILE`), and otherwise falls back to the debug keystore, so a fresh
clone still builds.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | fetching the forecast |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` | keeping the widget's place in step with you while it refreshes in the background |
| `RECEIVE_BOOT_COMPLETED` | re-arming the refresh chain after a reboot |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | the keep-alive service |
| `POST_NOTIFICATIONS` | its notification |
| `SCHEDULE_EXACT_ALARM` | the 15-minute wakeup alarm |
| `VIBRATE` | in-app feedback |

## Shizuku (optional)

Open the app once; Shizuku shows its permission prompt (it may already be granted if you allowed it
earlier), and Skyline then appears in Shizuku's *Application management* list. Allowing it starts a
watchdog loop running as a child of the Shizuku server:

```sh
while true; do
  pidof com.skyline.weather >/dev/null 2>&1 ||
    cmd jobscheduler run -f com.skyline.weather 8127 >/dev/null 2>&1
  sleep 45
done
```

A pidfile at `/data/local/tmp/skyline.wd.pid` keeps it single-instance. Revoking the permission in
Shizuku, or never granting it, leaves the JobScheduler chain intact.

## Known limitations

- **Aggressive OEM memory management.** On a memory-tight phone the system may kill the app
  regardless of the foreground service. The persisted `JobScheduler` job restarts it within roughly
  one alarm cycle; an optional shell watchdog can shorten that to under a minute (see the release
  notes).
- **The notification cannot be swiped away.** Android marks foreground-service notifications
  `NO_CLEAR`; the app hides the status-bar icon but the shade entry stays.
- **Place names come from two geocoders.** The widget uses Android's `Geocoder`; the web UI uses
  Nominatim, so the two can disagree on the label for the same coordinates.
- No dark/light theme switch in the widget — it follows the weather and the time of day.

## Licence

No licence file is included, so the code is all rights reserved by default. Add one if you intend
to accept contributions.
