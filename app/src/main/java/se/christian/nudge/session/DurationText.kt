package se.christian.nudge.session

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

/** "15 min on YouTube". */
fun reminderText(sessionMinutes: Long, appLabel: String): String =
    "${formatMinutes(sessionMinutes)} on $appLabel"
