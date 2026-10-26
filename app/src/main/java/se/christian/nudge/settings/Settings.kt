package se.christian.nudge.settings

/** User settings. Pure Kotlin so [se.christian.nudge.session.SessionTracker] can use it on the JVM. */
data class Settings(
    val remindersEnabled: Boolean = false,
    val defaultThresholdMinutes: Int = 15,
    /** null means "same as the app's threshold". */
    val repeatIntervalMinutes: Int? = null,
    val gracePeriodSeconds: Int = 120,
    val snoozeMinutes: Int = 10,
    /** Apps that get reminders. Every other app, including newly installed ones, is off. */
    val enabledPackages: Set<String> = emptySet(),
    val thresholdOverrides: Map<String, Int> = emptyMap(),
) {
    fun isAppEnabled(packageName: String): Boolean = packageName in enabledPackages

    fun thresholdMinutesFor(packageName: String): Int =
        thresholdOverrides[packageName] ?: defaultThresholdMinutes

    fun repeatMinutesFor(packageName: String): Int =
        repeatIntervalMinutes ?: thresholdMinutesFor(packageName)
}
