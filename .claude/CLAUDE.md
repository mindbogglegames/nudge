# Nudge

Personal Android app (sideloaded, not for Play Store) that replicates Digital Wellbeing's "Screen time reminders", with one addition: the user chooses when reminders appear. After N minutes of continuous use of an app, a small pill slides in at the top of the screen. It never blocks the app.

- Product spec: `SPEC.md`. Read it before implementing or changing behavior.
- Visual reference: `/reference` (Digital Wellbeing screenshots). Match their layout, spacing and feel.

## Stack
- Kotlin, Jetpack Compose, Material 3 with dynamic color (Material You)
- Single `:app` module, Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`)
- minSdk 31; compileSdk/targetSdk = latest stable API level installed in the SDK
- Coroutines + Flow, Preferences DataStore, kotlinx.serialization
- applicationId `se.christian.nudge`

## Hard constraints
- No INTERNET permission, no network calls, no analytics. All data stays on device.
- No third-party libraries beyond AndroidX and Kotlin. No DI framework, no Room.
- No WorkManager or AlarmManager for usage polling (WorkManager's 15-min minimum interval is too coarse). Poll in a coroutine loop inside the foreground service.
- No AccessibilityService.
- No QUERY_ALL_PACKAGES. List apps via a `<queries>` element with the MAIN/LAUNCHER intent.

## Architecture
- `UsageMonitorService`: foreground service (type `specialUse`). Extends `LifecycleService` and implements `SavedStateRegistryOwner`. Detects the foreground app, feeds `SessionTracker`, shows reminders.
- `SessionTracker`: pure Kotlin, no Android imports, injectable clock. All session and reminder logic lives here.
- `ReminderOverlay`: the pill, a `ComposeView` in a WindowManager overlay.
- `ReminderNotifier`: heads-up notification fallback when the overlay isn't available.
- `SettingsRepository`: DataStore; exposes `Flow<Settings>` to both UI and service.
- UI: single activity, Compose.

## Implementation rules
- Foreground detection: `UsageStatsManager.queryEvents` with `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` / `ACTIVITY_STOPPED`. Never the deprecated `MOVE_TO_FOREGROUND` / `MOVE_TO_BACKGROUND`.
- Overlay window: `TYPE_APPLICATION_OVERLAY`, `WRAP_CONTENT` width and height, `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`, so touches outside the pill pass through to the app underneath.
- Overlay `ComposeView`: call `setViewTreeLifecycleOwner` and `setViewTreeSavedStateRegistryOwner` (the service) before `addView`. Guard `addView` / `removeView` with `isAttachedToWindow` checks and try/catch.
- The overlay uses the app's `MaterialTheme` (dynamic color, follows system dark mode).
- The `specialUse` service needs `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and the `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` `<property>` on the service in the manifest.
- Start the service only from the activity while it is in the foreground, or from the `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` receiver.
- All PendingIntents use `FLAG_IMMUTABLE`.
- Usage access check: `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS, ...)`. Overlay check: `Settings.canDrawOverlays`.
- Load app labels and icons off the main thread and cache them.

## Testing
- Any change to session or reminder rules needs a `SessionTracker` unit test (JUnit, fake clock).
- Keep Android dependencies out of `SessionTracker` so it runs on the JVM.

## Commands
```
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lint
./gradlew installDebug

# Grant permissions on a connected device without tapping through Settings
adb shell appops set se.christian.nudge GET_USAGE_STATS allow
adb shell appops set se.christian.nudge SYSTEM_ALERT_WINDOW allow
adb shell pm grant se.christian.nudge android.permission.POST_NOTIFICATIONS

# App logs
adb logcat --pid=$(adb shell pidof se.christian.nudge)
```

## Workflow
- Before finishing any task: `assembleDebug`, `testDebugUnitTest` and `lint` pass, with no new warnings.
- If `adb devices` shows a device, install and check logcat for crashes after any change to the service or overlay.
- Record non-obvious choices in README.md under "Decisions".
- Use current stable versions of AGP, Kotlin and the Compose BOM; verify they resolve rather than relying on memory.
