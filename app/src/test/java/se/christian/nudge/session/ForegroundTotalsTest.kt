package se.christian.nudge.session

import org.junit.Assert.assertEquals
import org.junit.Test
import se.christian.nudge.session.UsageEvent.Type

class ForegroundTotalsTest {

    private fun resumed(t: Long, pkg: String, cls: String = "Main") = UsageEvent(Type.ActivityResumed, t, pkg, cls)
    private fun paused(t: Long, pkg: String, cls: String = "Main") = UsageEvent(Type.ActivityPaused, t, pkg, cls)

    @Test
    fun `adds up separate visits`() {
        val events = sequenceOf(resumed(100, A), paused(400, A), resumed(400, B), paused(500, B), resumed(900, A), paused(1000, A))
        assertEquals(mapOf(A to 400L, B to 100L), foregroundTotals(events, start = 0, end = 2000))
    }

    @Test
    fun `app open at the window start is counted from the start`() {
        val events = sequenceOf(resumed(-500, A), paused(300, A))
        assertEquals(mapOf(A to 300L), foregroundTotals(events, start = 0, end = 2000))
    }

    @Test
    fun `app still open at the window end is counted to the end`() {
        assertEquals(mapOf(A to 500L), foregroundTotals(sequenceOf(resumed(1500, A)), start = 0, end = 2000))
    }

    @Test
    fun `moving between activities of one app keeps counting`() {
        // The old activity's pause arrives after the new one's resume.
        val events = sequenceOf(resumed(0, A, "One"), resumed(100, A, "Two"), paused(110, A, "One"), paused(300, A, "Two"))
        assertEquals(mapOf(A to 300L), foregroundTotals(events, start = 0, end = 2000))
    }

    @Test
    fun `screen off and keyguard time is not counted`() {
        val events = sequenceOf(
            resumed(0, A),
            UsageEvent(Type.ScreenOff, 100),
            UsageEvent(Type.KeyguardShown, 100),
            UsageEvent(Type.ScreenOn, 500),
            UsageEvent(Type.KeyguardHidden, 600),
            paused(700, A),
        )
        assertEquals(mapOf(A to 200L), foregroundTotals(events, start = 0, end = 2000))
    }

    private companion object {
        const val A = "com.example.a"
        const val B = "com.example.b"
    }
}
