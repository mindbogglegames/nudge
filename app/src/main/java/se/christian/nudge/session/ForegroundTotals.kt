package se.christian.nudge.session

/** The subset of `UsageEvents.Event` needed to add up foreground time. Pure Kotlin so it can be unit tested. */
data class UsageEvent(val type: Type, val timestamp: Long, val packageName: String = "", val className: String = "") {
    enum class Type { ActivityResumed, ActivityPaused, ScreenOff, ScreenOn, KeyguardShown, KeyguardHidden }
}

/**
 * Foreground time per package between [start] and [end] (wall-clock ms), from usage events in timestamp order.
 *
 * Uses the same rule as foreground detection: the foreground app is the package of the most recently resumed
 * activity that hasn't been paused or stopped since, tracked per activity. Time with the screen off or the keyguard
 * showing isn't counted. Events before [start] only establish state, so pass a few hours of lookback to catch an app
 * that was already open at [start].
 */
fun foregroundTotals(events: Sequence<UsageEvent>, start: Long, end: Long): Map<String, Long> {
    val totals = HashMap<String, Long>()
    val resumed = HashMap<String, Pair<String, Long>>() // activity -> (package, resumed at)
    var screenOn = true
    var locked = false
    var cursor = start

    fun foreground(): String? =
        if (!screenOn || locked) null else resumed.values.maxByOrNull { it.second }?.first

    for (event in events) {
        val t = event.timestamp.coerceIn(start, end)
        foreground()?.let { pkg -> totals[pkg] = (totals[pkg] ?: 0L) + (t - cursor) }
        cursor = t
        val key = "${event.packageName}/${event.className}"
        when (event.type) {
            UsageEvent.Type.ActivityResumed -> resumed[key] = event.packageName to event.timestamp
            UsageEvent.Type.ActivityPaused -> {
                val entry = resumed[key]
                if (entry != null && entry.second <= event.timestamp) resumed.remove(key)
            }
            UsageEvent.Type.ScreenOff -> screenOn = false
            UsageEvent.Type.ScreenOn -> screenOn = true
            UsageEvent.Type.KeyguardShown -> locked = true
            UsageEvent.Type.KeyguardHidden -> locked = false
        }
    }
    foreground()?.let { pkg -> totals[pkg] = (totals[pkg] ?: 0L) + (end - cursor) }
    return totals.filterValues { it > 0 }
}
