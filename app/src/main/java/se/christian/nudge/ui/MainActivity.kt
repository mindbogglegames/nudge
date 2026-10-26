package se.christian.nudge.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import se.christian.nudge.NudgeApp
import se.christian.nudge.reminder.OverlayLifecycleOwner
import se.christian.nudge.reminder.PillContent
import se.christian.nudge.reminder.ReminderOverlay
import se.christian.nudge.service.UsageMonitorService
import se.christian.nudge.session.reminderText
import se.christian.nudge.ui.theme.NudgeTheme

/** Tracks whether [MainActivity] is resumed, to detect a blocked launch from the pill. */
object AppVisibility {
    @Volatile
    var mainActivityResumed = false
        internal set
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val app get() = application as NudgeApp
    private var testOverlay: ReminderOverlay<OverlayLifecycleOwner>? = null

    // The activity's own lifecycle stops when the user leaves, which would freeze the test pill on screen.
    private val overlayOwner by lazy { OverlayLifecycleOwner() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            NudgeTheme {
                NudgeRoot(viewModel = viewModel, onShowTestReminder = ::showTestReminder)
            }
        }

        // The service runs exactly when reminders are on and usage access is granted. Starting it from here
        // (while visible) satisfies Android's foreground service start restrictions.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.settings.filterNotNull(), viewModel.permissions) { settings, permissions ->
                    settings.remindersEnabled to permissions.usageAccess
                }.distinctUntilChanged().collect { (enabled, usageAccess) ->
                    when {
                        enabled && usageAccess -> UsageMonitorService.start(this@MainActivity)
                        !enabled -> UsageMonitorService.stop(this@MainActivity)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.mainActivityResumed = true
        viewModel.refreshPermissions()
        viewModel.refreshApps()
    }

    override fun onPause() {
        AppVisibility.mainActivityResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        testOverlay?.dismissNow()
        if (testOverlay != null) overlayOwner.destroy()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_PACKAGE)?.let(viewModel::openAppSheet)
    }

    /** Shows the pill for a sample app. Returns false if it fell back to a notification. */
    private suspend fun showTestReminder(): Boolean {
        val settings = viewModel.settings.value ?: return false
        val sample = SAMPLE_PACKAGES.firstNotNullOfOrNull { viewModel.appInfo(it) }
            ?: viewModel.apps.value?.firstOrNull()
            ?: viewModel.appInfo(packageName)
        val label = sample?.label ?: "Nudge"
        val content = PillContent(
            packageName = sample?.packageName ?: packageName,
            appLabel = label,
            icon = sample?.icon,
            text = reminderText(settings.defaultThresholdMinutes.toLong(), label),
            snoozeMinutes = settings.snoozeMinutes,
        )
        val overlay = testOverlay ?: ReminderOverlay(this, overlayOwner, app.reminderActions).also { testOverlay = it }
        if (overlay.show(content)) return true
        app.notifier.showReminder(content)
        return false
    }

    companion object {
        private const val EXTRA_PACKAGE = "se.christian.nudge.extra.PACKAGE"
        private val SAMPLE_PACKAGES = listOf("com.google.android.youtube", "com.android.chrome")

        /** Opens the app, optionally at [packageToOpen]'s settings sheet. */
        fun intent(context: Context, packageToOpen: String?): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (packageToOpen != null) putExtra(EXTRA_PACKAGE, packageToOpen) }
    }
}
