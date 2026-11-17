package se.christian.nudge.session

import se.christian.nudge.settings.Settings

/** "15 min", "1 h", "1 h 5 min". */
fun formatMinutes(totalMinutes: Long): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0L -> "$minutes min"
        minutes == 0L -> "$hours h"
        else -> "$hours h $minutes min"
    }
}

/** Placeholder in a reminder message for the time used ("15 min", "1 h 5 min"). */
const val TIME_PLACEHOLDER = "{time}"

/** Placeholder in a reminder message for the app's name. */
const val APP_PLACEHOLDER = "{app}"

/** The built-in message for [kind]: "{time} on {app}" or "{time} on {app} today". */
fun defaultMessage(kind: ReminderKind): String = when (kind) {
    ReminderKind.Continuous -> "$TIME_PLACEHOLDER on $APP_PLACEHOLDER"
    ReminderKind.Daily -> "$TIME_PLACEHOLDER on $APP_PLACEHOLDER today"
}

/**
 * "15 min on YouTube" for a session; "1 h on YouTube today" for a daily total. A non-blank [message] replaces the built-in one;
 * its [TIME_PLACEHOLDER] and [APP_PLACEHOLDER] are filled in.
 */
fun reminderText(
    minutes: Long,
    appLabel: String,
    kind: ReminderKind = ReminderKind.Continuous,
    message: String? = null,
): String = (message?.takeIf { it.isNotBlank() } ?: defaultMessage(kind))
    .replace(TIME_PLACEHOLDER, formatMinutes(minutes), ignoreCase = true)
    .replace(APP_PLACEHOLDER, appLabel, ignoreCase = true)

/** The app's own message for [kind], or null for the built-in one. */
fun Settings.messageFor(packageName: String, kind: ReminderKind): String? = when (kind) {
    ReminderKind.Continuous -> continuousMessages[packageName]
    ReminderKind.Daily -> dailyMessages[packageName]
}
