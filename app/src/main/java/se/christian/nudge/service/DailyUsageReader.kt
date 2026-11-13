package se.christian.nudge.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import se.christian.nudge.session.UsageEvent
import se.christian.nudge.session.foregroundTotals
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's foreground time per package from the system's usage events, so time before the service started counts
 * toward daily totals. Needs usage access; returns an empty map without it. Call off the main thread.
 */
class DailyUsageReader(context: Context) {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)

    fun todayTotals(now: Long = System.currentTimeMillis()): Map<String, Long> {
        val midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val events = runCatching { usageStats.queryEvents(midnight - LOOKBACK_MS, now) }.getOrNull()
            ?: return emptyMap()
        val sequence = sequence {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> UsageEvent.Type.ActivityResumed
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> UsageEvent.Type.ActivityPaused
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEvent.Type.ScreenOff
                    UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEvent.Type.ScreenOn
                    UsageEvents.Event.KEYGUARD_SHOWN -> UsageEvent.Type.KeyguardShown
                    UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEvent.Type.KeyguardHidden
                    else -> null
                } ?: continue
                yield(UsageEvent(type, event.timeStamp, event.packageName.orEmpty(), event.className.orEmpty()))
            }
        }
        return foregroundTotals(sequence, start = midnight, end = now)
    }

    private companion object {
        /** Events before midnight establish which app was already open at midnight. */
        const val LOOKBACK_MS = 3 * 60 * 60_000L
    }
}
