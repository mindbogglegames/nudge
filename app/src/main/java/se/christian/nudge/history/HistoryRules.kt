package se.christian.nudge.history

import java.time.Instant
import java.time.ZoneId

/** How a reminder was delivered, and why it wasn't the pill. */
data class DeliveryOutcome(val delivery: Delivery, val reason: DeliveryReason?)

/** What the service last recorded about itself, to tell a clean stop from the phone closing it. */
@kotlinx.serialization.Serializable
data class Heartbeat(val running: Boolean, val lastAliveMs: Long)

/** A row on the history screen: an entry, or a gap in monitoring between a stop and the next start. */
sealed interface HistoryRow {
    data class Entry(val entry: HistoryEntry) : HistoryRow
    data class Gap(val minutes: Long, val endTimeMs: Long) : HistoryRow
}

/** All the decisions behind the reminder history, free of Android so they're unit tested. */
object HistoryRules {
    const val MAX_ENTRIES = 500
    private const val MIN_GAP_MS = 60_000L

    /** The pill if it was added; otherwise the notification if notifications can be shown; otherwise nothing. */
    fun outcome(canDrawOverlays: Boolean, pillAdded: Boolean, notificationsAllowed: Boolean): DeliveryOutcome = when {
        canDrawOverlays && pillAdded -> DeliveryOutcome(Delivery.PILL, null)
        !notificationsAllowed -> DeliveryOutcome(Delivery.NOT_SHOWN, DeliveryReason.PILL_BLOCKED_NOTIFICATIONS_OFF)
        !canDrawOverlays -> DeliveryOutcome(Delivery.NOTIFICATION, DeliveryReason.OVERLAY_PERMISSION_OFF)
        else -> DeliveryOutcome(Delivery.NOTIFICATION, DeliveryReason.PILL_FAILED)
    }

    /** Local midnight at the start of the day containing [nowMs]. */
    fun startOfDay(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Today's entries in time order, at most [max] (the newest). */
    fun pruneToToday(entries: List<HistoryEntry>, startOfDayMs: Long, max: Int = MAX_ENTRIES): List<HistoryEntry> =
        entries.filter { it.timeMs >= startOfDayMs }.sortedBy { it.timeMs }.takeLast(max)

    /**
     * Entries to add when the service starts at [nowMs]. If the previous run never stopped cleanly, it ended
     * at its last sign of life: the last heartbeat, or the last entry logged after it ([lastEntryMs]), for
     * [exitReason] if Android knows it. If Android refused the start ([startFailed]), that's a stop rather than a start.
     */
    fun onServiceStart(
        previous: Heartbeat?,
        nowMs: Long,
        exitReason: StopReason?,
        nextId: () -> Long,
        startFailed: Boolean = false,
        lastEntryMs: Long? = null,
    ): List<HistoryEntry> = buildList {
        if (previous?.running == true) {
            val lastAlive = maxOf(previous.lastAliveMs, lastEntryMs ?: 0L).coerceAtMost(nowMs)
            add(MonitoringStopped(nextId(), lastAlive, exitReason ?: StopReason.KILLED))
        }
        add(if (startFailed) MonitoringStopped(nextId(), nowMs, StopReason.START_NOT_ALLOWED) else MonitoringStarted(nextId(), nowMs))
    }

    /** A problem worth the "Problems only" filter: a fallback, a reminder not shown, or a stop the user didn't ask for. */
    fun isProblem(entry: HistoryEntry): Boolean = when (entry) {
        is ReminderEntry -> entry.delivery != Delivery.PILL
        is MonitoringStopped -> entry.reason != StopReason.TURNED_OFF
        is MonitoringStarted -> false
    }

    /**
     * Rows newest first. A stop the user didn't ask for, followed by a start at least a minute later, gets a
     * [HistoryRow.Gap] between them. With [problemsOnly], only problems and their gaps (with the start that ends them).
     */
    fun rows(entries: List<HistoryEntry>, problemsOnly: Boolean): List<HistoryRow> {
        val sorted = entries.sortedBy { it.timeMs }
        val rows = mutableListOf<HistoryRow>()
        sorted.forEachIndexed { index, entry ->
            val gap = (entry as? MonitoringStarted)?.let { gapBefore(sorted, index) }
            if (!problemsOnly || isProblem(entry) || gap != null) {
                if (gap != null) rows += gap
                rows += HistoryRow.Entry(entry)
            }
        }
        return rows.asReversed()
    }

    private fun gapBefore(sorted: List<HistoryEntry>, startIndex: Int): HistoryRow.Gap? {
        val start = sorted[startIndex]
        val stop = sorted.subList(0, startIndex).lastOrNull { it is MonitoringStarted || it is MonitoringStopped }
        if (stop !is MonitoringStopped || stop.reason == StopReason.TURNED_OFF) return null
        val gapMs = start.timeMs - stop.timeMs
        return if (gapMs >= MIN_GAP_MS) HistoryRow.Gap(gapMs / 60_000L, start.timeMs) else null
    }

    data class Counts(val pill: Int, val notification: Int, val notShown: Int) {
        val total get() = pill + notification + notShown
    }

    fun counts(entries: List<HistoryEntry>): Counts {
        val reminders = entries.filterIsInstance<ReminderEntry>()
        return Counts(
            pill = reminders.count { it.delivery == Delivery.PILL },
            notification = reminders.count { it.delivery == Delivery.NOTIFICATION },
            notShown = reminders.count { it.delivery == Delivery.NOT_SHOWN },
        )
    }
}
