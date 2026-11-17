package se.christian.nudge.session

import se.christian.nudge.settings.ReminderStyle

/** How the pill looks and behaves for one reminder. */
enum class PillLevel {
    /** Small pill, 6 s, no vibration. */
    SUBTLE,

    /** Big colored pill, 10 s with a time-left bar, one short vibration. */
    PROMINENT,

    /** Like [PROMINENT], but stays until closed (or the app is left), two short vibrations. */
    PERSISTENT,
}

/** The pill level for the [repeatIndex]-th reminder (1-based) under [style]. */
fun pillLevel(style: ReminderStyle, repeatIndex: Int): PillLevel = when (style) {
    ReminderStyle.SUBTLE -> PillLevel.SUBTLE
    ReminderStyle.PROMINENT -> PillLevel.PROMINENT
    ReminderStyle.GETS_STRONGER -> when {
        repeatIndex <= 1 -> PillLevel.SUBTLE
        repeatIndex == 2 -> PillLevel.PROMINENT
        else -> PillLevel.PERSISTENT
    }
}

/**
 * Repeat index for the "Show test reminder" button: 1, 2, 3, then 1 again, so each step of the style can be
 * previewed. Starts over at 1 after [resetAfterMs] without a tap.
 */
class TestReminderCycle(private val clock: Clock, private val resetAfterMs: Long = 60_000L) {
    private var index = 0
    private var lastTapMs: Long? = null

    fun next(): Int {
        val now = clock.nowMillis()
        if (lastTapMs?.let { now - it > resetAfterMs } != false) index = 0
        lastTapMs = now
        index = index % STEPS + 1
        return index
    }

    private companion object {
        const val STEPS = 3
    }
}
