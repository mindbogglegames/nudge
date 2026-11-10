package se.christian.nudge.session

import se.christian.nudge.settings.Settings
import java.time.LocalDate

/** Monotonic time source in milliseconds. */
fun interface Clock {
    fun nowMillis(): Long
}

enum class ReminderKind {
    /** [Reminder.minutes] is the current session length. */
    Continuous,

    /** [Reminder.minutes] is the app's total use today. */
    Daily,
}

data class Reminder(
    val packageName: String,
    val minutes: Long,
    val kind: ReminderKind = ReminderKind.Continuous,
)

/**
 * All session and reminder rules (see SPEC.md, "Session rules" and "Daily total rules").
 *
 * Feed it the current foreground package on every poll via [onForeground]; `null` means the user is
 * away (launcher, ignored app, screen off). Not thread-safe: call from one thread.
 *
 * @param today the current local date; daily totals reset when it changes.
 */
class SessionTracker(
    private val clock: Clock,
    var settings: Settings = Settings(),
    ignoredPackages: Set<String> = emptySet(),
    private val today: () -> LocalDate = { LocalDate.now() },
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

    private class DailyUsage {
        var totalMs = 0L

        /** Daily total at the last daily reminder, or null if none was shown today. */
        var lastReminderAtMs: Long? = null

        /**
         * Total seeded from the system's usage data while no reminder was recorded. Reminder points at or below it
         * count as already shown, so a service restart doesn't repeat a reminder the user has likely seen.
         */
        var seededMs: Long? = null

        var remindWhenSnoozeEnds = false
    }

    private val sessions = HashMap<String, Session>()
    private val daily = HashMap<String, DailyUsage>()
    private val snoozedUntil = HashMap<String, Long>()
    private var day: LocalDate = today()

    /** Monotonic time up to which the active package's time has been added to its daily total. */
    private var dailyCountedUntil = 0L

    /** Package currently being counted, or null when away. */
    var activePackage: String? = null
        private set

    var ignoredPackages: Set<String> = ignoredPackages
        set(value) {
            field = value
            if (activePackage in value) onForeground(null)
            value.forEach {
                sessions.remove(it)
                daily.remove(it)
            }
        }

    /** Returns a reminder if one is due right now for [packageName]. */
    fun onForeground(packageName: String?): Reminder? {
        val now = clock.nowMillis()
        countDaily(now)
        val pkg = packageName?.takeUnless { it in ignoredPackages }
        if (pkg != activePackage) {
            activePackage?.let { pause(it, now) }
            pkg?.let { resume(it, now) }
            activePackage = pkg
        }
        prune(now)
        return pkg?.let { evaluate(it, now) }
    }

    /**
     * Replaces today's totals with [totalsMs] (package to foreground ms since midnight), e.g. read from the system's
     * usage data when the service starts. Packages missing from the map keep their in-memory total.
     */
    fun seedDailyTotals(totalsMs: Map<String, Long>) {
        val now = clock.nowMillis()
        countDaily(now)
        totalsMs.forEach { (pkg, total) ->
            if (pkg in ignoredPackages) return@forEach
            val usage = daily.getOrPut(pkg, ::DailyUsage)
            usage.totalMs = total
            if (usage.lastReminderAtMs == null) usage.seededMs = total
        }
    }

    /** Suppress reminders for [packageName] for [Settings.snoozeMinutes]. */
    fun snooze(packageName: String) {
        snoozedUntil[packageName] = clock.nowMillis() + settings.snoozeMinutes * MINUTE_MS
        // Only reminder types that already fired come back when the snooze ends.
        sessions[packageName]?.takeIf { it.lastReminderAtMs != null }?.remindWhenSnoozeEnds = true
        daily[packageName]?.takeIf { it.lastReminderAtMs != null }?.remindWhenSnoozeEnds = true
    }

    /** Current session length for [packageName], 0 if there is no live session. */
    fun sessionMillis(packageName: String): Long =
        sessions[packageName]?.elapsed(clock.nowMillis()) ?: 0L

    /** Foreground time today for [packageName], including the running poll interval. */
    fun dailyMillis(packageName: String): Long {
        val now = clock.nowMillis()
        val running = if (packageName == activePackage && today() == day) now - dailyCountedUntil else 0L
        return (daily[packageName]?.totalMs ?: 0L) + running
    }

    private val graceMs get() = settings.gracePeriodSeconds * 1000L

    /** Adds the active package's time since the last call to its daily total, resetting totals at midnight. */
    private fun countDaily(now: Long) {
        activePackage?.let { pkg -> daily.getOrPut(pkg, ::DailyUsage).totalMs += now - dailyCountedUntil }
        dailyCountedUntil = now
        val currentDay = today()
        if (currentDay != day) {
            // The few seconds since the last poll go to the old day; at a 5 s poll that's negligible.
            daily.clear()
            day = currentDay
        }
    }

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
        if (snoozedUntil[pkg]?.let { now < it } == true) return null
        // Evaluate both so each records its reminder; when both are due at once, show the daily one.
        val continuous = if (settings.isContinuousOn(pkg)) evaluateContinuous(pkg, now) else null
        val daily = if (settings.isDailyOn(pkg)) evaluateDaily(pkg) else null
        return daily ?: continuous
    }

    private fun evaluateContinuous(pkg: String, now: Long): Reminder? {
        val session = sessions[pkg] ?: return null
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
        return Reminder(pkg, elapsed / MINUTE_MS, ReminderKind.Continuous)
    }

    private fun evaluateDaily(pkg: String): Reminder? {
        val usage = daily[pkg] ?: return null
        val total = usage.totalMs
        val limitMs = settings.dailyLimitMinutesFor(pkg) * MINUTE_MS
        val repeatMs = settings.dailyRepeatIntervalMinutes?.let { it * MINUTE_MS }

        usage.seededMs?.let { seeded ->
            usage.seededMs = null
            if (usage.lastReminderAtMs == null && seeded >= limitMs) {
                usage.lastReminderAtMs = if (repeatMs == null) limitMs else limitMs + (seeded - limitMs) / repeatMs * repeatMs
            }
        }

        val last = usage.lastReminderAtMs
        val due = when {
            usage.remindWhenSnoozeEnds -> true
            // A limit raised past the last reminder starts over at the new limit.
            last == null || last < limitMs -> total >= limitMs
            repeatMs == null -> false
            else -> total >= last + repeatMs
        }
        if (!due) return null

        usage.remindWhenSnoozeEnds = false
        usage.lastReminderAtMs = total
        return Reminder(pkg, total / MINUTE_MS, ReminderKind.Daily)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
