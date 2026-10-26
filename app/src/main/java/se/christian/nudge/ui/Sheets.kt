package se.christian.nudge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
        SheetHeader(icon = null, title = "Remind me after")
        Text(
            text = "Applies to every app without its own setting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        ThresholdPicker(
            value = settings.defaultThresholdMinutes,
            onValueChange = { minutes -> minutes?.let(onChange) },
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
fun AppSettingsSheet(app: AppInfo?, packageName: String, settings: Settings, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val enabled = settings.isAppEnabled(packageName)
    NudgeSheet(onDismiss) {
        SheetHeader(icon = app, title = app?.label ?: packageName)
        SwitchRow(
            title = "Reminders for this app",
            checked = enabled,
            onCheckedChange = { viewModel.setAppEnabled(packageName, it) },
        )
        SheetSectionTitle("Remind me after")
        ThresholdPicker(
            value = settings.thresholdOverrides[packageName],
            onValueChange = { viewModel.setThresholdOverride(packageName, it) },
            defaultMinutes = settings.defaultThresholdMinutes,
            enabled = enabled,
        )
    }
}
