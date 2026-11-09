package se.christian.nudge.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.christian.nudge.settings.Settings
import java.time.LocalDate

class DailyTotalTest {

    private class FakeClock(var now: Long = 1_000_000L) : Clock {
        override fun nowMillis() = now
    }

    private val clock = FakeClock()
    private var date = LocalDate.of(2026, 11, 9)
    private val dailyOnly = Settings(
        remindersEnabled = true,
        continuousRemindersEnabled = false,
        dailyRemindersEnabled = true,
        defaultDailyLimitMinutes = 60,
        dailyRepeatIntervalMinutes = 15,
        enabledPackages = setOf(YOUTUBE, CHROME),
    )
    private val tracker = SessionTracker(clock, dailyOnly, ignoredPackages = setOf(LAUNCHER), today = { date })

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

    private fun daily(vararg minutes: Long) = minutes.map { Reminder(YOUTUBE, it, ReminderKind.Daily) }

    @Test
    fun `daily limit adds up separate sessions`() {
        // Four 20 min visits with long breaks: no continuous session ever reaches 60 min.
        assertTrue(use(YOUTUBE, 20.0).isEmpty())
        use(CHROME, 30.0)
        assertTrue(use(YOUTUBE, 20.0).isEmpty())
        use(null, 60.0)
        assertTrue(use(YOUTUBE, 19.9).isEmpty())
        assertEquals(daily(60), use(YOUTUBE, 1.0))
    }

    @Test
    fun `away time is not counted`() {
        use(YOUTUBE, 30.0)
        use(LAUNCHER, 30.0)
        tracker.onForeground(null) // screen off
        clock.now += 120 * 60_000L
        assertEquals(30 * 60_000L, tracker.dailyMillis(YOUTUBE))
    }

    @Test
    fun `repeats every daily repeat interval after the limit`() {
        val reminders = use(YOUTUBE, 61.0) + use(CHROME, 10.0) + use(YOUTUBE, 30.0)
        assertEquals(daily(60, 75, 90), reminders)
    }

    @Test
    fun `only once a day when repeat is off`() {
        tracker.settings = dailyOnly.copy(dailyRepeatIntervalMinutes = null)
        assertEquals(daily(60), use(YOUTUBE, 180.0))
    }

    @Test
    fun `totals reset at midnight`() {
        use(YOUTUBE, 61.0)
        date = date.plusDays(1)
        tracker.onForeground(YOUTUBE)
        assertEquals(0L, tracker.dailyMillis(YOUTUBE))
        assertTrue(use(YOUTUBE, 59.0).isEmpty())
        assertEquals(daily(60), use(YOUTUBE, 1.5))
    }

    @Test
    fun `use across midnight counts toward the new day`() {
        use(YOUTUBE, 50.0)
        date = date.plusDays(1)
        assertTrue(use(YOUTUBE, 59.0).isEmpty())
        assertEquals(daily(60), use(YOUTUBE, 1.5))
    }

    @Test
    fun `per-app daily limit override`() {
        tracker.settings = dailyOnly.copy(dailyLimitOverrides = mapOf(YOUTUBE to 30))
        assertEquals(listOf(30L), use(YOUTUBE, 31.0).map { it.minutes })
        assertTrue(use(CHROME, 59.0).isEmpty())
        assertEquals(listOf(Reminder(CHROME, 60, ReminderKind.Daily)), use(CHROME, 1.5))
    }

    @Test
    fun `raising the limit after a reminder reminds again at the new limit`() {
        tracker.settings = dailyOnly.copy(dailyRepeatIntervalMinutes = null)
        assertEquals(daily(60), use(YOUTUBE, 70.0))
        tracker.settings = dailyOnly.copy(dailyRepeatIntervalMinutes = null, defaultDailyLimitMinutes = 90)
        assertTrue(use(YOUTUBE, 19.9).isEmpty())
        assertEquals(daily(90), use(YOUTUBE, 1.0))
    }

    @Test
    fun `daily reminders off never remind for the daily total`() {
        tracker.settings = dailyOnly.copy(dailyRemindersEnabled = false)
        assertTrue(use(YOUTUBE, 120.0).isEmpty())
    }

    @Test
    fun `disabled app never gets a daily reminder`() {
        tracker.settings = dailyOnly.copy(enabledPackages = setOf(CHROME))
        assertTrue(use(YOUTUBE, 120.0).isEmpty())
    }

    @Test
    fun `apps are off by default for daily reminders`() {
        tracker.settings = dailyOnly.copy(enabledPackages = emptySet())
        assertTrue(use(YOUTUBE, 120.0).isEmpty())
    }

    @Test
    fun `ignored packages get no daily total`() {
        use(LAUNCHER, 120.0)
        assertEquals(0L, tracker.dailyMillis(LAUNCHER))
        tracker.seedDailyTotals(mapOf(LAUNCHER to 120 * 60_000L))
        assertEquals(0L, tracker.dailyMillis(LAUNCHER))
    }

    @Test
    fun `seeded total below the limit counts toward it`() {
        tracker.seedDailyTotals(mapOf(YOUTUBE to 50 * 60_000L))
        assertTrue(use(YOUTUBE, 9.9).isEmpty())
        assertEquals(daily(60), use(YOUTUBE, 1.0))
    }

    @Test
    fun `seeded total past the limit skips reminders already passed`() {
        // E.g. the service restarted at 70 min: the 60 min reminder was likely shown before.
        tracker.seedDailyTotals(mapOf(YOUTUBE to 70 * 60_000L))
        assertTrue(use(YOUTUBE, 4.9).isEmpty())
        assertEquals(daily(75), use(YOUTUBE, 1.0))
    }

    @Test
    fun `snooze suppresses the daily reminder then reminds when it ends`() {
        assertEquals(daily(60), use(YOUTUBE, 60.0))
        tracker.snooze(YOUTUBE) // ends at 70 min
        assertTrue(use(YOUTUBE, 9.9).isEmpty())
        assertEquals(daily(70), use(YOUTUBE, 0.2))
        assertEquals(daily(85), use(YOUTUBE, 15.0))
    }

    @Test
    fun `daily reminder wins when both types are due`() {
        tracker.settings = dailyOnly.copy(continuousRemindersEnabled = true, defaultThresholdMinutes = 15)
        val reminders = use(YOUTUBE, 61.0)
        assertEquals(
            listOf(15L to ReminderKind.Continuous, 30L to ReminderKind.Continuous, 45L to ReminderKind.Continuous,
                60L to ReminderKind.Daily),
            reminders.map { it.minutes to it.kind },
        )
        // The continuous 60 min reminder was recorded too, so the next one is at 75.
        assertEquals(listOf(75L), use(YOUTUBE, 15.0).map { it.minutes })
    }

    @Test
    fun `continuous reminders off leaves only daily reminders`() {
        tracker.settings = dailyOnly.copy(defaultThresholdMinutes = 15)
        assertEquals(daily(60), use(YOUTUBE, 61.0))
    }

    @Test
    fun `snoozing a continuous reminder does not trigger an early daily reminder`() {
        tracker.settings = dailyOnly.copy(continuousRemindersEnabled = true, defaultThresholdMinutes = 15)
        assertEquals(1, use(YOUTUBE, 15.0).size)
        tracker.snooze(YOUTUBE)
        val reminders = use(YOUTUBE, 10.2)
        assertEquals(listOf(Reminder(YOUTUBE, 25, ReminderKind.Continuous)), reminders)
    }

    @Test
    fun `daily reminder text`() {
        assertEquals("1 h on YouTube today", reminderText(60, "YouTube", ReminderKind.Daily))
        assertEquals("1 h 15 min on YouTube today", reminderText(75, "YouTube", ReminderKind.Daily))
    }

    private companion object {
        const val POLL_MS = 5_000L
        const val YOUTUBE = "com.google.android.youtube"
        const val CHROME = "com.android.chrome"
        const val LAUNCHER = "com.google.android.apps.nexuslauncher"
    }
}
