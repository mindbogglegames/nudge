package se.christian.nudge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import se.christian.nudge.NudgeApp
import se.christian.nudge.Permissions

/** Restarts monitoring after a reboot or an app update, if reminders are on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as NudgeApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                val settings = app.settingsRepository.settings.first()
                if (settings.remindersEnabled && Permissions.hasUsageAccess(app)) {
                    UsageMonitorService.start(app)
                }
            } catch (e: IllegalStateException) {
                Log.w("BootReceiver", "Couldn't start monitoring", e)
            } finally {
                pending.finish()
            }
        }
    }
}
