package se.christian.nudge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.christian.nudge.apps.AppInfo
import se.christian.nudge.session.formatMinutes
import se.christian.nudge.settings.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NudgeSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            content()
        }
    }
}

@Composable
fun DefaultThresholdSheet(settings: Settings, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    NudgeSheet(onDismiss) {
        SheetHeader(icon = null, title = "Continuous use")
        SheetHint(continuousHint(settings.defaultThresholdMinutes))
        SheetHint("Applies to every app without its own setting.", modifier = Modifier.padding(bottom = 8.dp))
        ThresholdPicker(
            value = settings.defaultThresholdMinutes,
            onValueChange = { minutes -> minutes?.let(onChange) },
        )
    }
}

@Composable
fun DailyTotalSheet(settings: Settings, viewModel: MainViewModel, onDismiss: () -> Unit) {
    NudgeSheet(onDismiss) {
        SheetHeader(icon = null, title = "Daily total")
        SheetHint(dailyHint(settings.defaultDailyLimitMinutes))
        SheetHint(
            "Adds up every visit to an app. Totals reset at midnight. Applies to every app without its own setting.",
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ThresholdPicker(
            value = settings.defaultDailyLimitMinutes,
            onValueChange = { minutes -> minutes?.let(viewModel::setDefaultDailyLimit) },
            presets = DAILY_LIMIT_PRESETS,
        )
        SheetSectionTitle("After that, remind me every")
        ChoiceChips(
            options = listOf("Only once" to null) + listOf(5, 10, 15, 30, 60).map { formatMinutes(it.toLong()) to it },
            selected = settings.dailyRepeatIntervalMinutes,
            onSelect = viewModel::setDailyRepeatInterval,
        )
        Text(
            text = "Counted from Android's usage data, so time before Nudge started today counts too.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
fun AdvancedSheet(settings: Settings, viewModel: MainViewModel, onDismiss: () -> Unit) {
    NudgeSheet(onDismiss) {
        SheetHeader(icon = null, title = "Advanced")

        SheetSectionTitle("Repeat reminder every")
        ChoiceChips(
            options = listOf("Same as reminder time" to null) +
                listOf(5, 10, 15, 30, 60).map { formatMinutes(it.toLong()) to it },
            selected = settings.repeatIntervalMinutes,
            onSelect = viewModel::setRepeatInterval,
        )

        SheetSectionTitle("Grace period")
        Text(
            text = "Coming back to an app within this time continues the same session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ChoiceChips(
            options = listOf(30, 60, 120, 300, 600).map { formatSeconds(it) to it },
            selected = settings.gracePeriodSeconds,
            onSelect = viewModel::setGracePeriod,
        )

        SheetSectionTitle("Snooze length")
        ChoiceChips(
            options = listOf(5, 10, 15, 30, 60).map { formatMinutes(it.toLong()) to it },
            selected = settings.snoozeMinutes,
            onSelect = viewModel::setSnooze,
        )
    }
}

@Composable
fun AppSettingsSheet(
    app: AppInfo?,
    packageName: String,
    settings: Settings,
    usedTodayMs: Long,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val enabled = settings.isAppEnabled(packageName)
    NudgeSheet(onDismiss) {
        SheetHeader(icon = app, title = app?.label ?: packageName, subtitle = usedTodayText(usedTodayMs))
        SwitchRow(
            title = "Reminders for this app",
            checked = enabled,
            onCheckedChange = { viewModel.setAppEnabled(packageName, it) },
        )
        if (settings.continuousRemindersEnabled) {
            val on = settings.isContinuousOn(packageName)
            SectionSwitchHeader(
                title = "Continuous use",
                checked = on,
                onCheckedChange = { viewModel.setAppContinuousEnabled(packageName, it) },
                enabled = enabled,
            )
            if (on) {
                SheetHint(continuousHint(settings.thresholdMinutesFor(packageName)))
                ThresholdPicker(
                    value = settings.thresholdOverrides[packageName],
                    onValueChange = { viewModel.setThresholdOverride(packageName, it) },
                    defaultMinutes = settings.defaultThresholdMinutes,
                    enabled = enabled,
                )
            } else {
                SheetHint("Off for this app")
            }
        }
        if (settings.dailyRemindersEnabled) {
            val on = settings.isDailyOn(packageName)
            SectionSwitchHeader(
                title = "Daily total",
                checked = on,
                onCheckedChange = { viewModel.setAppDailyEnabled(packageName, it) },
                enabled = enabled,
            )
            if (on) {
                SheetHint(dailyHint(settings.dailyLimitMinutesFor(packageName)))
                ThresholdPicker(
                    value = settings.dailyLimitOverrides[packageName],
                    onValueChange = { viewModel.setDailyLimitOverride(packageName, it) },
                    defaultMinutes = settings.defaultDailyLimitMinutes,
                    enabled = enabled,
                    presets = DAILY_LIMIT_PRESETS,
                )
            } else {
                SheetHint("Off for this app")
            }
        }
        if (!settings.continuousRemindersEnabled && !settings.dailyRemindersEnabled) {
            SheetHint(
                "Continuous use and daily total reminders are both off. Turn one on in the main screen.",
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/** Section title with a switch, for turning one reminder type on or off for a single app. */
@Composable
private fun SectionSwitchHeader(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** "Remind me after 15 min of continuous use". */
fun continuousHint(minutes: Int): String = "Remind me after ${formatMinutes(minutes.toLong())} of continuous use"

/** "Remind me after 1 h of use each day". */
fun dailyHint(minutes: Int): String = "Remind me after ${formatMinutes(minutes.toLong())} of use each day"

@Composable
private fun SheetHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

/** "Used 47 min today", or "Not used today" under a minute. */
fun usedTodayText(usedTodayMs: Long): String {
    val minutes = usedTodayMs / 60_000L
    return if (minutes == 0L) "Not used today" else "Used ${formatMinutes(minutes)} today"
}
