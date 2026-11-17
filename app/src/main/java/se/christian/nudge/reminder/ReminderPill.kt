package se.christian.nudge.reminder

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import se.christian.nudge.history.ReminderEnd
import se.christian.nudge.session.PillLevel
import se.christian.nudge.session.formatMinutes
import kotlin.math.roundToInt

private const val AUTO_DISMISS_MS = 6_000L
private const val PROMINENT_DISMISS_MS = 10_000L
private const val EXPANDED_DISMISS_MS = 8_000L
private const val OFF_MESSAGE_MS = 2_000L

private val ExpandedCardWidth = 300.dp
private val PillMarginHorizontal = 16.dp

/** Window width that fits the expanded card and its margins. */
internal val ExpandedWindowWidth = ExpandedCardWidth + PillMarginHorizontal * 2

/**
 * Space between a big pill and its window's edge, for the shadow. The window itself is inset so that the pill sits
 * [PillMarginHorizontal] from the screen edges and touches beside it reach the app underneath.
 */
internal val BigPillShadowMargin = 4.dp

/** Horizontal inset of a big pill's window from each screen edge. */
internal val BigPillWindowInset = PillMarginHorizontal - BigPillShadowMargin

/**
 * The reminder pill: slides in, auto-dismisses, swipe up to dismiss, tap to expand into a card with actions.
 * [onDismissed] is called once the exit animation has finished, with how the pill ended.
 */
@Composable
fun ReminderPill(
    content: PillContent,
    onSnooze: () -> Unit,
    onTurnOff: () -> Unit,
    onOpenSettings: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onDismissed: (ReminderEnd) -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    var expanded by remember { mutableStateOf(false) }
    var offMessage by remember { mutableStateOf<String?>(null) }
    var interactions by remember { mutableIntStateOf(0) }
    val dragOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissDistancePx = with(LocalDensity.current) { 24.dp.toPx() }
    val big = content.level != PillLevel.SUBTLE
    val persistent = content.level == PillLevel.PERSISTENT
    // Time left before a prominent pill closes, shown as a bar along its bottom edge.
    val timeLeft = remember { Animatable(1f) }

    // Not state: only read once the pill is gone. The first reason wins.
    val endReason = remember { arrayOfNulls<ReminderEnd>(1) }

    fun dismiss(reason: ReminderEnd) {
        if (endReason[0] == null) endReason[0] = reason
        visibility.targetState = false
    }

    LaunchedEffect(Unit) {
        visibility.targetState = true
        // Also fires when dismissed mid-enter, before the pill was ever fully visible.
        snapshotFlow { !visibility.targetState && !visibility.currentState && visibility.isIdle }.first { it }
        onDismissed(endReason[0] ?: ReminderEnd.TIMED_OUT)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { expanded }.drop(1).collect(onExpandedChange)
    }

    // Restarts on every interaction, so "after the last interaction" holds. A persistent pill only closes when
    // the user closes it, or when the overlay removes it (app left, screen off).
    LaunchedEffect(expanded, offMessage, interactions) {
        val timeout = when {
            offMessage != null -> OFF_MESSAGE_MS
            persistent -> null
            expanded -> EXPANDED_DISMISS_MS
            big -> PROMINENT_DISMISS_MS
            else -> AUTO_DISMISS_MS
        } ?: return@LaunchedEffect
        if (big && !expanded && offMessage == null) {
            timeLeft.snapTo(1f)
            launch { timeLeft.animateTo(0f, tween(timeout.toInt(), easing = LinearEasing)) }
        }
        delay(timeout)
        dismiss(ReminderEnd.TIMED_OUT)
    }

    val enter = if (big) {
        // A small bounce draws the eye to the bigger pill.
        val bounce = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
        slideInVertically(bounce) { -it } + fadeIn()
    } else {
        slideInVertically { -it } + fadeIn()
    }

    AnimatedVisibility(
        visibleState = visibility,
        // A big pill's window has a fixed width the pill fills; a subtle pill's window wraps it (centered while
        // the window has the expanded width).
        modifier = if (big) Modifier.fillMaxWidth() else Modifier.wrapContentWidth(Alignment.CenterHorizontally),
        enter = enter,
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        Surface(
            modifier = Modifier
                .then(if (big) Modifier.wrapContentWidth(Alignment.CenterHorizontally) else Modifier)
                .padding(horizontal = if (big) BigPillShadowMargin else PillMarginHorizontal, vertical = 8.dp)
                .then(if (big && !expanded) Modifier.fillMaxWidth() else Modifier)
                .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { interactions++ },
                        onDragEnd = {
                            if (dragOffset.value < -dismissDistancePx) {
                                dismiss(ReminderEnd.DISMISSED)
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
            color = if (big && !expanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = if (big) 6.dp else 3.dp,
        ) {
            val message = offMessage
            val expand = Modifier.clickable {
                interactions++
                expanded = true
            }
            when {
                message != null && big -> BigPillRow(content.icon, message, subtitle = null)
                message != null -> PillRow(content.icon, message)
                expanded -> ExpandedCard(
                    content = content,
                    onSnooze = {
                        onSnooze()
                        dismiss(ReminderEnd.SNOOZED)
                    },
                    onTurnOff = {
                        onTurnOff()
                        endReason[0] = ReminderEnd.TURNED_OFF
                        expanded = false
                        offMessage = "Reminders off for ${content.appLabel}"
                    },
                    onOpenSettings = {
                        onOpenSettings()
                        dismiss(ReminderEnd.OPENED_SETTINGS)
                    },
                    onClose = if (persistent) ({ dismiss(ReminderEnd.DISMISSED) }) else null,
                )
                big -> BigPillRow(
                    icon = content.icon,
                    text = content.text,
                    subtitle = if (persistent) "Swipe up to close, or tap for options" else "Tap to snooze or turn off",
                    timeLeft = if (persistent) null else timeLeft.value,
                    modifier = expand,
                )
                else -> PillRow(icon = content.icon, text = content.text, modifier = expand)
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

/** The prominent pill: full width, 40dp icon, larger text, and a time-left bar when [timeLeft] isn't null. */
@Composable
private fun BigPillRow(icon: Bitmap?, text: String, subtitle: String?, modifier: Modifier = Modifier, timeLeft: Float? = null) {
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .padding(start = 16.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icon, size = 40.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
        if (timeLeft != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(timeLeft)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun ExpandedCard(
    content: PillContent,
    onSnooze: () -> Unit,
    onTurnOff: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: (() -> Unit)? = null,
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
        if (onClose != null) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Settings") }
                TextButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
            }
        } else {
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Settings")
            }
        }
    }
}

@Composable
private fun AppIcon(icon: Bitmap?, size: Dp = 24.dp) {
    val image = remember(icon) { icon?.asImageBitmap() }
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
