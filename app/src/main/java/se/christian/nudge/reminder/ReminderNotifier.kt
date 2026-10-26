package se.christian.nudge.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import se.christian.nudge.R
import se.christian.nudge.ui.MainActivity

/** All notifications: the service's persistent one, the reminder fallback, and status alerts. */
class ReminderNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MONITORING, "Monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while screen time reminders are on"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminders shown when the pill can't be displayed"
                },
                NotificationChannel(CHANNEL_STATUS, "Status", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When reminders stop working, and why"
                },
            ),
        )
    }

    fun monitoringNotification(): Notification =
        Notification.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle("Screen time reminders are on")
            .setContentIntent(openAppIntent(packageToOpen = null))
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    fun showReminder(content: PillContent) {
        val notification = Notification.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle(content.text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent(content.packageName))
            .setAutoCancel(true)
            .setTimeoutAfter(REMINDER_TIMEOUT_MS)
            .addAction(actionButton("Snooze", ReminderActionReceiver.ACTION_SNOOZE, content.packageName))
            .addAction(actionButton("Turn off for this app", ReminderActionReceiver.ACTION_TURN_OFF, content.packageName))
            .apply { content.icon?.let { setLargeIcon(it) } }
            .build()
        manager.notify(ID_REMINDER, notification)
    }

    fun cancelReminder() = manager.cancel(ID_REMINDER)

    fun showUsageAccessRevoked() {
        val notification = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle("Screen time reminders stopped")
            .setContentText("Usage access was turned off. Open Nudge to turn it back on.")
            .setStyle(Notification.BigTextStyle().bigText("Usage access was turned off. Open Nudge to turn it back on."))
            .setContentIntent(openAppIntent(packageToOpen = null))
            .setAutoCancel(true)
            .build()
        manager.notify(ID_STATUS, notification)
    }

    /** Used when Android blocks opening the settings from the pill (background activity launch). */
    fun showOpenSettings(packageName: String, appLabel: String) {
        val notification = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle("Reminder settings for $appLabel")
            .setContentText("Tap to open")
            .setContentIntent(openAppIntent(packageName))
            .setAutoCancel(true)
            .setTimeoutAfter(REMINDER_TIMEOUT_MS)
            .build()
        manager.notify(ID_OPEN_SETTINGS, notification)
    }

    private fun openAppIntent(packageToOpen: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            packageToOpen?.hashCode() ?: 0,
            MainActivity.intent(context, packageToOpen),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun actionButton(title: String, action: String, packageName: String): Notification.Action {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderActionReceiver.EXTRA_PACKAGE, packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_nudge), title, pendingIntent)
            .build()
    }

    companion object {
        const val ID_MONITORING = 1
        private const val ID_REMINDER = 2
        private const val ID_STATUS = 3
        private const val ID_OPEN_SETTINGS = 4

        private const val CHANNEL_MONITORING = "monitoring"
        private const val CHANNEL_REMINDERS = "reminders"
        private const val CHANNEL_STATUS = "status"

        private const val REMINDER_TIMEOUT_MS = 60_000L
    }
}
