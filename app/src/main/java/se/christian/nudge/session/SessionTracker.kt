package se.christian.nudge.session

import se.christian.nudge.settings.Settings

/** Monotonic time source in milliseconds. */
fun interface Clock {
    fun nowMillis(): Long
}

data class Reminder(val packageName: String, val sessionMinutes: Long)

/**
 * All session and reminder rules (see SPEC.md, "Session rules").
 *
 * Feed it the current foreground package on every poll via [onForeground]; `null` means the user is
 * away (launcher, ignored app, screen off). Not thread-safe: call from one thread.
 */
class SessionTracker(
    private val clock: Clock,
    var settings: Settings = Settings(),
    ignoredPackages: Set<String> = emptySet(),
) {
    private class Session(startedAt: Long) {
        var accumulatedMs = 0L
        var resumedAt: Long? = startedAt
        var pausedAt: Long? = null

        /** Session time at the last reminder, or null if none was shown in this session yet. */
        var lastReminderAtMs: Long? = null

        /** Set by a snooze; the first evaluation after the snooze ends reminds immediately. */
        var remindWhenSnoozeEnds = false

        fun elapsed(now: Long): Long = accumulatedMs + (resumedAt?.let { now - it } ?: 0L)
    }

    private val sessions = HashMap<String, Session>()
    private val snoozedUntil = HashMap<String, Long>()

    /** Package currently being counted, or null when away. */
    var activePackage: String? = null
        private set

    var ignoredPackages: Set<String> = ignoredPackages
        set(value) {
            field = value
            if (activePackage in value) onForeground(null)
            value.forEach { sessions.remove(it) }
        }

    /** Returns a reminder if one is due right now for [packageName]. */
    fun onForeground(packageName: String?): Reminder? {
        val now = clock.nowMillis()
        val pkg = packageName?.takeUnless { it in ignoredPackages }
        if (pkg != activePackage) {
            activePackage?.let { pause(it, now) }
            pkg?.let { resume(it, now) }
            activePackage = pkg
        }
        prune(now)
        return pkg?.let { evaluate(it, now) }
    }

    /** Suppress reminders for [packageName] for [Settings.snoozeMinutes]. */
    fun snooze(packageName: String) {
        snoozedUntil[packageName] = clock.nowMillis() + settings.snoozeMinutes * MINUTE_MS
        sessions[packageName]?.remindWhenSnoozeEnds = true
    }

    /** Current session length for [packageName], 0 if there is no live session. */
    fun sessionMillis(packageName: String): Long =
        sessions[packageName]?.elapsed(clock.nowMillis()) ?: 0L

    private val graceMs get() = settings.gracePeriodSeconds * 1000L

    private fun resume(pkg: String, now: Long) {
        val existing = sessions[pkg]
        val pausedAt = existing?.pausedAt
        if (existing != null && pausedAt != null && now - pausedAt <= graceMs) {
            existing.resumedAt = now
            existing.pausedAt = null
        } else {
            sessions[pkg] = Session(startedAt = now)
        }
    }

    private fun pause(pkg: String, now: Long) {
        val session = sessions[pkg] ?: return
        val resumedAt = session.resumedAt ?: return
        session.accumulatedMs += now - resumedAt
        session.resumedAt = null
        session.pausedAt = now
    }

    private fun prune(now: Long) {
        sessions.values.removeAll { session ->
            val pausedAt = session.pausedAt
            pausedAt != null && now - pausedAt > graceMs
        }
        snoozedUntil.values.removeAll { until -> now >= until }
    }

    private fun evaluate(pkg: String, now: Long): Reminder? {
        if (!settings.remindersEnabled || !settings.isAppEnabled(pkg)) return null
        val session = sessions[pkg] ?: return null
        if (snoozedUntil[pkg]?.let { now < it } == true) return null

        val elapsed = session.elapsed(now)
        val lastReminder = session.lastReminderAtMs
        val due = when {
            session.remindWhenSnoozeEnds -> true
            lastReminder == null -> elapsed >= settings.thresholdMinutesFor(pkg) * MINUTE_MS
            else -> elapsed >= lastReminder + settings.repeatMinutesFor(pkg) * MINUTE_MS
        }
        if (!due) return null

        session.remindWhenSnoozeEnds = false
        session.lastReminderAtMs = elapsed
        return Reminder(pkg, elapsed / MINUTE_MS)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
