package se.christian.nudge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.christian.nudge.NudgeApp
import se.christian.nudge.PermissionState
import se.christian.nudge.Permissions
import se.christian.nudge.apps.AppInfo
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

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
