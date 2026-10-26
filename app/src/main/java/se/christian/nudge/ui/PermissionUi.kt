package se.christian.nudge.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import se.christian.nudge.PermissionState
import se.christian.nudge.Permissions
import se.christian.nudge.R

enum class PermissionKind(val title: String, val description: String, val label: String?) {
    Notifications(
        title = "Notifications",
        description = "Shows that reminders are on, and shows reminders as notifications when the pill can't appear.",
        label = null,
    ),
    UsageAccess(
        title = "Usage access",
        description = "Lets Nudge see which app is open. This information never leaves your phone.",
        label = "Required",
    ),
    Overlay(
        title = "Display over other apps",
        description = "Needed to show the reminder pill on top of the app you're using.",
        label = "Recommended",
    ),
    Battery(
        title = "Unrestricted battery",
        description = "Some phone makers stop background apps to save battery. This keeps reminders working.",
        label = "Recommended",
    ),
    ;

    val required get() = this == UsageAccess

    fun isGranted(state: PermissionState) = when (this) {
        Notifications -> state.notifications
        UsageAccess -> state.usageAccess
        Overlay -> state.overlay
        Battery -> state.battery
    }
}

/** Returns a function that asks for a permission, through a runtime request or the right Settings screen. */
@Composable
fun rememberPermissionRequester(): (PermissionKind) -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Denied without a dialog (or twice): the only way left is the app's notification settings.
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
        ) {
            context.startSettings(Permissions.notificationSettingsIntent(context))
        }
    }
    return remember(context, notificationLauncher) {
        { kind ->
            when (kind) {
                PermissionKind.Notifications ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.startSettings(Permissions.notificationSettingsIntent(context))
                    }
                PermissionKind.UsageAccess -> context.startSettings(Permissions.usageAccessIntent())
                PermissionKind.Overlay -> context.startSettings(Permissions.overlayIntent(context))
                PermissionKind.Battery -> context.startSettings(Permissions.batteryIntent(context))
            }
        }
    }
}

private fun Context.startSettings(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("Permissions", "No activity for ${intent.action}", e)
    }
}

/** Row in the onboarding checklist. */
@Composable
fun PermissionRow(kind: PermissionKind, granted: Boolean, onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium)
                kind.label?.let { label ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (kind.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = kind.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        if (granted) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = "Allowed",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        } else {
            FilledTonalButton(onClick = onRequest) { Text("Allow") }
        }
    }
}

/** Warning card on the main screen for a missing permission. */
@Composable
fun PermissionCard(kind: PermissionKind, onRequest: () -> Unit, modifier: Modifier = Modifier) {
    val colors = if (kind.required) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = colors) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (kind.required) "${kind.title} is off" else "Turn on ${kind.title.lowercase()}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.size(2.dp))
                Text(kind.description, style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = onRequest,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                ) {
                    Text("Allow")
                }
            }
        }
    }
}
