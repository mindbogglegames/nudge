package se.christian.nudge.session

import org.junit.Assert.assertEquals
import org.junit.Test
import se.christian.nudge.settings.ReminderStyle

class ReminderLevelTest {

    @Test
    fun `subtle style is always subtle`() {
        assertEquals(List(5) { PillLevel.SUBTLE }, (1..5).map { pillLevel(ReminderStyle.SUBTLE, it) })
    }

    @Test
    fun `prominent style is always prominent`() {
        assertEquals(List(5) { PillLevel.PROMINENT }, (1..5).map { pillLevel(ReminderStyle.PROMINENT, it) })
    }

    @Test
    fun `gets stronger escalates with each repeat`() {
        assertEquals(
            listOf(PillLevel.SUBTLE, PillLevel.PROMINENT, PillLevel.PERSISTENT, PillLevel.PERSISTENT, PillLevel.PERSISTENT),
            (1..5).map { pillLevel(ReminderStyle.GETS_STRONGER, it) },
        )
    }

    @Test
    fun `gets stronger is the default style`() {
        assertEquals(ReminderStyle.GETS_STRONGER, se.christian.nudge.settings.Settings().reminderStyle)
    }

    private class FakeClock(var now: Long = 0L) : Clock {
        override fun nowMillis() = now
    }

    @Test
    fun `test reminder cycles through three repeats`() {
        val clock = FakeClock()
        val cycle = TestReminderCycle(clock)
        val steps = (1..5).map {
            clock.now += 5_000
            cycle.next()
        }
        assertEquals(listOf(1, 2, 3, 1, 2), steps)
    }

    @Test
    fun `test reminder cycle starts over after a minute without taps`() {
        val clock = FakeClock()
        val cycle = TestReminderCycle(clock)
        cycle.next()
        clock.now += 30_000
        assertEquals(2, cycle.next())
        clock.now += 61_000
        assertEquals(1, cycle.next())
    }
}
