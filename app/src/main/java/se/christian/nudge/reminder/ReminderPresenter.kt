package se.christian.nudge.reminder

import android.provider.Settings
import se.christian.nudge.NudgeApp
import se.christian.nudge.history.Delivery
import se.christian.nudge.history.DeliveryOutcome
import se.christian.nudge.history.HistoryRules
import se.christian.nudge.session.ReminderKind

/** Shows a reminder as the pill, or as a notification when the pill can't appear, and records it in the history. */
class ReminderPresenter(private val app: NudgeApp) {

    fun present(overlay: ReminderOverlay<*>, content: PillContent, kind: ReminderKind, test: Boolean): DeliveryOutcome {
        val log = app.reminderLog
        val id = log.newId()
        val canDrawOverlays = Settings.canDrawOverlays(app)
        val pillAdded = canDrawOverlays && overlay.show(content) { end -> log.setEnd(id, end) }
        val outcome = HistoryRules.outcome(canDrawOverlays, pillAdded, app.notifier.canShowReminders())
        if (outcome.delivery == Delivery.NOTIFICATION) app.notifier.showReminder(content, id)
        log.reminder(content.packageName, kind, content.text, outcome, test, id)
        return outcome
    }
}
