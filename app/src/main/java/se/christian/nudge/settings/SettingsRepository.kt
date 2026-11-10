package se.christian.nudge.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<Settings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    val onboardingCompleted: Flow<Boolean> =
        store.data.map { it[ONBOARDING_COMPLETED] ?: false }.distinctUntilChanged()

    suspend fun setOnboardingCompleted() = store.edit { it[ONBOARDING_COMPLETED] = true }

    suspend fun setRemindersEnabled(enabled: Boolean) = store.edit { it[REMINDERS_ENABLED] = enabled }

    suspend fun setDefaultThresholdMinutes(minutes: Int) =
        store.edit { it[DEFAULT_THRESHOLD] = minutes.coerceIn(1, MAX_MINUTES) }

    suspend fun setRepeatIntervalMinutes(minutes: Int?) = store.edit {
        if (minutes == null) it.remove(REPEAT_INTERVAL) else it[REPEAT_INTERVAL] = minutes.coerceIn(1, MAX_MINUTES)
    }

    suspend fun setGracePeriodSeconds(seconds: Int) = store.edit { it[GRACE_PERIOD] = seconds.coerceAtLeast(0) }

    suspend fun setSnoozeMinutes(minutes: Int) = store.edit { it[SNOOZE] = minutes.coerceIn(1, MAX_MINUTES) }

    suspend fun setAppEnabled(packageName: String, enabled: Boolean) = store.edit {
        val current = it[ENABLED_PACKAGES].orEmpty()
        it[ENABLED_PACKAGES] = if (enabled) current + packageName else current - packageName
    }

    /** null removes the override, so the app follows the default threshold. */
    suspend fun setThresholdOverride(packageName: String, minutes: Int?) =
        store.edit { it.setOverride(THRESHOLD_OVERRIDES, packageName, minutes) }

    suspend fun setContinuousRemindersEnabled(enabled: Boolean) = store.edit { it[CONTINUOUS_ENABLED] = enabled }

    suspend fun setDailyRemindersEnabled(enabled: Boolean) = store.edit { it[DAILY_ENABLED] = enabled }

    suspend fun setDefaultDailyLimitMinutes(minutes: Int) =
        store.edit { it[DEFAULT_DAILY_LIMIT] = minutes.coerceIn(1, MAX_MINUTES) }

    /** null means remind only once a day. */
    suspend fun setDailyRepeatIntervalMinutes(minutes: Int?) =
        store.edit { it[DAILY_REPEAT] = minutes?.coerceIn(1, MAX_MINUTES) ?: DAILY_REPEAT_ONCE }

    /** null removes the override, so the app follows the default daily limit. */
    suspend fun setDailyLimitOverride(packageName: String, minutes: Int?) =
        store.edit { it.setOverride(DAILY_LIMIT_OVERRIDES, packageName, minutes) }

    /** Turns continuous use reminders on or off for one app, leaving its other settings alone. */
    suspend fun setAppContinuousEnabled(packageName: String, enabled: Boolean) =
        store.edit { it.toggle(CONTINUOUS_OFF_PACKAGES, packageName, off = !enabled) }

    /** Turns daily total reminders on or off for one app, leaving its other settings alone. */
    suspend fun setAppDailyEnabled(packageName: String, enabled: Boolean) =
        store.edit { it.toggle(DAILY_OFF_PACKAGES, packageName, off = !enabled) }

    /** Sets the app's continuous use reminder message; null or blank goes back to the built-in one. */
    suspend fun setContinuousMessage(packageName: String, message: String?) =
        store.edit { it.setMessage(CONTINUOUS_MESSAGES, packageName, message) }

    /** Sets the app's daily total reminder message; null or blank goes back to the built-in one. */
    suspend fun setDailyMessage(packageName: String, message: String?) =
        store.edit { it.setMessage(DAILY_MESSAGES, packageName, message) }

    private fun MutablePreferences.setMessage(key: Preferences.Key<String>, packageName: String, message: String?) {
        val messages = messages(key)
        val text = message?.trim()?.take(MAX_MESSAGE_LENGTH)
        val updated = if (text.isNullOrEmpty()) messages - packageName else messages + (packageName to text)
        this[key] = Json.encodeToString(updated)
    }

    private fun MutablePreferences.toggle(key: Preferences.Key<Set<String>>, packageName: String, off: Boolean) {
        val current = this[key].orEmpty()
        this[key] = if (off) current + packageName else current - packageName
    }

    private fun MutablePreferences.setOverride(key: Preferences.Key<String>, packageName: String, minutes: Int?) {
        val overrides = overrides(key)
        val updated = if (minutes == null) overrides - packageName else overrides + (packageName to minutes.coerceIn(1, MAX_MINUTES))
        this[key] = Json.encodeToString(updated)
    }

    private fun Preferences.toSettings() = Settings(
        remindersEnabled = this[REMINDERS_ENABLED] ?: false,
        defaultThresholdMinutes = this[DEFAULT_THRESHOLD] ?: 15,
        repeatIntervalMinutes = this[REPEAT_INTERVAL],
        gracePeriodSeconds = this[GRACE_PERIOD] ?: 120,
        snoozeMinutes = this[SNOOZE] ?: 10,
        continuousRemindersEnabled = this[CONTINUOUS_ENABLED] ?: true,
        dailyRemindersEnabled = this[DAILY_ENABLED] ?: false,
        defaultDailyLimitMinutes = this[DEFAULT_DAILY_LIMIT] ?: 60,
        dailyRepeatIntervalMinutes = when (val repeat = this[DAILY_REPEAT]) {
            null -> 15
            DAILY_REPEAT_ONCE -> null
            else -> repeat
        },
        enabledPackages = this[ENABLED_PACKAGES].orEmpty(),
        thresholdOverrides = overrides(THRESHOLD_OVERRIDES),
        dailyLimitOverrides = overrides(DAILY_LIMIT_OVERRIDES),
        continuousOffPackages = this[CONTINUOUS_OFF_PACKAGES].orEmpty(),
        dailyOffPackages = this[DAILY_OFF_PACKAGES].orEmpty(),
        continuousMessages = messages(CONTINUOUS_MESSAGES),
        dailyMessages = messages(DAILY_MESSAGES),
    )

    private fun Preferences.overrides(key: Preferences.Key<String>): Map<String, Int> =
        this[key]?.let { json ->
            runCatching { Json.decodeFromString<Map<String, Int>>(json) }.getOrNull()
        }.orEmpty()

    private fun Preferences.messages(key: Preferences.Key<String>): Map<String, String> =
        this[key]?.let { json ->
            runCatching { Json.decodeFromString<Map<String, String>>(json) }.getOrNull()
        }.orEmpty()

    companion object {
        const val MAX_MINUTES = 24 * 60

        /** Long enough for a sentence; the collapsed pill shows one line and ellipsizes the rest. */
        const val MAX_MESSAGE_LENGTH = 80

        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        private val DEFAULT_THRESHOLD = intPreferencesKey("default_threshold_minutes")
        private val REPEAT_INTERVAL = intPreferencesKey("repeat_interval_minutes")
        private val GRACE_PERIOD = intPreferencesKey("grace_period_seconds")
        private val SNOOZE = intPreferencesKey("snooze_minutes")
        private val ENABLED_PACKAGES = stringSetPreferencesKey("enabled_packages")
        private val THRESHOLD_OVERRIDES = stringPreferencesKey("threshold_overrides")
        private val CONTINUOUS_ENABLED = booleanPreferencesKey("continuous_reminders_enabled")
        private val DAILY_ENABLED = booleanPreferencesKey("daily_reminders_enabled")
        private val DEFAULT_DAILY_LIMIT = intPreferencesKey("default_daily_limit_minutes")
        private val DAILY_REPEAT = intPreferencesKey("daily_repeat_interval_minutes")
        private val DAILY_LIMIT_OVERRIDES = stringPreferencesKey("daily_limit_overrides")
        private val CONTINUOUS_OFF_PACKAGES = stringSetPreferencesKey("continuous_off_packages")
        private val DAILY_OFF_PACKAGES = stringSetPreferencesKey("daily_off_packages")
        private val CONTINUOUS_MESSAGES = stringPreferencesKey("continuous_messages")
        private val DAILY_MESSAGES = stringPreferencesKey("daily_messages")

        /** Stored for "only once a day"; an absent key means the default. */
        private const val DAILY_REPEAT_ONCE = 0
    }
}
