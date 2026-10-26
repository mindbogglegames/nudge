package se.christian.nudge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.christian.nudge.PermissionState
import se.christian.nudge.R
import se.christian.nudge.apps.AppInfo

@Composable
fun NudgeRoot(viewModel: MainViewModel, onShowTestReminder: suspend () -> Boolean) {
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val sheetPackage by viewModel.sheetPackage.collectAsStateWithLifecycle()
    val requestPermission = rememberPermissionRequester()
    val currentSettings = settings

    when {
        onboardingCompleted == null || currentSettings == null ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            )
        onboardingCompleted == false -> OnboardingScreen(
            permissions = permissions,
            onRequestPermission = requestPermission,
            onFinish = viewModel::completeOnboarding,
        )
        else -> {
            MainScreen(
                settings = currentSettings,
                permissions = permissions,
                apps = apps,
                viewModel = viewModel,
                onRequestPermission = requestPermission,
                onShowTestReminder = onShowTestReminder,
                onOpenApp = viewModel::openAppSheet,
            )
            sheetPackage?.let { packageName ->
                val app by produceState<AppInfo?>(null, packageName, apps) { value = viewModel.appInfo(packageName) }
                AppSettingsSheet(
                    app = app,
                    packageName = packageName,
                    settings = currentSettings,
                    viewModel = viewModel,
                    onDismiss = { viewModel.openAppSheet(null) },
                )
            }
        }
    }
}

@Composable
private fun OnboardingScreen(
    permissions: PermissionState,
    onRequestPermission: (PermissionKind) -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(24.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (step == 0) Intro() else Checklist(permissions, onRequestPermission)
            }
            Button(
                onClick = { if (step == 0) step = 1 else onFinish() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text(if (step == 0) "Get started" else "Done")
            }
        }
    }
}

@Composable
private fun Intro() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_stat_nudge),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        Text("Nudge", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "After you've used an app for a while, a small reminder slides in at the top of the screen, " +
                "like \"15 min on YouTube\". You choose which apps, and when. Apps are never blocked.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Everything stays on this phone. Nudge has no internet access and collects nothing.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Checklist(permissions: PermissionState, onRequestPermission: (PermissionKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 24.dp)) {
        Text("A few permissions", style = MaterialTheme.typography.headlineMedium)
        PermissionKind.entries.forEach { kind ->
            PermissionRow(
                kind = kind,
                granted = kind.isGranted(permissions),
                onRequest = { onRequestPermission(kind) },
            )
        }
    }
}
