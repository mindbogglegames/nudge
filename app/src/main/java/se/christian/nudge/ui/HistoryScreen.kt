package se.christian.nudge.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.christian.nudge.R
import se.christian.nudge.apps.AppInfo
import se.christian.nudge.history.Delivery
import se.christian.nudge.history.DeliveryReason
import se.christian.nudge.history.HistoryEntry
import se.christian.nudge.history.HistoryRow
import se.christian.nudge.history.HistoryRules
import se.christian.nudge.history.MonitoringStarted
import se.christian.nudge.history.MonitoringStopped
import se.christian.nudge.history.ReminderEnd
import se.christian.nudge.history.ReminderEntry
import se.christian.nudge.history.StopReason
import se.christian.nudge.session.ReminderKind
import se.christian.nudge.session.formatMinutes
import java.util.Date

/** Today's reminders and how they were shown, plus when monitoring started and stopped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    loadApp: suspend (String) -> AppInfo?,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val counts = HistoryRules.counts(entries)
    val rows = remember(entries, problemsOnly) { HistoryRules.rows(entries, problemsOnly) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Reminder history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Clear history") },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    confirmClear = true
                                },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    text = "Today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    StatTile(counts.pill, "Shown as pill", Modifier.weight(1f))
                    StatTile(counts.notification, "As notification", Modifier.weight(1f))
                    if (counts.notShown > 0) {
                        StatTile(
                            count = counts.notShown,
                            label = "Not shown",
                            modifier = Modifier.weight(1f),
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        StatTile(0, "Not shown", Modifier.weight(1f))
                    }
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                ) {
                    FilterChip(
                        selected = !problemsOnly,
                        onClick = { problemsOnly = false },
                        label = { Text("All") },
                        leadingIcon = if (!problemsOnly) CheckIcon else null,
                    )
                    FilterChip(
                        selected = problemsOnly,
                        onClick = { problemsOnly = true },
                        label = { Text("Problems only") },
                        leadingIcon = if (problemsOnly) CheckIcon else null,
                    )
                }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        text = if (problemsOnly && entries.isNotEmpty()) "No problems today." else "No reminders yet today.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }
            items(rows, key = ::rowKey) { row ->
                when (row) {
                    is HistoryRow.Gap -> GapBanner(row.minutes)
                    is HistoryRow.Entry -> when (val entry = row.entry) {
                        is ReminderEntry -> ReminderRow(entry, loadApp)
                        is MonitoringStarted -> EventRow(entry.timeMs, "Monitoring started", started = true, problem = false)
                        is MonitoringStopped -> EventRow(
                            timeMs = entry.timeMs,
                            text = stopReasonText(entry.reason)?.let { "Monitoring stopped · $it" } ?: "Monitoring stopped",
                            started = false,
                            problem = HistoryRules.isProblem(entry),
                        )
                    }
                }
            }
            item {
                Text(
                    text = "The list clears at midnight and stays on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear today's history?") },
            text = { Text("Today's reminders and monitoring events will be removed from this list.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun rowKey(row: HistoryRow): String = when (row) {
    is HistoryRow.Entry -> "e${row.entry.id}"
    is HistoryRow.Gap -> "g${row.endTimeMs}"
}

private val CheckIcon: @Composable () -> Unit = {
    Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
}

@Composable
private fun StatTile(
    count: Int,
    label: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = container, contentColor = content, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
    }
}

@Composable
private fun ReminderRow(entry: ReminderEntry, loadApp: suspend (String) -> AppInfo?) {
    val app by produceState<AppInfo?>(null, entry.packageName) { value = loadApp(entry.packageName) }
    val time = formatTime(entry.timeMs)
    val notShown = entry.delivery == Delivery.NOT_SHOWN
    val details = buildList {
        add(time)
        val reason = entry.reason
        if (entry.delivery == Delivery.PILL || reason == null) {
            add(kindText(entry.kind))
            entry.end?.let { add(endText(it)) }
        } else {
            add(reasonText(reason))
        }
        if (entry.test) add("Test")
    }.joinToString(" · ")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        AppIcon(app)
        Column(Modifier.weight(1f)) {
            Text(entry.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = details,
                style = MaterialTheme.typography.bodyMedium,
                color = if (notShown) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DeliveryTag(entry.delivery)
    }
}

@Composable
private fun AppIcon(app: AppInfo?) {
    val icon = app?.icon
    if (icon != null) {
        val image = remember(icon) { icon.asImageBitmap() }
        Image(image, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(40.dp)) {}
    }
}

@Composable
private fun DeliveryTag(delivery: Delivery) {
    val colors = MaterialTheme.colorScheme
    val (label, container, content) = when (delivery) {
        Delivery.PILL -> Triple("Pill", colors.secondaryContainer, colors.onSecondaryContainer)
        Delivery.NOTIFICATION -> Triple("Notification", colors.tertiaryContainer, colors.onTertiaryContainer)
        Delivery.NOT_SHOWN -> Triple("Not shown", colors.errorContainer, colors.onErrorContainer)
    }
    val icon = when (delivery) {
        Delivery.PILL -> R.drawable.ic_check
        Delivery.NOTIFICATION -> null
        Delivery.NOT_SHOWN -> R.drawable.ic_close
    }
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(28.dp)
                .padding(horizontal = 10.dp),
        ) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EventRow(timeMs: Long, text: String, started: Boolean, problem: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(if (started) R.drawable.ic_play_circle else R.drawable.ic_stop_circle),
                contentDescription = null,
                tint = when {
                    started -> MaterialTheme.colorScheme.primary
                    problem -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = "${formatTime(timeMs)} · $text",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GapBanner(minutes: Long) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 80.dp, end = 24.dp),
    ) {
        Text(
            text = "Off for ${formatMinutes(minutes)}. No reminders could be shown.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun formatTime(timeMs: Long): String {
    val context = LocalContext.current
    val format = remember(context) { DateFormat.getTimeFormat(context) }
    return format.format(Date(timeMs))
}

private fun kindText(kind: ReminderKind) = when (kind) {
    ReminderKind.Continuous -> "Continuous use"
    ReminderKind.Daily -> "Daily total"
}

private fun reasonText(reason: DeliveryReason) = when (reason) {
    DeliveryReason.OVERLAY_PERMISSION_OFF -> "Display over other apps is off"
    DeliveryReason.PILL_FAILED -> "Pill couldn't be shown"
    DeliveryReason.PILL_BLOCKED_NOTIFICATIONS_OFF -> "Pill blocked and notifications are off"
}

private fun endText(end: ReminderEnd) = when (end) {
    ReminderEnd.TIMED_OUT -> "Timed out"
    ReminderEnd.DISMISSED -> "Dismissed"
    ReminderEnd.SNOOZED -> "Snoozed"
    ReminderEnd.TURNED_OFF -> "Turned off"
    ReminderEnd.OPENED_SETTINGS -> "Opened settings"
    ReminderEnd.REPLACED -> "Replaced"
    ReminderEnd.SCREEN_OFF -> "Screen off"
}

/** The part after "Monitoring stopped · ", or null for a plain stop. */
private fun stopReasonText(reason: StopReason): String? = when (reason) {
    StopReason.TURNED_OFF -> "reminders were turned off"
    StopReason.USAGE_ACCESS_REVOKED -> "usage access was turned off"
    StopReason.START_NOT_ALLOWED -> "Android didn't allow it to start"
    StopReason.KILLED -> "the phone closed Nudge in the background"
    StopReason.LOW_MEMORY -> "the phone closed Nudge to free memory"
    StopReason.FORCE_STOPPED -> "Nudge was force stopped"
    StopReason.CRASHED -> "Nudge stopped because of an error"
    StopReason.UPDATED -> "Nudge was updated"
    StopReason.PERMISSION_CHANGED -> "a permission was turned off"
    StopReason.STOPPED -> null
}

/** "3 reminders today" and the "1 not shown" part (null if none), for the main screen row. */
fun historySummary(entries: List<HistoryEntry>): Pair<String, String?> {
    val counts = HistoryRules.counts(entries)
    val total = when (counts.total) {
        0 -> "No reminders today"
        1 -> "1 reminder today"
        else -> "${counts.total} reminders today"
    }
    return total to counts.notShown.takeIf { it > 0 }?.let { "$it not shown" }
}
