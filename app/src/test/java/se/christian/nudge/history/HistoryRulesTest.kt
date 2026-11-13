package se.christian.nudge.history

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.christian.nudge.session.ReminderKind
import java.time.LocalDateTime
import java.time.ZoneId

class HistoryRulesTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private var nextId = 0L
    private fun id() = ++nextId

    /** Epoch ms for a local time on 2026-11-13 (or another [day] of November). */
    private fun at(hour: Int, minute: Int = 0, second: Int = 0, day: Int = 13): Long =
        LocalDateTime.of(2026, 11, day, hour, minute, second).atZone(zone).toInstant().toEpochMilli()

    private fun reminder(timeMs: Long, delivery: Delivery = Delivery.PILL, reason: DeliveryReason? = null) =
        ReminderEntry(id(), timeMs, "com.google.android.youtube", ReminderKind.Continuous, "15 min on YouTube", delivery, reason)

    // Outcome

    @Test
    fun `pill added is shown as pill`() {
        assertEquals(DeliveryOutcome(Delivery.PILL, null), HistoryRules.outcome(true, true, notificationsAllowed = false))
    }

    @Test
    fun `no overlay permission falls back to a notification`() {
        assertEquals(
            DeliveryOutcome(Delivery.NOTIFICATION, DeliveryReason.OVERLAY_PERMISSION_OFF),
            HistoryRules.outcome(canDrawOverlays = false, pillAdded = false, notificationsAllowed = true),
        )
    }

    @Test
    fun `failed addView falls back to a notification`() {
        assertEquals(
            DeliveryOutcome(Delivery.NOTIFICATION, DeliveryReason.PILL_FAILED),
            HistoryRules.outcome(canDrawOverlays = true, pillAdded = false, notificationsAllowed = true),
        )
    }

    @Test
    fun `no pill and notifications off is not shown`() {
        val notShown = DeliveryOutcome(Delivery.NOT_SHOWN, DeliveryReason.PILL_BLOCKED_NOTIFICATIONS_OFF)
        assertEquals(notShown, HistoryRules.outcome(canDrawOverlays = false, pillAdded = false, notificationsAllowed = false))
        assertEquals(notShown, HistoryRules.outcome(canDrawOverlays = true, pillAdded = false, notificationsAllowed = false))
    }

    // Today only

    @Test
    fun `start of day is local midnight`() {
        assertEquals(at(0), HistoryRules.startOfDay(at(14, 5), zone))
        assertEquals(at(0), HistoryRules.startOfDay(at(0), zone))
    }

    @Test
    fun `pruning keeps today's entries in time order`() {
        val yesterday = reminder(at(23, 59, day = 12))
        val morning = reminder(at(8))
        val evening = reminder(at(20))
        val pruned = HistoryRules.pruneToToday(listOf(evening, yesterday, morning), HistoryRules.startOfDay(at(21), zone))
        assertEquals(listOf(morning, evening), pruned)
    }

    @Test
    fun `entries from before midnight are dropped once the day changes`() {
        val lateEvening = reminder(at(23, 50))
        val afterMidnight = reminder(at(0, 5, day = 14))
        val entries = listOf(lateEvening, afterMidnight)
        assertEquals(entries, HistoryRules.pruneToToday(entries, HistoryRules.startOfDay(at(23, 55), zone)))
        assertEquals(listOf(afterMidnight), HistoryRules.pruneToToday(entries, HistoryRules.startOfDay(at(0, 10, day = 14), zone)))
    }

    @Test
    fun `pruning keeps the newest 500`() {
        val entries = (0 until 600).map { reminder(at(8) + it * 1_000L) }
        val pruned = HistoryRules.pruneToToday(entries, at(0))
        assertEquals(HistoryRules.MAX_ENTRIES, pruned.size)
        assertEquals(entries.takeLast(500), pruned)
    }

    // Heartbeat and gaps

    @Test
    fun `clean previous stop only logs a start`() {
        val added = HistoryRules.onServiceStart(Heartbeat(running = false, lastAliveMs = at(9)), at(10), null, ::id)
        assertEquals(1, added.size)
        assertTrue(added.single() is MonitoringStarted)
        assertEquals(at(10), added.single().timeMs)
    }

    @Test
    fun `first start ever only logs a start`() {
        val added = HistoryRules.onServiceStart(null, at(10), null, ::id)
        assertEquals(listOf(MonitoringStarted::class), added.map { it::class })
    }

    @Test
    fun `run without a clean stop ended at its last heartbeat`() {
        val added = HistoryRules.onServiceStart(Heartbeat(running = true, lastAliveMs = at(10, 15)), at(10, 42), null, ::id)
        val stopped = added[0] as MonitoringStopped
        assertEquals(at(10, 15), stopped.timeMs)
        assertEquals(StopReason.KILLED, stopped.reason)
        assertEquals(at(10, 42), (added[1] as MonitoringStarted).timeMs)
    }

    @Test
    fun `entries logged after the last heartbeat move the stop later`() {
        val added = HistoryRules.onServiceStart(
            previous = Heartbeat(running = true, lastAliveMs = at(10, 15)),
            nowMs = at(10, 42),
            exitReason = null,
            nextId = ::id,
            lastEntryMs = at(10, 15, 40),
        )
        assertEquals(at(10, 15, 40), added[0].timeMs)
    }

    @Test
    fun `known exit reason replaces the generic one`() {
        val added = HistoryRules.onServiceStart(Heartbeat(true, at(10)), at(10, 5), StopReason.FORCE_STOPPED, ::id)
        assertEquals(StopReason.FORCE_STOPPED, (added[0] as MonitoringStopped).reason)
    }

    @Test
    fun `refused start is logged as a stop`() {
        val added = HistoryRules.onServiceStart(null, at(10), null, ::id, startFailed = true)
        assertEquals(StopReason.START_NOT_ALLOWED, (added.single() as MonitoringStopped).reason)
    }

    @Test
    fun `gap between an unexpected stop and the next start is shown`() {
        val stopped = MonitoringStopped(id(), at(10, 15), StopReason.KILLED)
        val started = MonitoringStarted(id(), at(10, 42))
        val rows = HistoryRules.rows(listOf(stopped, started), problemsOnly = false)
        assertEquals(
            listOf(HistoryRow.Entry(started), HistoryRow.Gap(27, at(10, 42)), HistoryRow.Entry(stopped)),
            rows,
        )
    }

    @Test
    fun `no gap under a minute or after turning reminders off`() {
        val quick = listOf(MonitoringStopped(id(), at(10), StopReason.KILLED), MonitoringStarted(id(), at(10) + 30_000))
        assertTrue(HistoryRules.rows(quick, false).none { it is HistoryRow.Gap })
        val turnedOff = listOf(MonitoringStopped(id(), at(10), StopReason.TURNED_OFF), MonitoringStarted(id(), at(11)))
        assertTrue(HistoryRules.rows(turnedOff, false).none { it is HistoryRow.Gap })
    }

    @Test
    fun `rows are newest first`() {
        val first = reminder(at(9))
        val second = reminder(at(11))
        assertEquals(listOf(HistoryRow.Entry(second), HistoryRow.Entry(first)), HistoryRules.rows(listOf(first, second), false))
    }

    @Test
    fun `problems only keeps fallbacks, not shown and gaps`() {
        val pill = reminder(at(9))
        val notification = reminder(at(9, 30), Delivery.NOTIFICATION, DeliveryReason.OVERLAY_PERMISSION_OFF)
        val notShown = reminder(at(9, 45), Delivery.NOT_SHOWN, DeliveryReason.PILL_BLOCKED_NOTIFICATIONS_OFF)
        val stopped = MonitoringStopped(id(), at(10), StopReason.KILLED)
        val started = MonitoringStarted(id(), at(10, 30))
        val laterStart = MonitoringStarted(id(), at(12))
        val rows = HistoryRules.rows(listOf(pill, notification, notShown, stopped, started, laterStart), problemsOnly = true)
        assertEquals(
            listOf(
                HistoryRow.Entry(started),
                HistoryRow.Gap(30, at(10, 30)),
                HistoryRow.Entry(stopped),
                HistoryRow.Entry(notShown),
                HistoryRow.Entry(notification),
            ),
            rows,
        )
    }

    @Test
    fun `counts by delivery`() {
        val entries = listOf(
            reminder(at(9)),
            reminder(at(10)),
            reminder(at(11), Delivery.NOTIFICATION, DeliveryReason.PILL_FAILED),
            reminder(at(12), Delivery.NOT_SHOWN, DeliveryReason.PILL_BLOCKED_NOTIFICATIONS_OFF),
            MonitoringStarted(id(), at(8)),
        )
        assertEquals(HistoryRules.Counts(pill = 2, notification = 1, notShown = 1), HistoryRules.counts(entries))
    }

    @Test
    fun `entries survive a JSON round trip`() {
        val entries: List<HistoryEntry> = listOf(
            reminder(at(9)).copy(end = ReminderEnd.SNOOZED, test = true),
            MonitoringStarted(id(), at(8)),
            MonitoringStopped(id(), at(7), StopReason.USAGE_ACCESS_REVOKED),
        )
        val json = Json.encodeToString(entries)
        assertEquals(entries, Json.decodeFromString<List<HistoryEntry>>(json))
    }
}
