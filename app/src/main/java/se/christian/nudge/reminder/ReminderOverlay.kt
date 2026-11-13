package se.christian.nudge.reminder

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import se.christian.nudge.history.ReminderEnd
import se.christian.nudge.ui.theme.NudgeTheme

/**
 * Shows the reminder pill in a TYPE_APPLICATION_OVERLAY window at the top of the screen.
 * The window wraps the pill and isn't focusable, so touches elsewhere reach the app underneath.
 */
class ReminderOverlay<T>(
    context: Context,
    private val owner: T,
    private val actions: ReminderActions,
) where T : LifecycleOwner, T : SavedStateRegistryOwner {

    private val windowContext: Context = context.applicationContext.createWindowContext(
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        null,
    )
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var current: ShownPill? = null

    /** A pill on screen and who to tell how it ended (once). */
    private class ShownPill(val view: ComposeView, private val onEnd: (ReminderEnd) -> Unit) {
        private var ended = false

        fun end(reason: ReminderEnd) {
            if (!ended) {
                ended = true
                onEnd(reason)
            }
        }
    }

    /**
     * Shows [content], replacing any pill on screen. Returns false if the overlay can't be shown.
     * [onEnd] is told how the pill ended: by the pill itself, or [dismissNow].
     */
    fun show(content: PillContent, onEnd: (ReminderEnd) -> Unit = {}): Boolean {
        if (!Settings.canDrawOverlays(windowContext)) return false
        dismissNow(ReminderEnd.REPLACED)

        val params = layoutParams()
        val view = ComposeView(windowContext)
        val shown = ShownPill(view, onEnd)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setContent {
            NudgeTheme {
                ReminderPill(
                    content = content,
                    onSnooze = { actions.snooze(content.packageName) },
                    onTurnOff = { actions.turnOff(content.packageName) },
                    onOpenSettings = { actions.openSettings(content.packageName, content.appLabel) },
                    onExpandedChange = { expanded -> resize(view, params, expanded) },
                    // Remove after the current frame, not from inside the composition being disposed.
                    onDismissed = { reason ->
                        shown.end(reason)
                        mainHandler.post { remove(shown) }
                    },
                )
            }
        }

        return try {
            if (!view.isAttachedToWindow) windowManager.addView(view, params)
            current = shown
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't add the reminder overlay", e)
            false
        }
    }

    /** Removes the pill without animating, e.g. when the screen turns off. */
    fun dismissNow(reason: ReminderEnd = ReminderEnd.SCREEN_OFF) {
        current?.let {
            it.end(reason)
            remove(it)
        }
    }

    private fun remove(shown: ShownPill) {
        if (current === shown) current = null
        val view = shown.view
        // parent is set as soon as addView returns; isAttachedToWindow only after the first traversal.
        if (!view.isAttachedToWindow && view.parent == null) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't remove the reminder overlay", e)
        }
    }

    /**
     * Gives the window its expanded width in one step. Letting a WRAP_CONTENT overlay follow the size animation
     * relayouts (and re-centers) the window every frame, which lags behind the content and clips it.
     */
    private fun resize(view: ComposeView, params: WindowManager.LayoutParams, expanded: Boolean) {
        params.width = if (expanded) {
            (ExpandedWindowWidth.value * windowContext.resources.displayMetrics.density).toInt()
        } else {
            WindowManager.LayoutParams.WRAP_CONTENT
        }
        try {
            if (view.parent != null) windowManager.updateViewLayout(view, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't resize the reminder overlay", e)
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // Position explicitly below the status bar instead of letting the system fit insets.
        fitInsetsTypes = 0
        y = windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        title = "Nudge reminder"
    }

    private companion object {
        const val TAG = "ReminderOverlay"
    }
}
