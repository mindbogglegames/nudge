package se.christian.nudge.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import se.christian.nudge.NudgeApp
import se.christian.nudge.history.ReminderEnd

/** Handles the "Snooze" and "Turn off for this app" actions on the fallback notification. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as NudgeApp
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        app.notifier.cancelReminder()
        val historyId = intent.getLongExtra(EXTRA_HISTORY_ID, NO_ID)
        when (intent.action) {
            ACTION_SNOOZE -> {
                app.reminderActions.snooze(packageName)
                if (historyId != NO_ID) app.reminderLog.setEnd(historyId, ReminderEnd.SNOOZED)
            }
            ACTION_TURN_OFF -> {
                if (historyId != NO_ID) app.reminderLog.setEnd(historyId, ReminderEnd.TURNED_OFF)
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
        const val EXTRA_HISTORY_ID = "history_id"
        private const val NO_ID = -1L
    }
}
