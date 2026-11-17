package se.christian.nudge.history

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import se.christian.nudge.session.ReminderKind
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * Today's reminder history in a JSON file in filesDir, plus the service's heartbeat in a second small file.
 * Every read and write runs in order on one background thread; [entries] updates live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderLog(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val historyFile = File(appContext.filesDir, "reminder_history.json")
    private val heartbeatFile = File(appContext.filesDir, "monitor_heartbeat.json")
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicLong(System.currentTimeMillis())
    private var lastHeartbeatWriteMs = 0L

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Today's entries in time order. Filter with [HistoryRules.pruneToToday] when showing them after midnight. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    init {
        // Queued first, so every later change applies to the loaded list.
        run { _entries.value = HistoryRules.pruneToToday(read(), startOfToday()) }
    }

    /** Records a reminder and returns its id, for [setEnd]. */
    fun reminder(
        packageName: String,
        kind: ReminderKind,
        text: String,
        outcome: DeliveryOutcome,
        test: Boolean,
        id: Long = newId(),
    ): Long {
        val entry = ReminderEntry(id, now(), packageName, kind, text, outcome.delivery, outcome.reason, test)
        change { it + entry }
        return id
    }

    fun newId(): Long = ids.incrementAndGet()

    /** How a reminder ended. Only the first report counts. */
    fun setEnd(id: Long, end: ReminderEnd) = change { entries ->
        entries.map { if (it is ReminderEntry && it.id == id && it.end == null) it.copy(end = end) else it }
    }

    /**
     * Logs the service starting (or Android refusing it, with [failed]), and a stop at the last heartbeat if the
     * previous run ended without one.
     */
    fun serviceStarted(failed: Boolean = false) = run {
        val previous = readHeartbeat()
        val nowMs = now()
        val exit = if (previous?.running == true) exitReasonSince(previous.lastAliveMs) else null
        val lastEntryMs = _entries.value.maxOfOrNull { it.timeMs }
        val added = HistoryRules.onServiceStart(previous, nowMs, exit, ::newId, startFailed = failed, lastEntryMs = lastEntryMs)
        writeHeartbeat(Heartbeat(running = !failed, lastAliveMs = nowMs))
        save(_entries.value + added)
    }

    /** Logs a clean stop; the next start won't report the phone closing Nudge. */
    fun serviceStopped(reason: StopReason) = run {
        val nowMs = now()
        writeHeartbeat(Heartbeat(running = false, lastAliveMs = nowMs))
        save(_entries.value + MonitoringStopped(newId(), nowMs, reason))
    }

    /** Called often while monitoring; writes about once a minute, or now with [force]. */
    fun heartbeat(force: Boolean = false) {
        val nowMs = now()
        if (!force && nowMs - lastHeartbeatWriteMs < HEARTBEAT_INTERVAL_MS) return
        lastHeartbeatWriteMs = nowMs
        run { writeHeartbeat(Heartbeat(running = true, lastAliveMs = nowMs)) }
    }

    fun clear() = change { emptyList() }

    private fun change(transform: (List<HistoryEntry>) -> List<HistoryEntry>) = run { save(transform(_entries.value)) }

    private fun save(entries: List<HistoryEntry>) {
        val pruned = HistoryRules.pruneToToday(entries, startOfToday())
        _entries.value = pruned
        write(historyFile, json.encodeToString(pruned))
    }

    private fun read(): List<HistoryEntry> = readText(historyFile)?.let { text ->
        try {
            json.decodeFromString<List<HistoryEntry>>(text)
        } catch (e: SerializationException) {
            Log.w(TAG, "Discarding unreadable reminder history", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Discarding unreadable reminder history", e)
            null
        }
    }.orEmpty()

    private fun readHeartbeat(): Heartbeat? = readText(heartbeatFile)?.let { text ->
        runCatching { json.decodeFromString<Heartbeat>(text) }.getOrNull()
    }

    private fun writeHeartbeat(heartbeat: Heartbeat) = write(heartbeatFile, json.encodeToString(heartbeat))

    /**
     * Why the process ended after [sinceMs] (the last sign of life), if Android recorded it. The list is newest
     * first, and a later run may also have ended before logging anything, so take the earliest exit after it.
     */
    private fun exitReasonSince(sinceMs: Long): StopReason? {
        val info = try {
            appContext.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(appContext.packageName, 0, EXIT_REASONS_TO_READ)
                .filter { it.timestamp >= sinceMs }
                .minByOrNull { it.timestamp }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't read process exit reasons", e)
            null
        } ?: return null
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                info.reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED -> StopReason.UPDATED
            else -> when (info.reason) {
                ApplicationExitInfo.REASON_USER_REQUESTED -> StopReason.FORCE_STOPPED
                ApplicationExitInfo.REASON_LOW_MEMORY -> StopReason.LOW_MEMORY
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> StopReason.PERMISSION_CHANGED
                ApplicationExitInfo.REASON_CRASH,
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR,
                -> StopReason.CRASHED
                else -> StopReason.KILLED
            }
        }
    }

    private fun readText(file: File): String? = try {
        if (file.exists()) file.readText() else null
    } catch (e: IOException) {
        Log.w(TAG, "Couldn't read ${file.name}", e)
        null
    }

    private fun write(file: File, text: String) {
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) file.writeText(text)
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't write ${file.name}", e)
        }
    }

    private fun run(block: () -> Unit) {
        scope.launch(serial) { block() }
    }

    private fun now() = System.currentTimeMillis()

    private fun startOfToday() = HistoryRules.startOfDay(now(), ZoneId.systemDefault())

    private companion object {
        const val TAG = "ReminderLog"
        const val HEARTBEAT_INTERVAL_MS = 60_000L
        const val EXIT_REASONS_TO_READ = 10
    }
}
