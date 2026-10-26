package se.christian.nudge.reminder

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import se.christian.nudge.session.formatMinutes
import kotlin.math.roundToInt

private const val AUTO_DISMISS_MS = 6_000L
private const val EXPANDED_DISMISS_MS = 8_000L
private const val OFF_MESSAGE_MS = 2_000L

private val ExpandedCardWidth = 300.dp
private val PillMarginHorizontal = 16.dp

/** Window width that fits the expanded card and its margins. */
internal val ExpandedWindowWidth = ExpandedCardWidth + PillMarginHorizontal * 2

/**
 * The reminder pill: slides in, auto-dismisses, swipe up to dismiss, tap to expand into a card with actions.
 * [onDismissed] is called once the exit animation has finished.
 */
@Composable
fun ReminderPill(
    content: PillContent,
    onSnooze: () -> Unit,
    onTurnOff: () -> Unit,
    onOpenSettings: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onDismissed: () -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    var expanded by remember { mutableStateOf(false) }
    var offMessage by remember { mutableStateOf<String?>(null) }
    var interactions by remember { mutableIntStateOf(0) }
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissDistancePx = with(LocalDensity.current) { 24.dp.toPx() }

    fun dismiss() {
        visibility.targetState = false
    }

    LaunchedEffect(Unit) {
        visibility.targetState = true
        // Also fires when dismissed mid-enter, before the pill was ever fully visible.
        snapshotFlow { !visibility.targetState && !visibility.currentState && visibility.isIdle }.first { it }
        onDismissed()
    }

    LaunchedEffect(Unit) {
        snapshotFlow { expanded }.drop(1).collect(onExpandedChange)
    }

    // Restarts on every interaction, so "after the last interaction" holds.
    LaunchedEffect(expanded, offMessage, interactions) {
        delay(
            when {
                offMessage != null -> OFF_MESSAGE_MS
                expanded -> EXPANDED_DISMISS_MS
                else -> AUTO_DISMISS_MS
            },
        )
        dismiss()
    }

    AnimatedVisibility(
        visibleState = visibility,
        // Centered while the window has the expanded width; plain wrap-content otherwise.
        modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally),
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = PillMarginHorizontal, vertical = 8.dp)
                .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { interactions++ },
                        onDragEnd = {
                            if (dragOffset.value < -dismissDistancePx) {
                                dismiss()
                            } else {
                                scope.launch { dragOffset.animateTo(0f) }
                            }
                        },
                        onDragCancel = { scope.launch { dragOffset.animateTo(0f) } },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch { dragOffset.snapTo((dragOffset.value + dragAmount).coerceAtMost(0f)) }
                        },
                    )
                }
                .animateContentSize(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 3.dp,
        ) {
            val message = offMessage
            when {
                message != null -> PillRow(content.icon, message)
                expanded -> ExpandedCard(
                    content = content,
                    onSnooze = {
                        onSnooze()
                        dismiss()
                    },
                    onTurnOff = {
                        onTurnOff()
                        expanded = false
                        offMessage = "Reminders off for ${content.appLabel}"
                    },
                    onOpenSettings = {
                        onOpenSettings()
                        dismiss()
                    },
                )
                else -> PillRow(
                    icon = content.icon,
                    text = content.text,
                    modifier = Modifier.clickable {
                        interactions++
                        expanded = true
                    },
                )
            }
        }
    }
}

@Composable
private fun PillRow(icon: Bitmap?, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(start = 12.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon)
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExpandedCard(
    content: PillContent,
    onSnooze: () -> Unit,
    onTurnOff: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(ExpandedCardWidth)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(content.icon)
            Spacer(Modifier.width(12.dp))
            Text(
                text = content.text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        FilledTonalButton(onClick = onSnooze, modifier = Modifier.fillMaxWidth()) {
            Text("Snooze ${formatMinutes(content.snoozeMinutes.toLong())}")
        }
        OutlinedButton(onClick = onTurnOff, modifier = Modifier.fillMaxWidth()) {
            Text("Turn off for this app")
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Settings")
        }
    }
}

@Composable
private fun AppIcon(icon: Bitmap?) {
    val image = remember(icon) { icon?.asImageBitmap() }
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = Modifier.size(24.dp))
    } else {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
