package se.christian.nudge.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.christian.nudge.settings.Settings

class SessionTrackerTest {

    private class FakeClock(var now: Long = 1_000_000L) : Clock {
        override fun nowMillis() = now
    }

    private val clock = FakeClock()
    private val enabled = Settings(remindersEnabled = true, enabledPackages = setOf(YOUTUBE, CHROME))
    private val tracker = SessionTracker(clock, enabled, ignoredPackages = setOf(LAUNCHER))

    /** Polls every 5 s like the service, with [pkg] in the foreground for [minutes]. */
    private fun use(pkg: String?, minutes: Double): List<Reminder> {
        val reminders = mutableListOf<Reminder>()
        val end = clock.now + (minutes * 60_000).toLong()
        tracker.onForeground(pkg)?.let(reminders::add)
        while (clock.now < end) {
            clock.now = minOf(clock.now + POLL_MS, end)
            tracker.onForeground(pkg)?.let(reminders::add)
        }
        return reminders
    }

    private fun away(minutes: Double) = use(null, minutes)

    @Test
    fun `threshold reached gives exactly one reminder`() {
        assertTrue(use(YOUTUBE, 14.9).isEmpty())
        val reminders = use(YOUTUBE, 5.0)
        assertEquals(listOf(Reminder(YOUTUBE, 15)), reminders)
    }

    @Test
    fun `repeats every threshold by default`() {
        val reminders = use(YOUTUBE, 46.0)
        assertEquals(listOf(15L, 30L, 45L), reminders.map { it.sessionMinutes })
    }

    @Test
    fun `custom repeat interval`() {
        tracker.settings = enabled.copy(repeatIntervalMinutes = 5)
        val reminders = use(YOUTUBE, 26.0)
        assertEquals(listOf(15L, 20L, 25L), reminders.map { it.sessionMinutes })
    }

    @Test
    fun `switching away within grace keeps the session`() {
        use(YOUTUBE, 10.0)
        use(CHROME, 1.5)
        assertEquals(10 * 60_000L, tracker.sessionMillis(YOUTUBE))
        val reminders = use(YOUTUBE, 6.0)
        // Away time isn't counted: 10 + 5 = 15 min of use.
        assertEquals(listOf(Reminder(YOUTUBE, 15)), reminders)
    }

    @Test
    fun `switching away beyond grace resets the session`() {
        use(YOUTUBE, 10.0)
        use(CHROME, 2.5)
        assertTrue(use(YOUTUBE, 14.0).isEmpty())
        assertEquals(1, use(YOUTUBE, 1.5).size)
    }

    @Test
    fun `launcher counts as away`() {
        use(YOUTUBE, 10.0)
        use(LAUNCHER, 3.0)
        assertTrue(use(YOUTUBE, 6.0).isEmpty())
    }

    @Test
    fun `screen off within grace keeps the session`() {
        use(YOUTUBE, 10.0)
        tracker.onForeground(null) // screen off
        clock.now += 90_000 // no polling while the screen is off
        assertEquals(listOf(Reminder(YOUTUBE, 15)), use(YOUTUBE, 6.0))
    }

    @Test
    fun `screen off beyond grace resets the session`() {
        use(YOUTUBE, 10.0)
        tracker.onForeground(null)
        clock.now += 121_000
        assertTrue(use(YOUTUBE, 6.0).isEmpty())
        assertEquals(6 * 60_000L, tracker.sessionMillis(YOUTUBE))
    }

    @Test
    fun `custom grace period`() {
        tracker.settings = enabled.copy(gracePeriodSeconds = 30)
        use(YOUTUBE, 10.0)
        away(0.75)
        assertTrue(use(YOUTUBE, 6.0).isEmpty())
    }

    @Test
    fun `snooze suppresses then reminds immediately when it ends`() {
        tracker.settings = enabled.copy(repeatIntervalMinutes = 5)
        assertEquals(1, use(YOUTUBE, 15.0).size)
        tracker.snooze(YOUTUBE) // 10 min snooze, ends at 25 min
        assertTrue(use(YOUTUBE, 9.9).isEmpty()) // 20 min reminder suppressed
        assertEquals(listOf(Reminder(YOUTUBE, 25)), use(YOUTUBE, 0.2))
        // The repeat interval continues from the snooze end: next at 30.
        assertEquals(listOf(30L), use(YOUTUBE, 5.0).map { it.sessionMinutes })
    }

    @Test
    fun `snooze ending while session was reset does not remind immediately`() {
        use(YOUTUBE, 15.0)
        tracker.snooze(YOUTUBE)
        use(CHROME, 11.0)
        assertTrue(use(YOUTUBE, 5.0).isEmpty())
    }

    @Test
    fun `snooze is per package`() {
        tracker.snooze(CHROME)
        assertEquals(1, use(YOUTUBE, 15.0).size)
    }

    @Test
    fun `per-app threshold override`() {
        tracker.settings = enabled.copy(thresholdOverrides = mapOf(YOUTUBE to 5))
        assertEquals(listOf(5L, 10L, 15L), use(YOUTUBE, 16.0).map { it.sessionMinutes })
        assertEquals(listOf(15L), use(CHROME, 16.0).map { it.sessionMinutes })
    }

    @Test
    fun `override applies live during a session`() {
        use(YOUTUBE, 10.0)
        tracker.settings = enabled.copy(thresholdOverrides = mapOf(YOUTUBE to 5))
        assertEquals(Reminder(YOUTUBE, 10), tracker.onForeground(YOUTUBE))
    }

    @Test
    fun `disabled app never reminds`() {
        tracker.settings = enabled.copy(enabledPackages = setOf(CHROME))
        assertTrue(use(YOUTUBE, 120.0).isEmpty())
    }

    @Test
    fun `apps are off by default`() {
        tracker.settings = Settings(remindersEnabled = true)
        assertTrue(use(YOUTUBE, 120.0).isEmpty())
        assertTrue(use(CHROME, 120.0).isEmpty())
    }

    @Test
    fun `turning an app on mid-session reminds right away`() {
        tracker.settings = Settings(remindersEnabled = true)
        use(YOUTUBE, 20.0)
        tracker.settings = enabled
        assertEquals(Reminder(YOUTUBE, 20), tracker.onForeground(YOUTUBE))
    }

    @Test
    fun `master toggle off never reminds`() {
        tracker.settings = Settings(remindersEnabled = false)
        assertTrue(use(YOUTUBE, 120.0).isEmpty())
    }

    @Test
    fun `ignored packages are never tracked`() {
        assertTrue(use(LAUNCHER, 60.0).isEmpty())
        assertNull(tracker.activePackage)
        assertEquals(0L, tracker.sessionMillis(LAUNCHER))
    }

    @Test
    fun `newly ignored package stops being tracked`() {
        use(YOUTUBE, 10.0)
        tracker.ignoredPackages = setOf(LAUNCHER, YOUTUBE)
        assertTrue(use(YOUTUBE, 30.0).isEmpty())
        assertNull(tracker.activePackage)
    }

    @Test
    fun `reminder text`() {
        assertEquals("15 min on YouTube", reminderText(15, "YouTube"))
        assertEquals("59 min on YouTube", reminderText(59, "YouTube"))
        assertEquals("1 h on YouTube", reminderText(60, "YouTube"))
        assertEquals("1 h 5 min on YouTube", reminderText(65, "YouTube"))
        assertEquals("2 h 30 min on YouTube", reminderText(150, "YouTube"))
    }

    private companion object {
        const val POLL_MS = 5_000L
        const val YOUTUBE = "com.google.android.youtube"
        const val CHROME = "com.android.chrome"
        const val LAUNCHER = "com.google.android.apps.nexuslauncher"
    }
}
