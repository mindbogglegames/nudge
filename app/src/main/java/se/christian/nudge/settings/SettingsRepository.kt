package se.christian.nudge.settings

import android.content.Context
import androidx.datastore.core.DataStore
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
    suspend fun setThresholdOverride(packageName: String, minutes: Int?) = store.edit {
        val overrides = it.thresholdOverrides()
        val updated = if (minutes == null) overrides - packageName else overrides + (packageName to minutes.coerceIn(1, MAX_MINUTES))
        it[THRESHOLD_OVERRIDES] = Json.encodeToString(updated)
    }

    private fun Preferences.toSettings() = Settings(
        remindersEnabled = this[REMINDERS_ENABLED] ?: false,
        defaultThresholdMinutes = this[DEFAULT_THRESHOLD] ?: 15,
        repeatIntervalMinutes = this[REPEAT_INTERVAL],
        gracePeriodSeconds = this[GRACE_PERIOD] ?: 120,
        snoozeMinutes = this[SNOOZE] ?: 10,
        enabledPackages = this[ENABLED_PACKAGES].orEmpty(),
        thresholdOverrides = thresholdOverrides(),
    )

    private fun Preferences.thresholdOverrides(): Map<String, Int> =
        this[THRESHOLD_OVERRIDES]?.let { json ->
            runCatching { Json.decodeFromString<Map<String, Int>>(json) }.getOrNull()
        }.orEmpty()

    companion object {
        const val MAX_MINUTES = 24 * 60

        private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        private val DEFAULT_THRESHOLD = intPreferencesKey("default_threshold_minutes")
        private val REPEAT_INTERVAL = intPreferencesKey("repeat_interval_minutes")
        private val GRACE_PERIOD = intPreferencesKey("grace_period_seconds")
        private val SNOOZE = intPreferencesKey("snooze_minutes")
        private val ENABLED_PACKAGES = stringSetPreferencesKey("enabled_packages")
        private val THRESHOLD_OVERRIDES = stringPreferencesKey("threshold_overrides")
    }
}
