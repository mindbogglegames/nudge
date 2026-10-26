package se.christian.nudge.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.christian.nudge.NudgeApp
import se.christian.nudge.Permissions
import se.christian.nudge.reminder.PillContent
import se.christian.nudge.reminder.ReminderEvents
import se.christian.nudge.reminder.ReminderNotifier
import se.christian.nudge.reminder.ReminderOverlay
import se.christian.nudge.session.Reminder
import se.christian.nudge.session.SessionTracker
import se.christian.nudge.session.reminderText

/**
 * Foreground service that polls the foreground app every 5 s while the screen is on and unlocked,
 * feeds [SessionTracker] and shows reminders.
 */
class UsageMonitorService : LifecycleService(), SavedStateRegistryOwner {
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val app get() = application as NudgeApp
    private val tracker = SessionTracker(clock = { SystemClock.elapsedRealtime() })
    private lateinit var detector: ForegroundAppDetector
    private lateinit var overlay: ReminderOverlay<UsageMonitorService>
    private var pollJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    stopPolling()
                    tracker.onForeground(null)
                    overlay.dismissNow()
                }
                Intent.ACTION_USER_PRESENT -> startPolling()
                Intent.ACTION_SCREEN_ON -> if (!isKeyguardLocked()) startPolling()
            }
        }
    }

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        if (!enterForeground()) return

        detector = ForegroundAppDetector(this)
        overlay = ReminderOverlay(this, this, app.reminderActions)
        tracker.ignoredPackages = app.appInfo.ignoredPackages()

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        lifecycleScope.launch {
            app.settingsRepository.settings.collect { settings ->
                tracker.settings = settings
                if (!settings.remindersEnabled) stopSelf()
            }
        }
        lifecycleScope.launch {
            ReminderEvents.snoozes.collect { packageName -> tracker.snooze(packageName) }
        }

        val power = getSystemService(PowerManager::class.java)
        if (power.isInteractive && !isKeyguardLocked()) startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Every startForegroundService() call must be answered with startForeground().
        enterForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) {
            unregisterReceiver(screenReceiver)
            overlay.dismissNow()
        }
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        val notification = app.notifier.monitoringNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ReminderNotifier.ID_MONITORING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // Service types before API 34 don't include specialUse, and none is required there.
            startForeground(ReminderNotifier.ID_MONITORING, notification)
        }
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException, e.g. a sticky restart while the app is restricted.
        Log.w(TAG, "Not allowed to start in the foreground", e)
        stopSelf()
        false
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        // The default launcher may have changed while the screen was off.
        tracker.ignoredPackages = app.appInfo.ignoredPackages()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                poll()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun poll() {
        if (!Permissions.hasUsageAccess(this)) {
            Log.i(TAG, "Usage access revoked; stopping")
            app.notifier.showUsageAccessRevoked()
            stopPolling()
            stopSelf()
            return
        }
        val reminder = tracker.onForeground(detector.currentForegroundPackage()) ?: return
        showReminder(reminder)
    }

    private fun showReminder(reminder: Reminder) {
        lifecycleScope.launch {
            val info = app.appInfo.get(reminder.packageName)
            val label = info?.label ?: reminder.packageName
            val content = PillContent(
                packageName = reminder.packageName,
                appLabel = label,
                icon = info?.icon,
                text = reminderText(reminder.sessionMinutes, label),
                snoozeMinutes = tracker.settings.snoozeMinutes,
            )
            val shownAsPill = overlay.show(content)
            if (!shownAsPill) app.notifier.showReminder(content)
            Log.i(TAG, "Reminder for ${reminder.packageName} at ${reminder.sessionMinutes} min, pill=$shownAsPill")
        }
    }

    private fun isKeyguardLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    companion object {
        private const val TAG = "UsageMonitorService"
        private const val POLL_INTERVAL_MS = 5_000L

        /** Only call while the activity is in the foreground, or from the boot/update receiver. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, UsageMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageMonitorService::class.java))
        }
    }
}
