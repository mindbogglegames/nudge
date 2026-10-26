package se.christian.nudge.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Foreground app from UsageStats activity events: the package of the most recently resumed activity
 * that hasn't been paused or stopped since.
 *
 * Tracked per activity (package + class) rather than per package: when an app moves between its own
 * activities, the old activity's STOPPED arrives after the new one's RESUMED, and must not end the app.
 */
class ForegroundAppDetector(context: Context) {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private val resumed = HashMap<String, Resumed>()
    private var lastQueryEnd = 0L
    private val event = UsageEvents.Event()

    private data class Resumed(val packageName: String, val timestamp: Long)

    /** Queries new events since the last call (the last [SCAN_WINDOW_MS] on the first call). */
    fun currentForegroundPackage(now: Long = System.currentTimeMillis()): String? {
        val begin = if (lastQueryEnd == 0L || now - lastQueryEnd > SCAN_WINDOW_MS) {
            resumed.clear()
            now - SCAN_WINDOW_MS
        } else {
            // Re-read a little; processing is idempotent and this covers late-written events.
            lastQueryEnd - OVERLAP_MS
        }
        val events = usageStats.queryEvents(begin, now)
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val key = "${event.packageName}/${event.className}"
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    resumed[key] = Resumed(event.packageName, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val entry = resumed[key]
                    if (entry != null && entry.timestamp <= event.timeStamp) resumed.remove(key)
                }
            }
        }
        lastQueryEnd = now
        if (resumed.size > MAX_ENTRIES) {
            resumed.entries.sortedBy { it.value.timestamp }.take(resumed.size - MAX_ENTRIES)
                .forEach { resumed.remove(it.key) }
        }
        return resumed.values.maxByOrNull { it.timestamp }?.packageName
    }

    private companion object {
        const val SCAN_WINDOW_MS = 10 * 60_000L
        const val OVERLAP_MS = 2_000L
        const val MAX_ENTRIES = 32
    }
}
