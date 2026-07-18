# Prayer Alarm (Android)

A native Android app that alarms you at each of the five daily prayer times,
calculated from your GPS location, with per-prayer offsets and a choice of
playing the full azan out loud only when you're on your home Wi-Fi.

## Features

- Prayer times calculated on-device from GPS coordinates (no internet needed
  after the first location fix) using the standard sun-angle astronomical
  method used by most prayer-time apps.
- 14 calculation methods to choose from: Muslim World League, ISNA, Egyptian
  General Authority, Umm al-Qura (Makkah), University of Islamic Sciences
  Karachi, University of Tehran, Jafari (Shia Ithna-Ashari), Kuwait, Qatar,
  Singapore (MUIS), Diyanet (Turkey), UOIF (France), Russia, and Moonsighting
  Committee Worldwide — plus Standard/Hanafi Asr calculation.
- Per-prayer settings: a +/- minute offset, and one of four alarm modes —
  Off, Vibrate only, Full azan on home Wi-Fi only, or Full azan everywhere.
- Detects your currently connected Wi-Fi network and compares it to a home
  network name (SSID) you set in Settings.
- Full-screen alarm UI (works over the lock screen) with Dismiss / Snooze,
  like a normal alarm clock.
- Alarms survive reboots and re-schedule themselves automatically each day.

## Important: this is a source project, not a ready APK

I built this entire app for you, but the sandbox I run in has no Android
SDK, no Gradle, and no root access, and Google's/Maven's servers are
network-blocked from it — so I could not compile it into a `.apk` myself.
The fastest way to get a real installable APK is GitHub Actions (free),
included below.

## Option A — Build automatically with GitHub Actions (recommended, no installs)

1. Create a free GitHub account if you don't have one, and create a new
   **public or private repository** (e.g. `prayer-alarm`).
2. Upload this whole `PrayerAlarm` folder into that repository (drag-and-drop
   on github.com works, or use `git push` if you're comfortable with git).
3. Go to the repo's **Actions** tab. A workflow called "Build APK" will run
   automatically (it's triggered on every push, and you can also click
   "Run workflow" to trigger it manually).
4. When it finishes (a few minutes), open the finished run and download the
   **PrayerAlarm-debug-apk** artifact — that's a zip containing `app-debug.apk`.
5. Transfer `app-debug.apk` to your phone (email it to yourself, Google
   Drive, USB, etc.), tap it, and allow "install from this source" if asked.

This produces a debug-signed APK, which installs and runs exactly like any
other app — debug signing just means it's not going through the Play Store
release-signing process.

## Option B — Build locally with Android Studio

1. Install [Android Studio](https://developer.android.com/studio) (free).
2. Open this `PrayerAlarm` folder as a project (File → Open).
3. Let it sync (downloads the Android SDK/Gradle automatically the first time).
4. Click **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. Find the APK under `app/build/outputs/apk/debug/app-debug.apk` and copy it
   to your phone.

## Before you build: swap in a real azan recording

I could not download any audio in the sandbox (every audio-hosting site was
network-blocked), so `app/src/main/res/raw/azan_placeholder.wav` is currently
just a short generated chime — a placeholder, not a real azan.

To use a real azan recording:

1. Find or record an azan audio file you have the rights to use.
2. Convert/rename it to `azan_placeholder.mp3` (or keep it as `.wav`) — if
   you change the file extension, also update the one reference to
   `R.raw.azan_placeholder` in `AzanPlaybackService.kt` isn't extension
   -specific (Android resolves `R.raw.azan_placeholder` to whatever file
   matches that base name), so you can literally just delete the placeholder
   `.wav` and drop in your own `azan_placeholder.mp3` in the same folder.
3. Rebuild.

## First-run setup on your phone

1. Open the app, allow the location permission when asked (needed to
   calculate accurate prayer times) and the notification permission
   (Android 13+).
2. Tap **Update Location** once to get your GPS fix.
3. Go to **Settings** in the app:
   - Pick your calculation method and Asr madhab.
   - Enter your home Wi-Fi network name, or tap "Use Current Network" while
     connected to it at home.
   - For each prayer, set an offset (in minutes, can be negative) and choose
     an alarm mode.
   - If you see a banner about exact alarms, tap it and grant the permission
     — otherwise Android may fire alarms a few minutes late.
4. On some phones (Xiaomi/Huawei/Oppo/Vivo/Samsung with aggressive battery
   management), also go into your phone's battery settings and disable
   battery optimization / enable "autostart" for this app, or Android may
   kill it in the background and alarms won't fire.

## Known limitations

- Wi-Fi network name detection requires Location permission and Location
  services to be turned on — this is an Android OS restriction on all apps,
  not specific to this one.
- The Umm al-Qura and Qatar methods use a fixed 90-minute Isha interval
  year-round (the real Umm al-Qura method uses 120 minutes during Ramadan;
  this simplification isn't accounted for).
- The Moonsighting Committee method is approximated with fixed angles rather
  than its full seasonal-adjustment formula.
- Prayer times use your phone's current timezone setting, not a timezone
  looked up from GPS coordinates — fine for virtually all real-world use
  since your phone's timezone should match where you are.
