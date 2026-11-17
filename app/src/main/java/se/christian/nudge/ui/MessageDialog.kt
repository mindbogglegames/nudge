package se.christian.nudge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import se.christian.nudge.session.APP_PLACEHOLDER
import se.christian.nudge.session.ReminderKind
import se.christian.nudge.session.TIME_PLACEHOLDER
import se.christian.nudge.session.defaultMessage
import se.christian.nudge.session.reminderText
import se.christian.nudge.settings.SettingsRepository

/** "Reminder message" row in the app sheet, showing what the reminder will say. Tapping it opens [MessageDialog]. */
@Composable
fun MessageRow(
    minutes: Int,
    appLabel: String,
    kind: ReminderKind,
    message: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val preview = "“${reminderText(minutes.toLong(), appLabel, kind, message)}”"
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text("Reminder message", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = if (message == null) preview else "$preview · custom",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Edits one app's reminder message for [kind]. Starts from the app's message, or the built-in one with its placeholders, and shows a
 * live preview. [onSave] gets null when the message should go back to the built-in one.
 */
@Composable
fun MessageDialog(
    minutes: Int,
    appLabel: String,
    kind: ReminderKind,
    message: String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val default = defaultMessage(kind)
    val initial = message ?: default
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    val text = field.text.trim()

    fun insert(placeholder: String) {
        val start = field.selection.min
        val end = field.selection.max
        val updated = field.text.replaceRange(start, end, placeholder)
        if (updated.length <= SettingsRepository.MAX_MESSAGE_LENGTH) {
            field = TextFieldValue(updated, selection = TextRange(start + placeholder.length))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == ReminderKind.Continuous) "Continuous use message" else "Daily total message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { if (it.text.length <= SettingsRepository.MAX_MESSAGE_LENGTH) field = it },
                    label = { Text("Message") },
                    placeholder = { Text(default) },
                    supportingText = { Text("${field.text.length}/${SettingsRepository.MAX_MESSAGE_LENGTH}") },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { insert(TIME_PLACEHOLDER) }, label = { Text("Insert time") })
                    AssistChip(onClick = { insert(APP_PLACEHOLDER) }, label = { Text("Insert app name") })
                }
                Text(
                    text = "$TIME_PLACEHOLDER becomes the time used, $APP_PLACEHOLDER the app's name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Preview",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = reminderText(minutes.toLong(), appLabel, kind, text),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.takeUnless { it.isEmpty() || it == default }) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (message != null) TextButton(onClick = { onSave(null) }) { Text("Use default") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
