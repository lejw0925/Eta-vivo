package io.github.mangi.eta.agent.display

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.util.Base64
import android.view.WindowManager
import android.view.RoundedCorner
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayGlow
import io.github.mangi.eta.agent.overlay.ScreenCornerRadii
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.EtaPreferenceTheme
import io.github.mangi.eta.ui.components.EtaWindowDialog
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class VirtualScreenViewerActivity : ComponentActivity() {
    private val viewerId = java.util.UUID.randomUUID().toString()
    private var surface: VirtualScreenDisplayView? = null
    private var liveRenderer by mutableStateOf(false)
    private var compatibilityRenderer by mutableStateOf(false)
    private var hasFrame by mutableStateOf(false)
    private var inputError by mutableStateOf(false)
    private var showHistory by mutableStateOf(false)
    private var controlsExpanded by mutableStateOf(false)
    private var lastFrameId = 0L
    private var textInputVisible by mutableStateOf(false)
    private var textDraft by mutableStateOf("")
    private var textPending by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTaskDescription(ActivityManager.TaskDescription.Builder()
            .setLabel(getString(R.string.virtual_screen_title))
            .setIcon(R.mipmap.ic_launcher)
            .setPrimaryColor(android.graphics.Color.BLACK)
            .build())
        // Drop the pre-document viewer card left by an update; keep Eta's main task.
        getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
            val info = task.taskInfo ?: return@forEach
            if (info.taskId != taskId && info.baseActivity?.className == javaClass.name &&
                info.baseIntent.data != intent.data) task.finishAndRemoveTask()
        }
        enableEdgeToEdge()
        window.attributes = window.attributes.apply {
            preferredRefreshRate = 120f
            if (Build.VERSION.SDK_INT >= 35) setFrameRatePowerSavingsBalanced(false)
        }
        configureSystemBars()
        onBackPressedDispatcher.addCallback(this) {
            when {
                showHistory -> showHistory = false
                textInputVisible -> textInputVisible = false
                controlsExpanded -> controlsExpanded = false
                VirtualScreenSession.state.value.display == null -> finish()
                else -> VirtualScreenSession.state.value.display?.let {
                    sendInput(it.sessionId, JSONObject().put("action", "key").put("button", "BACK"))
                }
            }
        }
        setContent {
            val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initial = AppearanceSettings())
            AgentAppTheme(appearance, applyInterfaceScale = true) { ViewerScreen() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    combine(VirtualScreenSession.state, VirtualScreenSession.rootBridge) { state, bridge -> state to bridge }
                        .collect { (state, bridge) ->
                        surface?.setTarget(state.display, bridge)
                        if (state.display == null) { surface?.clearFrame(); hasFrame = false; lastFrameId = 0 }
                    }
                }
                while (true) {
                    val info = VirtualScreenSession.state.value.display
                    if (info != null && !liveRenderer) {
                        val next = readFrame()
                        if (VirtualScreenSession.state.value.display?.sessionId == info.sessionId) {
                            if (next != null) {
                                val view = surface
                                if (view != null) {
                                    view.setFrame(next.bitmap, next.display)
                                    lastFrameId = next.id
                                    hasFrame = true
                                } else next.bitmap.recycle()
                            }
                        } else {
                            next?.bitmap?.recycle(); surface?.clearFrame(); hasFrame = false; lastFrameId = 0
                        }
                    }
                    delay(VirtualScreenFrameCapturePolicy.FRAME_INTERVAL_MS)
                }
            }
        }
    }

    private data class Frame(val bitmap: Bitmap, val id: Long, val display: VirtualDisplayInfo)

    private suspend fun readFrame(): Frame? {
        val pending = AtomicReference<Bitmap?>()
        return try {
            val result = withContext(Dispatchers.IO) {
                val frame = VirtualScreenSession.observeForViewer(this@VirtualScreenViewerActivity, lastFrameId, viewerId)
                val json = JSONObject(frame.content)
                val bitmap = frame.images.firstOrNull()?.reference?.substringAfter("base64,")?.let { encoded ->
                    runCatching {
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
                pending.set(bitmap)
                bitmap?.let { decoded ->
                    val display = VirtualScreenSession.state.value.display
                    if (display != null && display.displayId == json.optInt("displayId") &&
                        display.width == decoded.width && display.height == decoded.height && display.rotation == json.optInt("rotation")) {
                        Frame(decoded, json.optLong("frameId"), display)
                    } else {
                        decoded.recycle()
                        pending.set(null)
                        null
                    }
                }
            }
            pending.set(null)
            result
        } finally {
            pending.getAndSet(null)?.recycle()
        }
    }

    private fun sendInput(sessionId: String, args: JSONObject) {
        inputError = false
        surface?.cancelGesture()
        val info = VirtualScreenSession.state.value.display?.takeIf { it.sessionId == sessionId } ?: return
        if (args.optString("action") !in setOf("tap", "swipe", "long_press") && !VirtualScreenSession.beginManualGesture(viewerId, info)) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    VirtualScreenSession.rootBridge.value?.takeIf { it.sessionId == sessionId }?.takeover(viewerId)
                    VirtualScreenSession.inputForViewer(this@VirtualScreenViewerActivity, sessionId, args, viewerId, generationPrepared = true)
                }.getOrElse { io.github.mangi.eta.agent.model.AgentModelClient.ToolResult("{\"ok\":false}") }
            }
            inputError = !JSONObject(result.content).optBoolean("ok")
        }
    }

    private fun sendText(sessionId: String) {
        val text = textDraft
        if (text.isEmpty() || textPending) return
        textPending = true
        inputError = false
        lifecycleScope.launch {
            try {
                val info = VirtualScreenSession.state.value.display?.takeIf { it.sessionId == sessionId } ?: return@launch
                surface?.cancelGesture()
                if (!VirtualScreenSession.beginManualGesture(viewerId, info)) return@launch
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        VirtualScreenSession.rootBridge.value?.takeIf { it.sessionId == sessionId }?.takeover(viewerId)
                        VirtualScreenSession.inputForViewer(this@VirtualScreenViewerActivity, sessionId,
                            JSONObject().put("action", "text").put("text", text), viewerId, generationPrepared = true)
                    }.getOrElse { io.github.mangi.eta.agent.model.AgentModelClient.ToolResult("{\"ok\":false}") }
                }
                inputError = !JSONObject(result.content).optBoolean("ok")
                if (!inputError && VirtualScreenSession.state.value.display?.sessionId == sessionId) textDraft = ""
            } finally { textPending = false }
        }
    }

    private fun configureSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            show(WindowInsetsCompat.Type.statusBars())
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun pauseAgent(stop: Boolean = false) {
        val info = VirtualScreenSession.state.value.display ?: return
        surface?.cancelGesture()
        VirtualScreenSession.beginManualGesture(viewerId, info)
        if (stop) AgentRuntimeService.stopVirtualScreen(VirtualScreenSession.state.value.activeRunId)
        lifecycleScope.launch {
            inputError = withContext(Dispatchers.IO) { runCatching {
                VirtualScreenSession.rootBridge.value?.takeIf { it.sessionId == info.sessionId }?.takeover(viewerId)
            }.isFailure }
        }
    }

    private fun supplementAgent(text: String) {
        val info = VirtualScreenSession.state.value.display ?: return
        val runId = VirtualScreenSession.state.value.activeRunId ?: return
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { runCatching {
                VirtualScreenSession.resumeFromViewer(info.sessionId, resumeRuntime = false)
            }.getOrDefault(false) }
            inputError = !ready
            if (ready) {
                AgentRuntimeService.supplementVirtualScreen(runId, text)
                AgentRuntimeService.resumeVirtualScreen(runId)
            }
        }
    }

    private fun resumeAgent() {
        val sessionId = VirtualScreenSession.state.value.display?.sessionId ?: return
        lifecycleScope.launch {
            inputError = !withContext(Dispatchers.IO) { runCatching { VirtualScreenSession.resumeFromViewer(sessionId) }.getOrDefault(false) }
        }
    }

    @Composable
    @OptIn(ExperimentalLayoutApi::class)
    private fun ViewerScreen() {
        val state by VirtualScreenSession.state.collectAsState()
        val runtime by AgentRuntimeService.viewerOverlay.collectAsState()
        val channelError by VirtualScreenSession.bridgeError.collectAsState()
        val display = state.display
        val hostDisplay = LocalView.current.display
        val density = LocalDensity.current
        val contentTop = maxOf(WindowInsets.displayCutout.getTop(density), WindowInsets.statusBarsIgnoringVisibility.getTop(density))
        val corners = remember(hostDisplay, density) {
            val fallback = with(density) { 28.dp.toPx() }
            fun radius(position: Int) = hostDisplay?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }?.toFloat() ?: fallback
            ScreenCornerRadii(radius(RoundedCorner.POSITION_TOP_LEFT), radius(RoundedCorner.POSITION_TOP_RIGHT),
                radius(RoundedCorner.POSITION_BOTTOM_RIGHT), radius(RoundedCorner.POSITION_BOTTOM_LEFT))
        }
        val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(display?.sessionId) { textDraft = ""; textInputVisible = false }
        LaunchedEffect(display?.sessionId, liveRenderer) {
            compatibilityRenderer = false
            if (display != null && !liveRenderer) { delay(1500); compatibilityRenderer = !liveRenderer }
        }
        LaunchedEffect(textInputVisible) {
            if (textInputVisible) { focus.requestFocus(); keyboard?.show() } else keyboard?.hide()
        }
        val status = when {
            state.manualControl && state.activeRunId != null || state.taskPhase == VirtualScreenTaskPhase.PAUSED -> AgentOverlayState(AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
            state.activeRunId != null -> runtime
            state.taskPhase == VirtualScreenTaskPhase.FAILED -> AgentOverlayState(AgentOverlayPhase.FAILED, status = AgentOverlayStatus.RunFailed)
            else -> AgentOverlayState(AgentOverlayPhase.FINISHED, status = AgentOverlayStatus.ResultReady)
        }
        EtaPreferenceTheme {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { context ->
                    VirtualScreenDisplayView(context, viewerId).also {
                        it.onInput = ::sendInput
                        it.onManualDown = { info -> VirtualScreenSession.beginManualGesture(viewerId, info) }
                        it.onLiveChanged = { live ->
                            liveRenderer = live
                            if (live) hasFrame = true
                            VirtualScreenSession.setViewerCapture(viewerId, !live)
                        }
                        it.onError = { code -> if (code == "DISPLAY_MIRROR_UNAVAILABLE") compatibilityRenderer = true else inputError = true }
                        it.setTarget(VirtualScreenSession.state.value.display, VirtualScreenSession.rootBridge.value)
                        it.setResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        it.contentDescription = context.getString(R.string.virtual_screen_touch_description)
                        surface = it
                    }
                }, update = { it.touchEnabled = !textInputVisible && !showHistory },
                    modifier = Modifier.fillMaxSize().padding(top = with(density) { contentTop.toDp() }))
                if (display != null) AgentOverlayGlow(state = status, corners = corners)
                if (display == null) Column(Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.virtual_screen_idle), color = Color.White, style = MiuixTheme.textStyles.headline2)
                    Text(stringResource(R.string.virtual_screen_empty_hint), color = Color.LightGray, style = MiuixTheme.textStyles.body2)
                    Button(onClick = ::finish) { Text(stringResource(R.string.virtual_screen_exit_viewer)) }
                }
                VirtualScreenControlOverlay(state, status, controlsExpanded, { controlsExpanded = it },
                    inputError = inputError || channelError != null && !channelError.orEmpty().startsWith("DISPLAY_"),
                    compatibilityRenderer = compatibilityRenderer,
                    onPause = { pauseAgent() },
                    onResume = ::resumeAgent,
                    onStop = { pauseAgent(stop = true) },
                    onSupplement = ::supplementAgent,
                    onKeyboard = { textInputVisible = true; controlsExpanded = false },
                    onHistory = { showHistory = true }, onExit = ::finish)
                if (textInputVisible) Row(Modifier.align(Alignment.BottomCenter).imePadding().fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceContainer).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(value = textDraft, onValueChange = { if (it.length <= 20000) textDraft = it },
                        label = stringResource(R.string.virtual_screen_text_placeholder), enabled = !textPending,
                        modifier = Modifier.weight(1f).focusRequester(focus), maxLines = 3)
                    IconButton(enabled = display != null && textDraft.isNotEmpty() && !textPending, onClick = {
                        keyboard?.hide(); focusManager.clearFocus(); display?.let { sendText(it.sessionId) }
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.virtual_screen_text_insert), modifier = Modifier.size(22.dp))
                    }
                }
            }
            VirtualScreenAppConflictDialog()
            EtaWindowDialog(show = showHistory, title = stringResource(R.string.virtual_screen_history), onDismissRequest = { showHistory = false }) {
                if (state.operations.isEmpty()) Text(stringResource(R.string.virtual_screen_history_empty), style = MiuixTheme.textStyles.body2)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.operations.asReversed(), key = { it.id }) { operation ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(operationLabel(operation.name), color = MiuixTheme.colorScheme.onSurface, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(operation.timestamp)) + " · " +
                                    stringResource(if (operation.manual) R.string.virtual_screen_history_manual else R.string.virtual_screen_history_agent),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                            }
                            Text(stringResource(when { operation.unconfirmed -> R.string.virtual_screen_operation_dispatched
                                    operation.success -> R.string.virtual_screen_operation_success
                                    else -> R.string.virtual_screen_operation_failed }),
                                color = if (operation.success) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                                style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        configureSystemBars()
        VirtualScreenSession.setViewerVisible(viewerId, true)
        surface?.setResumed(true)
    }

    override fun onPause() {
        VirtualScreenSession.setViewerVisible(viewerId, false)
        surface?.setResumed(false)
        surface?.clearFrame(); hasFrame = false; lastFrameId = 0
        super.onPause()
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, VirtualScreenViewerActivity::class.java)
            .setData(Uri.parse("eta-virtual-screen://viewer"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS)
    }
}

@Composable
private fun operationLabel(name: String): String = when (name) {
    "home" -> stringResource(R.string.virtual_screen_home)
    "menu", "switch_task" -> stringResource(R.string.virtual_screen_menu)
    "back" -> stringResource(R.string.action_back)
    "enter" -> toolDisplayName("press_key")
    "text" -> toolDisplayName("input_text")
    "create" -> stringResource(R.string.virtual_screen_started)
    else -> toolDisplayName(name)
}
