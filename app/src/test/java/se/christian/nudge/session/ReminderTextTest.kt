package se.christian.nudge.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.christian.nudge.settings.Settings

class ReminderTextTest {
    @Test
    fun `custom message fills in time and app`() {
        assertEquals(
            "1 h 5 min on YouTube, time for a break?",
            reminderText(65, "YouTube", ReminderKind.Continuous, "{time} on {app}, time for a break?"),
        )
        assertEquals("YouTube: 2 h today", reminderText(120, "YouTube", ReminderKind.Daily, "{app}: {time} today"))
    }

    @Test
    fun `placeholders are case-insensitive and can repeat`() {
        assertEquals("15 min, 15 min on YouTube", reminderText(15, "YouTube", message = "{TIME}, {Time} on {APP}"))
    }

    @Test
    fun `message without placeholders is shown as written`() {
        assertEquals("Put the phone down", reminderText(15, "YouTube", message = "Put the phone down"))
    }

    @Test
    fun `blank or missing message uses the built-in one`() {
        assertEquals("15 min on YouTube", reminderText(15, "YouTube", message = "  "))
        assertEquals("1 h on YouTube today", reminderText(60, "YouTube", ReminderKind.Daily, message = null))
    }

    @Test
    fun `messages are per app and per reminder type`() {
        val settings = Settings(
            continuousMessages = mapOf(YOUTUBE to "{time} of videos"),
            dailyMessages = mapOf(CHROME to "{time} browsing today"),
        )
        assertEquals("{time} of videos", settings.messageFor(YOUTUBE, ReminderKind.Continuous))
        assertNull(settings.messageFor(YOUTUBE, ReminderKind.Daily))
        assertEquals("{time} browsing today", settings.messageFor(CHROME, ReminderKind.Daily))
        assertNull(settings.messageFor(CHROME, ReminderKind.Continuous))
    }

    private companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val CHROME = "com.android.chrome"
    }
}
