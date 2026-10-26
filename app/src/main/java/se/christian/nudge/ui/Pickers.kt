package se.christian.nudge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import se.christian.nudge.session.formatMinutes
import se.christian.nudge.settings.SettingsRepository

val THRESHOLD_PRESETS = listOf(5, 10, 15, 20, 30, 45, 60)

/**
 * Threshold chips 5–60 min plus "Custom" (number input). When [defaultMinutes] is given, a "Use default" chip is
 * shown and `null` means "follow the default".
 */
@Composable
fun ThresholdPicker(
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    defaultMinutes: Int? = null,
    enabled: Boolean = true,
) {
    var editingCustom by rememberSaveable { mutableStateOf(false) }
    val isCustom = value != null && value !in THRESHOLD_PRESETS

    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            THRESHOLD_PRESETS.forEach { minutes ->
                FilterChip(
                    selected = value == minutes && !editingCustom,
                    onClick = {
                        editingCustom = false
                        onValueChange(minutes)
                    },
                    label = { Text(formatMinutes(minutes.toLong())) },
                    enabled = enabled,
                )
            }
            FilterChip(
                selected = isCustom || editingCustom,
                onClick = { editingCustom = true },
                label = { Text(if (value != null && isCustom) "Custom · ${formatMinutes(value.toLong())}" else "Custom") },
                enabled = enabled,
            )
            if (defaultMinutes != null) {
                FilterChip(
                    selected = value == null && !editingCustom,
                    onClick = {
                        editingCustom = false
                        onValueChange(null)
                    },
                    label = { Text("Use default (${formatMinutes(defaultMinutes.toLong())})") },
                    enabled = enabled,
                )
            }
        }
        if (editingCustom && enabled) {
            CustomMinutesInput(
                initial = value,
                onSubmit = { minutes ->
                    editingCustom = false
                    onValueChange(minutes)
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CustomMinutesInput(initial: Int?, onSubmit: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf(initial?.toString().orEmpty()) }
    val minutes = text.toIntOrNull()?.takeIf { it in 1..SettingsRepository.MAX_MINUTES }
    val submit = { minutes?.let(onSubmit) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { input -> text = input.filter(Char::isDigit).take(4) },
            label = { Text("Minutes") },
            singleLine = true,
            isError = text.isNotEmpty() && minutes == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Button(onClick = { submit() }, enabled = minutes != null) { Text("Set") }
    }
}

/** Single-choice chips. */
@Composable
fun <T> ChoiceChips(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
fun SheetSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** "30 s", "2 min". */
fun formatSeconds(seconds: Int): String =
    if (seconds < 60 || seconds % 60 != 0) "$seconds s" else formatMinutes(seconds / 60L)
