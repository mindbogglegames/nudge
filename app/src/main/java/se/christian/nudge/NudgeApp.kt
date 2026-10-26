package se.christian.nudge

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import se.christian.nudge.apps.AppInfoRepository
import se.christian.nudge.reminder.ReminderActions
import se.christian.nudge.reminder.ReminderNotifier
import se.christian.nudge.settings.SettingsRepository

/** Holds the process-wide singletons (no DI framework by design). */
class NudgeApp : Application() {
    /** For work that must outlive a screen or receiver, e.g. a DataStore write from a notification action. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository by lazy { SettingsRepository(this) }
    val appInfo by lazy { AppInfoRepository(this) }
    val notifier by lazy { ReminderNotifier(this) }
    val reminderActions by lazy { ReminderActions(this) }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
    }
}
