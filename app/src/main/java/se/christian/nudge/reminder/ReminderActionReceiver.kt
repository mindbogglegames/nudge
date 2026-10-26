package se.christian.nudge.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import se.christian.nudge.NudgeApp

/** Handles the "Snooze" and "Turn off for this app" actions on the fallback notification. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NudgeApp
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        app.notifier.cancelReminder()
        when (intent.action) {
            ACTION_SNOOZE -> app.reminderActions.snooze(packageName)
            ACTION_TURN_OFF -> {
                val pending = goAsync()
                app.appScope.launch {
                    try {
                        app.settingsRepository.setAppEnabled(packageName, false)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "se.christian.nudge.action.SNOOZE"
        const val ACTION_TURN_OFF = "se.christian.nudge.action.TURN_OFF"
        const val EXTRA_PACKAGE = "package"
    }
}
