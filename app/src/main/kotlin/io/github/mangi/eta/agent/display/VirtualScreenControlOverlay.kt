package io.github.mangi.eta.agent.display

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayCapsule
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Activity-local controls: only the visible handle/capsule intercepts application touches. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun VirtualScreenControlOverlay(
    state: VirtualScreenViewerState,
    overlay: AgentOverlayState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    inputError: Boolean,
    compatibilityRenderer: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSupplement: (String) -> Unit,
    onKeyboard: () -> Unit,
    onHistory: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val host = LocalView.current
    DisposableEffect(host) { onDispose { host.systemGestureExclusionRects = emptyList() } }
    val prefs = remember(context) { context.getSharedPreferences("virtual_screen_controls", android.content.Context.MODE_PRIVATE) }
    var dock by remember { mutableStateOf(OverlayDockPosition(prefs.getBoolean("right", true), prefs.getFloat("fraction", 0.3f))) }
    var compact by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Offset?>(null) }
    var controlSize by remember { mutableStateOf(IntSize.Zero) }
    var supplement by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, compact, drag, supplement) {
        if (!expanded && compact && drag == null && !supplement) {
            delay(2_000)
            compact = false
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val cutout = WindowInsets.displayCutout
        // Keep controls below system chrome even while immersive mode hides the bars.
        val top = maxOf(cutout.getTop(density), WindowInsets.statusBarsIgnoringVisibility.getTop(density)).toFloat() +
            with(density) { 8.dp.toPx() }
        val bottom = maxOf(cutout.getBottom(density), WindowInsets.navigationBarsIgnoringVisibility.getBottom(density)).toFloat() +
            with(density) { 8.dp.toPx() }
        val shown = expanded || compact
        val controlWidth = if (shown) minOf(maxWidth, 272.dp) else 24.dp
        val w = with(density) { controlWidth.toPx() }
        val h = controlSize.height.takeIf { it > 0 }?.toFloat() ?: with(density) { 64.dp.toPx() }
        val origin = dock.origin(widthPx, heightPx, w, h, top, bottom)
        val position = drag ?: Offset(origin.x, origin.y)
        fun saveDock() {
            val current = drag ?: return
            dock = OverlayDockPosition.nearest(current.x, current.y, widthPx, heightPx, w, h, top, bottom)
            prefs.edit().putBoolean("right", dock.right).putFloat("fraction", dock.fraction).apply()
            drag = null
        }
        val dragModifier = Modifier.pointerInput(widthPx, heightPx, w, h, dock) {
            detectDragGestures(
                onDragStart = { drag = Offset(origin.x, origin.y) },
                onDragCancel = { saveDock() },
                onDragEnd = { saveDock() },
                onDrag = { change, amount ->
                    change.consume()
                    val current = drag ?: Offset(origin.x, origin.y)
                    drag = Offset((current.x + amount.x).coerceIn(0f, (widthPx - w).coerceAtLeast(0f)),
                        (current.y + amount.y).coerceIn(top.coerceAtMost((heightPx - h).coerceAtLeast(0f)),
                            (heightPx - bottom - h).coerceAtLeast(top.coerceAtMost((heightPx - h).coerceAtLeast(0f)))))
                },
            )
        }
        Box(Modifier.offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .width(controlWidth).onSizeChanged { controlSize = it }) {
            if (!shown) {
                val accent = when (overlay.phase) {
                    AgentOverlayPhase.FAILED -> MiuixTheme.colorScheme.error
                    AgentOverlayPhase.RUNNING -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                }
                val label = stringResource(R.string.virtual_screen_controls)
                Box(Modifier.width(24.dp).height(64.dp).then(dragModifier)
                    .onGloballyPositioned { layout ->
                        val p = layout.positionInWindow()
                        host.systemGestureExclusionRects = listOf(android.graphics.Rect(p.x.roundToInt(), p.y.roundToInt(),
                            (p.x + layout.size.width).roundToInt(), (p.y + layout.size.height).roundToInt()))
                    }
                    .clickable(onClickLabel = label) { onExpandedChange(true) }
                    .semantics { contentDescription = label },
                    contentAlignment = if (dock.right) Alignment.CenterEnd else Alignment.CenterStart) {
                    Box(Modifier.width(10.dp).height(42.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.8f)))
                }
            } else {
                AgentOverlayCapsule(overlay, expanded,
                    onToggleExpanded = { onExpandedChange(!expanded); compact = true },
                    onCollapsedSettled = {}, onPause = onPause, onResume = onResume, onStop = onStop,
                    onSupplementModeChange = { supplement = it }, onSupplement = onSupplement,
                    headerModifier = dragModifier,
                    showRunControls = state.activeRunId != null,
                    runControlsEnabled = state.activeRunId != null,
                    containerModifier = Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!down.isConsumed) {
                                down.consume()
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    },
                    statusOverride = if (state.activeRunId != null || state.manualControl) null else stringResource(when (state.taskPhase) {
                        VirtualScreenTaskPhase.COMPLETED -> R.string.virtual_screen_completed
                        VirtualScreenTaskPhase.FAILED -> R.string.virtual_screen_failed
                        VirtualScreenTaskPhase.STOPPED -> R.string.virtual_screen_stopped
                        VirtualScreenTaskPhase.PAUSED -> R.string.virtual_screen_paused
                        else -> R.string.virtual_screen_idle
                    }),
                    footer = {
                        if (!supplement) {
                            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (compatibilityRenderer) Text(stringResource(R.string.virtual_screen_compatibility_preview),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                                if (inputError) Text(stringResource(R.string.virtual_screen_input_failed),
                                    color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    Control(Icons.Rounded.Keyboard, R.string.virtual_screen_text_toggle, onClick = onKeyboard)
                                    Control(Icons.Rounded.History, R.string.virtual_screen_history, onClick = onHistory)
                                    Control(Icons.AutoMirrored.Rounded.ExitToApp, R.string.virtual_screen_exit_viewer, onClick = onExit)
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Control(icon: ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, stringResource(label), modifier = Modifier.size(19.dp),
            tint = MiuixTheme.colorScheme.onSurface)
    }
}
