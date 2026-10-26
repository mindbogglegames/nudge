package se.christian.nudge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.christian.nudge.PermissionState
import se.christian.nudge.R
import se.christian.nudge.apps.AppInfo
import se.christian.nudge.session.formatMinutes
import se.christian.nudge.settings.Settings

private enum class GlobalSheet { Threshold, Advanced }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: Settings,
    permissions: PermissionState,
    apps: List<AppInfo>?,
    viewModel: MainViewModel,
    onRequestPermission: (PermissionKind) -> Unit,
    onShowTestReminder: suspend () -> Boolean,
    onOpenApp: (String) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var openSheet by rememberSaveable { mutableStateOf<GlobalSheet?>(null) }
    val listEnabled = settings.remindersEnabled
    val missing = PermissionKind.entries.filterNot { it.isGranted(permissions) }
    val filteredApps = apps?.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(title = { Text("Nudge") }, scrollBehavior = scrollBehavior)
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Text(
                    text = "Get a gentle reminder at the top of the screen when you've been using an app " +
                        "for a while. Apps are never blocked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                )
            }
            item {
                MainSwitchBar(
                    checked = settings.remindersEnabled,
                    onCheckedChange = viewModel::setRemindersEnabled,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(missing, key = { it.name }) { kind ->
                PermissionCard(
                    kind = kind,
                    onRequest = { onRequestPermission(kind) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
            item { Spacer(Modifier.size(8.dp)) }
            item {
                SettingRow(
                    title = "Remind me after",
                    summary = formatMinutes(settings.defaultThresholdMinutes.toLong()) + " of continuous use",
                    onClick = { openSheet = GlobalSheet.Threshold },
                )
            }
            item {
                SettingRow(
                    title = "Advanced",
                    summary = advancedSummary(settings),
                    onClick = { openSheet = GlobalSheet.Advanced },
                )
            }
            item {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            if (!onShowTestReminder()) {
                                snackbar.showSnackbar(
                                    "Display over other apps is off, so the reminder was sent as a notification",
                                )
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    Text("Show test reminder")
                }
            }
            item {
                Text(
                    text = "Apps",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                )
            }
            item {
                Text(
                    text = "Reminders are off for every app until you turn them on here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
                )
            }
            item {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    enabled = listEnabled,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                )
            }
            if (filteredApps == null) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items(filteredApps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        settings = settings,
                        enabled = listEnabled,
                        onToggle = { viewModel.setAppEnabled(app.packageName, it) },
                        onClick = { onOpenApp(app.packageName) },
                    )
                }
            }
        }
    }

    when (openSheet) {
        GlobalSheet.Threshold -> DefaultThresholdSheet(
            settings = settings,
            onChange = viewModel::setDefaultThreshold,
            onDismiss = { openSheet = null },
        )
        GlobalSheet.Advanced -> AdvancedSheet(
            settings = settings,
            viewModel = viewModel,
            onDismiss = { openSheet = null },
        )
        null -> Unit
    }
}

/** Android Settings style main switch: a large rounded, filled container. */
@Composable
private fun MainSwitchBar(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Use reminders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun SettingRow(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    )
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        enabled = enabled,
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun AppRow(app: AppInfo, settings: Settings, enabled: Boolean, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val icon = remember(app.icon) { app.icon.asImageBitmap() }
    val appEnabled = settings.isAppEnabled(app.packageName)
    ListItem(
        headlineContent = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(appSubtitle(app.packageName, settings)) },
        leadingContent = { Image(icon, contentDescription = null, modifier = Modifier.size(40.dp)) },
        trailingContent = { Switch(checked = appEnabled, onCheckedChange = onToggle, enabled = enabled) },
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
    )
}

/** "After 15 min", "After 30 min · custom" or "Off". */
fun appSubtitle(packageName: String, settings: Settings): String {
    if (!settings.isAppEnabled(packageName)) return "Off"
    val after = "After ${formatMinutes(settings.thresholdMinutesFor(packageName).toLong())}"
    return if (packageName in settings.thresholdOverrides) "$after · custom" else after
}

private fun advancedSummary(settings: Settings): String {
    val repeat = settings.repeatIntervalMinutes?.let { formatMinutes(it.toLong()) } ?: "same as reminder time"
    return "Repeat: $repeat · Grace: ${formatSeconds(settings.gracePeriodSeconds)} · " +
        "Snooze: ${formatMinutes(settings.snoozeMinutes.toLong())}"
}

/** Row used in sheets: label plus switch, whole row toggles. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shape = RoundedCornerShape(28.dp),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
fun SheetHeader(icon: AppInfo?, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
        if (icon != null) {
            val image = remember(icon.icon) { icon.icon.asImageBitmap() }
            Image(image, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column { Text(title, style = MaterialTheme.typography.headlineSmall) }
    }
}

private const val DISABLED_ALPHA = 0.38f
