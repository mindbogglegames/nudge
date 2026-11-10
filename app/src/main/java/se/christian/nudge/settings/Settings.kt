package se.christian.nudge.settings

/** User settings. Pure Kotlin so [se.christian.nudge.session.SessionTracker] can use it on the JVM. */
data class Settings(
    val remindersEnabled: Boolean = false,
    /** Reminders after N minutes of continuous use (one session). */
    val continuousRemindersEnabled: Boolean = true,
    val defaultThresholdMinutes: Int = 15,
    /** null means "same as the app's threshold". */
    val repeatIntervalMinutes: Int? = null,
    val gracePeriodSeconds: Int = 120,
    val snoozeMinutes: Int = 10,
    /** Reminders when an app's total use today reaches a limit. Totals reset at local midnight. */
    val dailyRemindersEnabled: Boolean = false,
    val defaultDailyLimitMinutes: Int = 60,
    /** Repeat after the daily limit every N minutes of further use; null means only once a day. */
    val dailyRepeatIntervalMinutes: Int? = 15,
    /** Apps that get reminders. Every other app, including newly installed ones, is off. */
    val enabledPackages: Set<String> = emptySet(),
    val thresholdOverrides: Map<String, Int> = emptyMap(),
    val dailyLimitOverrides: Map<String, Int> = emptyMap(),
    /** Enabled apps that skip continuous use reminders while the global type is on. */
    val continuousOffPackages: Set<String> = emptySet(),
    /** Enabled apps that skip daily total reminders while the global type is on. */
    val dailyOffPackages: Set<String> = emptySet(),
) {
    fun isAppEnabled(packageName: String): Boolean = packageName in enabledPackages

    /** Continuous use reminders apply to [packageName] (ignores [isAppEnabled] and [remindersEnabled]). */
    fun isContinuousOn(packageName: String): Boolean =
        continuousRemindersEnabled && packageName !in continuousOffPackages

    /** Daily total reminders apply to [packageName] (ignores [isAppEnabled] and [remindersEnabled]). */
    fun isDailyOn(packageName: String): Boolean = dailyRemindersEnabled && packageName !in dailyOffPackages

    fun thresholdMinutesFor(packageName: String): Int =
        thresholdOverrides[packageName] ?: defaultThresholdMinutes

    fun repeatMinutesFor(packageName: String): Int =
        repeatIntervalMinutes ?: thresholdMinutesFor(packageName)

    fun dailyLimitMinutesFor(packageName: String): Int =
        dailyLimitOverrides[packageName] ?: defaultDailyLimitMinutes
}
