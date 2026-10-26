# Nudge

A personal Android app that replicates Digital Wellbeing's **Screen time reminders**, with one addition: you choose when they appear. After N minutes of continuous use of an app, a small pill slides in at the top of the screen with the app's icon and, for example, "15 min on YouTube". It never blocks the app.

- Reminders are opt-in per app: every app starts off, including newly installed ones. Turn on the apps you want in the app list.
- Default reminder time is 15 min, configurable globally and per app.
- Repeats every N minutes while you keep using the app (configurable).
- Leaving an app briefly (default 2 min grace period) doesn't reset the session; away time isn't counted.
- Tap the pill for **Snooze 10 min**, **Turn off for this app** and **Settings**. Swipe it up to dismiss; it goes away on its own after 6 s.
- If the pill can't be shown, a heads-up notification with the same actions is used instead.
- Nothing leaves the phone: no internet permission, no analytics, no backup.

Behavior is specified in [SPEC.md](SPEC.md); technical constraints are in [.claude/CLAUDE.md](.claude/CLAUDE.md).

## Build and install

Requirements: JDK 17+ (Android Studio's bundled JBR works), Android SDK with **Platform 37.2** and Build-Tools 36+.

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # SessionTracker rules
./gradlew lint
./gradlew installDebug         # to a connected device
```

## Permission setup

The first launch walks through these. The main screen shows a card for anything still missing.

| Permission | Why | |
|---|---|---|
| Usage access | Detects the foreground app | Required |
| Display over other apps | Shows the pill | Recommended (otherwise notifications) |
| Notifications | Persistent "reminders are on" notification and the fallback reminders | Recommended |
| Unrestricted battery | Some manufacturers kill background services | Recommended |

To skip tapping through Settings on a connected device:

```
adb shell appops set se.christian.nudge GET_USAGE_STATS allow
adb shell appops set se.christian.nudge SYSTEM_ALERT_WINDOW allow
adb shell pm grant se.christian.nudge android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +se.christian.nudge
```

Then open Nudge, turn on **Use reminders**, and tap **Show test reminder**.

On Android 13+ a sideloaded app may need **App info > ⋮ > Allow restricted settings** before usage access and overlay can be granted.

## How it works

- `UsageMonitorService` (foreground service, type `specialUse`) polls `UsageStatsManager.queryEvents` every 5 s while the screen is on and unlocked, and stops polling on screen off.
- `ForegroundAppDetector` turns `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` / `ACTIVITY_STOPPED` events into the current foreground package.
- `SessionTracker` (pure Kotlin, unit tested) owns all session, grace, repeat and snooze rules.
- `ReminderOverlay` shows `ReminderPill` (Compose) in a `TYPE_APPLICATION_OVERLAY` window; `ReminderNotifier` is the notification fallback.
- `SettingsRepository` stores settings in Preferences DataStore and exposes a `Flow<Settings>` that both the UI and the service observe.

## Known limitations

- **Split-screen and picture-in-picture:** only the most recently resumed app is tracked.
- **Apps that hide overlay windows** (for example banking apps using `setHideOverlayWindows`) won't show the pill.
- **Aggressive OEM battery management** may stop the service. Allow unrestricted battery use; on some brands you also need to lock the app in recents or allow autostart.
- Snoozes are in memory only and are lost if the service restarts.
- Usage events are read every 5 s, so reminders can be up to 5 s late and app switches are detected with the same delay.

## Decisions

- **Reminders are opt-in per app** (changed after the initial build, at Christian's request). The original spec enabled every app not listed in `disabledPackages`; settings now store `enabledPackages` instead, and an app gets reminders only if it's in that set. Newly installed apps start off. "Turn off for this app" removes the app from the set. There's no migration from `disabled_packages`, because no build with the old model was ever installed beyond testing.

- **compileSdk/targetSdk 37 (Platform 37.2).** The current stable AndroidX releases (core 1.19, lifecycle 2.11, Compose 1.12 via BOM 2026.09.00) refuse to build against anything lower. 37.2 is the latest stable platform, and it was installed into the SDK for this project.
- **AGP 9 built-in Kotlin.** The `org.jetbrains.kotlin.android` plugin isn't applied; it's declared `apply false` in the root build only to pin the Kotlin Gradle plugin to the same version as the Compose and serialization compiler plugins (2.4.21). The one Gradle deprecation warning in the build (`Configuration.setVisible`) comes from AGP itself, not from this project.
- **Foreground detection tracks activities, not packages.** The spec says "most recent `ACTIVITY_RESUMED` without a later pause/stop for that package". Taken per package, moving between two activities of the same app would end the session, because the old activity's `ACTIVITY_STOPPED` arrives after the new one's `ACTIVITY_RESUMED`. So resumed state is kept per package + class, and the foreground app is the package of the most recent still-resumed activity. Each poll re-reads a 2 s overlap, which is safe because processing is idempotent; after more than 10 min without polling, it rescans the last 10 min from scratch.
- **Session clock is `elapsedRealtime`**, so wall-clock changes don't affect sessions.
- **Repeat timing** is measured from when the previous reminder was actually shown (up to one poll after it was due). After a snooze ends, the next repeat counts from the immediate post-snooze reminder, as the spec says.
- **A snoozed session that resets** (away longer than the grace period) does not get the "remind immediately when the snooze ends" reminder; the new session follows the normal threshold, still suppressed until the snooze ends.
- **Ignored packages** (this app, the launcher, System UI) count as "away". The launcher is resolved again each time polling resumes, in case it changed.
- **Usage access check:** `checkOpNoThrow` on API 36+, `unsafeCheckOpNoThrow` below. `unsafeCheckOpNoThrow` is deprecated from API 36, and its replacement was deprecated before 36, so each is used only where it isn't deprecated.
- **Overlay window** uses `fitInsetsTypes = 0` and an explicit `y` from the status bar inset of `currentWindowMetrics`; otherwise the system would also fit it below the status bar and the offset would double. It's created from a window context (`createWindowContext`), the supported way to add windows from a service.
- **The overlay window jumps to its expanded width in one step** when the pill is tapped, instead of wrapping the size animation. A WRAP_CONTENT overlay that follows `animateContentSize` relayouts and re-centers every frame, and on the emulator the window lagged behind and clipped the card.
- **The pill slides in from under the status bar.** The window wraps the pill (so the rest of the screen stays touchable), and the slide is clipped at the window's top edge, which sits at the bottom of the status bar.
- **Test reminder lifecycle:** the test pill shown from the activity uses its own always-resumed lifecycle owner. With the activity's lifecycle, leaving the app within 6 s would pause the Compose frame clock and freeze the pill on screen. The service is its own owner, as specified.
- **"Settings" from the pill** starts the activity directly (SYSTEM_ALERT_WINDOW with a visible overlay exempts the app from background activity launch limits). If the activity isn't resumed 2 s later, a notification that opens the app's settings sheet is posted instead. Verified on an Android 16 (API 36) emulator, where the launch is allowed (`BAL_ALLOW_NON_APP_VISIBLE_WINDOW`); not yet verified on a physical device.
- **Pill actions reach the service through an in-process `SharedFlow`** (`ReminderEvents`), used by both the pill and the notification's BroadcastReceiver, so snoozes don't need to start the service.
- **The service runs exactly when "Use reminders" is on and usage access is granted.** The activity starts it once the setting has been saved (not on the tap itself), so the service never reads the old "off" value and stops itself.
- **A third notification channel, "Status"** (default importance), is used for "usage access was turned off" and the settings-launch fallback. Neither belongs on the silent "Monitoring" channel or the heads-up "Reminders" channel.
- **On API 31–33** the service is started without a type, since `specialUse` only exists from API 34.
- **No backup:** `allowBackup="false"` and data extraction rules that exclude everything, so settings never leave the phone.
- **Per-app threshold overrides** are stored as a JSON string (kotlinx.serialization) in DataStore, because Preferences DataStore has no map type.
- **UI strings are in Kotlin, not `strings.xml`.** The app is English-only and personal; only the app name is a resource.
- **Main screen title is "Nudge"** rather than "Screen time reminders", to match the launcher label.
- **No `/reference` screenshots were in the repository** when this was built, so the look follows Android 16 Settings and Digital Wellbeing from memory (Material 3, dynamic color, a filled main switch, `surfaceContainer` pill with 3 dp shadow). Check it against the screenshots once they're added.
