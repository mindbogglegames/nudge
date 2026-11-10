# Nudge: product spec

Technical constraints, architecture and commands are in `.claude/CLAUDE.md`. This file describes behavior.

## Goal
Replicate Android Digital Wellbeing's "Screen time reminders" (see `/reference`), with a user-configurable reminder time. After N minutes of continuous use of an app, a pill slides in at the top of the screen showing the app icon and e.g. "15 min on YouTube". Optionally, a second reminder type fires when an app's total use for the day reaches a limit ("1 h on YouTube today"). It never blocks the app.

## Foreground app detection
- Poll every 5 s, only while the screen is on and unlocked.
- Current foreground package = package of the most recent `ACTIVITY_RESUMED` without a later pause/stop for that package.
- On service start, scan the last 10 minutes of events to determine the current foreground app.
- In the service, dynamically register for `ACTION_SCREEN_OFF`, `ACTION_SCREEN_ON` and `ACTION_USER_PRESENT`. Stop polling on screen off; resume on `USER_PRESENT`, or on `SCREEN_ON` if the keyguard isn't locked. Screen off counts as "away".
- Ignored packages (never tracked, treated as "away"): this app, the default launcher (resolve via the HOME intent), `com.android.systemui`.

## Session rules (`SessionTracker`)
- A session is continuous foreground time in one package.
- Leaving the app (other app, launcher, screen off) pauses the session; away time isn't counted.
- Returning within the grace period (default 2 min) resumes the same session. Away longer than the grace period resets the session to 0.
- First reminder when session time >= the app's threshold, then repeat every repeat interval (default: same as the threshold) while the session continues.
- Snooze: suppress reminders for that package for the snooze duration (default 10 min), in memory only. If the session is still running when the snooze ends, remind immediately, then continue the repeat interval from there.
- Never remind for apps that aren't turned on, ignored packages, or when the master toggle is off.
- Continuous reminders only fire while `continuousRemindersEnabled` is on (default on) and the app isn't in `continuousOffPackages`.
- Reminder text uses the current session length: "15 min on YouTube"; at 60+ min, "1 h 5 min on YouTube".

## Daily total rules (`SessionTracker`)
- Only while `dailyRemindersEnabled` is on (default off) and the app isn't in `dailyOffPackages`. Same app gating as continuous reminders: master toggle, `enabledPackages`, ignored packages.
- An app's daily total is all its foreground time today, across sessions. Away time (other app, launcher, screen off) isn't counted; the grace period doesn't apply.
- Totals reset at local midnight. Use that runs across midnight counts toward the new day from midnight.
- On service start, seed today's totals from `UsageStatsManager` events since midnight (with a few hours of lookback to catch an app already open at midnight; screen-off and keyguard time excluded), so use before the service started counts. Reminder points already passed in the seeded total count as shown, so a restart doesn't repeat a reminder.
- First daily reminder when the total >= the app's daily limit, then every `dailyRepeatIntervalMinutes` of further use (default 15; null = only once a day). If the limit is raised above the last reminder, the next reminder is at the new limit.
- Snooze covers both types for that package. When it ends, a type that had already reminded and is still running reminds immediately, as for continuous reminders. A type that hadn't reminded yet is not brought forward.
- If both types are due on the same poll, show one reminder: the daily one. Both count as shown.
- Daily reminder text: "1 h on YouTube today", "1 h 15 min on YouTube today".

Required unit tests: apps are off by default; threshold reached gives exactly one reminder; repeat interval; switching away within grace keeps the session; switching away beyond grace resets; screen off within/beyond grace; snooze suppresses then resumes; per-app threshold override; disabled app never reminds; ignored packages never tracked. Daily: separate sessions add up; away time not counted; repeat interval and "only once"; midnight reset (also mid-use); per-app daily limit; daily off / app off never reminds; seeded totals below and past the limit; snooze; daily wins when both are due; adding up usage events (activity switches, screen off and keyguard, app open at midnight).

## Settings model
- Global: `remindersEnabled` (default false), `continuousRemindersEnabled` (true), `defaultThresholdMinutes` (15), `repeatIntervalMinutes` (null = same as threshold), `gracePeriodSeconds` (120), `snoozeMinutes` (10), `dailyRemindersEnabled` (false), `defaultDailyLimitMinutes` (60), `dailyRepeatIntervalMinutes` (15; null = only once a day).
- Per-app settings: `enabledPackages: Set<String>`, `thresholdOverrides: Map<String, Int>`, `dailyLimitOverrides: Map<String, Int>`, `continuousOffPackages: Set<String>`, `dailyOffPackages: Set<String>`. Each reminder type has a global switch; an enabled app can turn either type off for itself (e.g. daily total only) and override its time. Reminders are opt-in per app: an app gets reminders only if it's in `enabledPackages`, so every app, including newly installed ones, is off until the user turns it on. Enabled apps without an override use the default threshold.
- The service observes the settings Flow and applies changes live.

## The pill (primary reminder UI)
- Top center, just below the status bar (use the status bar insets from `WindowManager.currentWindowMetrics`).
- Collapsed: fully rounded pill, app icon (24 dp) + "15 min on YouTube" (or "1 h on YouTube today" for a daily total), surface container color with subtle elevation, matching the reference screenshots.
- Animation: slide down + fade in; slide up + fade out.
- Auto-dismiss after 6 s if untouched. Swipe up to dismiss.
- Tap: expand in place (`animateContentSize`) into a small card with actions:
  - "Snooze 10 min" (uses `snoozeMinutes`)
  - "Turn off for this app": removes the app from `enabledPackages`, shows "Reminders off for YouTube" in the pill for 2 s, then dismisses
  - "Settings": opens Nudge at that app's settings sheet. SYSTEM_ALERT_WINDOW should exempt the app from background activity launch restrictions; verify on device, and if the launch is blocked, fall back to a notification that opens the app.
- While expanded, dismiss 8 s after the last interaction.
- Only one pill at a time; a new reminder replaces the current one.

## Fallback notification
If overlay permission isn't granted or `addView` throws: post a heads-up notification on a high-importance channel "Reminders" with the same text, the app icon as large icon, and actions "Snooze" and "Turn off for this app" (handled by a BroadcastReceiver). Auto-cancel after 1 min (`setTimeoutAfter`).

## Service lifecycle
- Persistent notification on a low-importance channel "Monitoring": "Screen time reminders are on". Tap opens the app.
- Start when the master toggle turns on and usage access is granted; stop when it turns off.
- Restart on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` if `remindersEnabled`.
- Return `START_STICKY`.
- If usage access is revoked while running, stop the service and post a notification explaining why.

## Permissions and onboarding
Manifest permissions: `PACKAGE_USAGE_STATS` (`tools:ignore="ProtectedPermissions"`), `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

First launch: a short onboarding screen (what the app does; nothing leaves the phone), then a permission checklist:
1. Notifications: runtime request.
2. Usage access (required): open `Settings.ACTION_USAGE_ACCESS_SETTINGS`.
3. Display over other apps (recommended, needed for the pill): open `ACTION_MANAGE_OVERLAY_PERMISSION` with the package URI.
4. Unrestricted battery (recommended): `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; explain that some manufacturers kill background services.

Re-check all permission states in `onResume`. On the main screen, show a card for each missing permission.

## Main screen (styled like Android Settings / Digital Wellbeing)
- Edge-to-edge (`enableEdgeToEdge`, handle insets), large collapsing top app bar.
- Prominent "Use reminders" main switch in a rounded filled container, like Android's settings main switch.
- Permission warning cards when anything is missing.
- "Remind me" section with two switch rows (Android Settings primary-switch style: text opens a sheet, the switch toggles the type):
  - "Continuous use" ("After 15 min of continuous use" / "Off"): sheet headed "Remind me after 15 min of continuous use" with the default threshold.
  - "Daily total" ("After 1 h of use each day" / "Off"): sheet headed "Remind me after 1 h of use each day" with the default daily limit (chips 15/30/45 min, 1 h, 1 h 30 min, 2 h, 3 h, "Custom") and "After that, remind me every" ("Only once", 5/10/15/30 min, 1 h).
- "Advanced" row: repeat interval, grace period, snooze length (continuous use).
- When the main switch is off, dim and disable the "Remind me" rows and "Advanced" as well as the app list.
- "Show test reminder" button: shows the pill immediately for a sample app.
- "Apps" section: search field, then launchable apps sorted by name (excluding this app and the launcher), each with icon, name, subtitle (the active types, e.g. "15 min continuous · 1 h daily", "30 min continuous · custom", "1 h daily", or "Off") and a switch.
- Tapping an app row opens a `ModalBottomSheet`: "Used 47 min today" under the name, enable switch, then a "Continuous use" section and a "Daily total" section. Each has a switch in its title (turns that type off for this app only), a line with the current value ("Remind me after 15 min of continuous use", "Remind me after 30 min of use each day"), and chips: thresholds 5/10/15/20/30/45/60 min or daily limits, "Custom" and "Use default". A section is shown only while its type is on globally.
- Dim and disable the app list when the main switch is off.

## Known limitations (document in README)
- Split-screen and picture-in-picture: only the most recently resumed app is tracked.
- Apps that hide overlay windows (e.g. banking apps) won't show the pill.
- Aggressive OEM battery management may stop the service.

## Definition of done (initial build)
1. `./gradlew assembleDebug`, `./gradlew testDebugUnitTest` and `./gradlew lint` all pass, with no warnings introduced by this project.
2. If a device is connected: installed, permissions granted via adb, app launched, test reminder shown, no crashes in logcat.
3. README.md covers what the app does, build and install steps, permission setup, known limitations, and a "Decisions" section.
