package se.christian.nudge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.christian.nudge.NudgeApp
import se.christian.nudge.PermissionState
import se.christian.nudge.Permissions
import se.christian.nudge.apps.AppInfo
import se.christian.nudge.service.DailyUsageReader
import se.christian.nudge.settings.Settings

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NudgeApp
    private val repository = app.settingsRepository

    val settings: StateFlow<Settings?> =
        repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val onboardingCompleted: StateFlow<Boolean?> =
        repository.onboardingCompleted.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _apps = MutableStateFlow<List<AppInfo>?>(null)
    val apps: StateFlow<List<AppInfo>?> = _apps.asStateFlow()

    private val _permissions = MutableStateFlow(Permissions.state(application))
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _todayUsage = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Foreground ms today per package, from the system's usage data. */
    val todayUsage: StateFlow<Map<String, Long>> = _todayUsage.asStateFlow()

    /** Package whose settings sheet is open. */
    private val _sheetPackage = MutableStateFlow<String?>(null)
    val sheetPackage: StateFlow<String?> = _sheetPackage.asStateFlow()

    fun refreshPermissions() {
        _permissions.value = Permissions.state(app)
    }

    fun refreshApps() {
        viewModelScope.launch { _apps.value = app.appInfo.launchableApps() }
    }

    suspend fun appInfo(packageName: String): AppInfo? =
        _apps.value?.firstOrNull { it.packageName == packageName } ?: app.appInfo.get(packageName)

    fun openAppSheet(packageName: String?) {
        _sheetPackage.value = packageName
        if (packageName != null) refreshTodayUsage()
    }

    fun completeOnboarding() = launch { repository.setOnboardingCompleted() }
    fun setRemindersEnabled(enabled: Boolean) = launch { repository.setRemindersEnabled(enabled) }
    fun setDefaultThreshold(minutes: Int) = launch { repository.setDefaultThresholdMinutes(minutes) }
    fun setRepeatInterval(minutes: Int?) = launch { repository.setRepeatIntervalMinutes(minutes) }
    fun setGracePeriod(seconds: Int) = launch { repository.setGracePeriodSeconds(seconds) }
    fun setSnooze(minutes: Int) = launch { repository.setSnoozeMinutes(minutes) }
    fun setAppEnabled(packageName: String, enabled: Boolean) = launch { repository.setAppEnabled(packageName, enabled) }
    fun setThresholdOverride(packageName: String, minutes: Int?) =
        launch { repository.setThresholdOverride(packageName, minutes) }
    fun setContinuousEnabled(enabled: Boolean) = launch { repository.setContinuousRemindersEnabled(enabled) }
    fun setDailyEnabled(enabled: Boolean) = launch { repository.setDailyRemindersEnabled(enabled) }
    fun setDefaultDailyLimit(minutes: Int) = launch { repository.setDefaultDailyLimitMinutes(minutes) }
    fun setDailyRepeatInterval(minutes: Int?) = launch { repository.setDailyRepeatIntervalMinutes(minutes) }
    fun setDailyLimitOverride(packageName: String, minutes: Int?) =
        launch { repository.setDailyLimitOverride(packageName, minutes) }
    fun setAppContinuousEnabled(packageName: String, enabled: Boolean) =
        launch { repository.setAppContinuousEnabled(packageName, enabled) }
    fun setAppDailyEnabled(packageName: String, enabled: Boolean) =
        launch { repository.setAppDailyEnabled(packageName, enabled) }
    fun setContinuousMessage(packageName: String, message: String?) =
        launch { repository.setContinuousMessage(packageName, message) }
    fun setDailyMessage(packageName: String, message: String?) =
        launch { repository.setDailyMessage(packageName, message) }

    /** Re-reads today's foreground time per package (shown in the app sheet). */
    fun refreshTodayUsage() {
        viewModelScope.launch {
            _todayUsage.value = withContext(Dispatchers.IO) { DailyUsageReader(app).todayTotals() }
        }
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
