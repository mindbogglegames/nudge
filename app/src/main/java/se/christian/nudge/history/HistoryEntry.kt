package se.christian.nudge.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import se.christian.nudge.session.ReminderKind

/** One line in the reminder history. Pure Kotlin so the rules in [HistoryRules] run on the JVM. */
@Serializable
sealed interface HistoryEntry {
    val id: Long

    /** Wall-clock time, epoch milliseconds. */
    val timeMs: Long
}

@Serializable
@SerialName("reminder")
data class ReminderEntry(
    override val id: Long,
    override val timeMs: Long,
    val packageName: String,
    val kind: ReminderKind,
    /** The text exactly as shown. */
    val text: String,
    val delivery: Delivery,
    val reason: DeliveryReason? = null,
    val test: Boolean = false,
    /** How the pill (or the notification, through its actions) ended; null until it does, or if unknown. */
    val end: ReminderEnd? = null,
) : HistoryEntry

@Serializable
@SerialName("started")
data class MonitoringStarted(override val id: Long, override val timeMs: Long) : HistoryEntry

@Serializable
@SerialName("stopped")
data class MonitoringStopped(override val id: Long, override val timeMs: Long, val reason: StopReason) : HistoryEntry

enum class Delivery { PILL, NOTIFICATION, NOT_SHOWN }

/** Why a reminder wasn't shown as the pill. */
enum class DeliveryReason { OVERLAY_PERMISSION_OFF, PILL_FAILED, PILL_BLOCKED_NOTIFICATIONS_OFF }

enum class ReminderEnd { TIMED_OUT, DISMISSED, SNOOZED, TURNED_OFF, OPENED_SETTINGS, REPLACED, SCREEN_OFF }

enum class StopReason {
    /** "Use reminders" was turned off. */
    TURNED_OFF,
    USAGE_ACCESS_REVOKED,

    /** Android refused to start the foreground service. */
    START_NOT_ALLOWED,

    /** The process ended without a clean stop; the phone most likely closed it. */
    KILLED,
    LOW_MEMORY,
    FORCE_STOPPED,
    CRASHED,
    UPDATED,

    /** Android restarts an app when one of its permissions is turned off. */
    PERMISSION_CHANGED,

    /** A clean stop without a known cause. */
    STOPPED,
}
