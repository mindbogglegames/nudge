package se.christian.nudge.reminder

import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import se.christian.nudge.NudgeApp
import se.christian.nudge.ui.AppVisibility
import se.christian.nudge.ui.MainActivity

/** What the pill and the fallback notification show. */
data class PillContent(
    val packageName: String,
    val appLabel: String,
    val icon: Bitmap?,
    val text: String,
    val snoozeMinutes: Int,
)

/** In-process events from reminder UI (pill, notification actions) to the running service. */
object ReminderEvents {
    private val snoozeRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snoozes: SharedFlow<String> = snoozeRequests.asSharedFlow()

    fun snooze(packageName: String) {
        snoozeRequests.tryEmit(packageName)
    }
}

/** The pill's actions, shared by the service and the test reminder in the activity. */
class ReminderActions(private val app: NudgeApp) {

    fun snooze(packageName: String) = ReminderEvents.snooze(packageName)

    fun turnOff(packageName: String) {
        app.appScope.launch { app.settingsRepository.setAppEnabled(packageName, false) }
    }

    /**
     * Opens Nudge at the app's settings sheet. Holding SYSTEM_ALERT_WINDOW with a visible overlay exempts us
     * from background activity launch restrictions; if the launch is blocked anyway, post a notification.
     */
    fun openSettings(packageName: String, appLabel: String) {
        val intent = MainActivity.intent(app, packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't open settings from the pill", e)
        }
        app.appScope.launch {
            delay(LAUNCH_CHECK_DELAY_MS)
            if (!AppVisibility.mainActivityResumed) {
                Log.i(TAG, "Settings launch was blocked; falling back to a notification")
                app.notifier.showOpenSettings(packageName, appLabel)
            }
        }
    }

    private companion object {
        const val TAG = "ReminderActions"
        const val LAUNCH_CHECK_DELAY_MS = 2_000L
    }
}
