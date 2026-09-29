# Work Schedule

A tiny Android app that pauses and unpauses your **work profile** on a weekly schedule, for
example: work apps on Monday to Friday from 09:00 to 18:00, paused at all other times.

It does what the "Work apps" tile in Quick Settings does, just automatically. There is no
server, no account and no tracking; everything stays on the phone.

## Requirements

- Android 11 or newer.
- A work profile (the one your employer's device management app set up), with this app
  installed in your **personal** profile.
- A computer with `adb`, once, to grant one permission. Android offers no other way for a
  regular app to pause the work profile.

## Install

1. Open the [Actions tab](https://github.com/tiltbob/strawberry/actions), pick the latest
   successful run and download the `work-schedule-debug-apk` artifact. Unzip it.
2. Install the APK on the phone, for example with `adb install -r app-debug.apk`
   (or copy it over and open it).
3. Grant the permission (see below), open the app and set your schedule.

Every build is signed with the same key, so a new build installs over the old one and keeps the
permission. Do not uninstall to update: uninstalling drops the permission and you have to grant
it again.

## adb commands

Enable USB debugging on the phone (Settings > System > Developer options), connect it and run:

```sh
adb shell pm grant io.github.tiltbob.strawberry android.permission.MODIFY_QUIET_MODE
```

`pm grant` applies to Android user 0, which is the normal case. If your personal profile is a
different user (the app shows the exact command including `--user N` when it is), add that:

```sh
adb shell pm grant --user 10 io.github.tiltbob.strawberry android.permission.MODIFY_QUIET_MODE
```

The command can print nothing and still not work (for example if you typed the package name
wrong), so check it. Either reopen the app (the setup step disappears once the permission is
granted) or run:

```sh
adb shell dumpsys package io.github.tiltbob.strawberry | grep MODIFY_QUIET_MODE
```

and look for `granted=true`.

Optional but recommended: exempt the app from battery optimization so the schedule runs on
time even on phones that delay alarms. You can also do this from the app.

```sh
adb shell dumpsys deviceidle whitelist +io.github.tiltbob.strawberry
```

## How it behaves

- **One window per day.** You pick a start time, an end time and the days. Work apps are on
  inside the window and paused at all other times. If the end time is before the start time the
  window runs overnight and ends the next day (22:00 to 06:00); the same start and end time means
  24 hours. The days are the days a window *starts* on.
- **It only acts at the start and end of the window.** If you pause or unpause work apps by hand
  in between, the app leaves that alone until the next change in the schedule.
- **It re-applies the schedule after a reboot and whenever you change the schedule** (including
  turning "Follow schedule" on). A missed change, for example while the phone was off or after
  the clock or time zone changed, is applied as soon as the app notices.
- **Turning off "Follow schedule"** stops all alarms; the app then changes nothing.
- The app uses exact alarms and shows the next change on its screen.

### If your work profile has its own PIN

With one screen lock for both profiles, turning work apps on works silently, even while the
phone is locked (Android keeps the unlock for a while after you last unlocked the phone).

If your work profile has a **separate work PIN or pattern**, Android always asks for it before
work apps turn on; on Android 14 and newer an app can never do that silently. In that case the
app shows a notification "Tap to turn on work apps" at the start of the window. Tap it and
Android asks for your work PIN. The status in the app also shows this. Pausing never needs a
PIN.

## Caveats

- **Pixel "Work apps schedule":** Digital Wellbeing on Pixel phones has its own work schedule.
  Use one or the other; two schedules fight each other.
- **Company-owned phones:** on a fully managed, company-owned phone your IT admin can limit how
  long the work profile may stay off (at least 3 days). If it stays off longer, your personal
  apps get suspended until you turn work apps back on. A normal weekend (Friday 18:00 to Monday
  09:00, 63 hours) is fine, but long weekends or a failed unpause can go over. Watch for the
  app's notifications.
- **Your employer can see it:** the device management app is told when the work profile is
  paused or unpaused (not which app did it).
- **Pausing closes work apps**, including a running work call, when the window ends.
- **Battery savers:** some phone makers stop background apps aggressively. Allow unrestricted
  battery use (the app offers a button, or use the adb command above) if changes come late or
  not at all.
- **Unused apps:** Android pauses apps you have not opened for a few months, which cancels their
  alarms. The app asks you to turn this off for it. Opening the app also repairs its alarms.
- **Several work profiles:** some phones have features that look like a second work profile
  (for example Samsung Secure Folder). The app then asks which one to use. It never pauses a
  profile it cannot identify unless you pick it yourself.

## Building locally

You need JDK 21 and the Android SDK (platform 36).

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The APK ends up in `app/build/outputs/apk/debug/`.

`app/debug.keystore` is committed on purpose: it is a **publicly known debug key** (store and
key password `android`, alias `androiddebugkey`) that exists only so that every build, local or
CI, has the same signature and installs over the previous one. It offers no protection; anyone
can sign an APK with it. Only install builds from this repository's Actions or your own
machine.
