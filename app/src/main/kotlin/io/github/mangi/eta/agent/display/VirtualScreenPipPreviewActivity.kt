package io.github.mangi.eta.agent.display

import android.app.ActivityOptions
import android.app.PictureInPictureUiState
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A separate system-managed preview task; expanding opens the interactive virtual-screen viewer. */
internal class VirtualScreenPipPreviewActivity : ComponentActivity() {
    private val viewerId = "pip-${UUID.randomUUID()}"
    private var controller: VirtualScreenPipPreviewController? = null
    private var requestId = ""
    private var surface: VirtualScreenSurfaceView? = null
    private var capture: Job? = null
    private var visibility: Job? = null
    private var generation = 0L
    private var lastFrame = 0L
    private var started = false
    private var resumed = false
    private var enteredPip = false
    private var stashed = false
    private var reportedStashed = false
    private var hostClosing = false
    private var openingViewer = false
    private var enterRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(VirtualScreenPipPreviewController.EXTRA_REQUEST_ID).orEmpty()
        controller = VirtualScreenPipPreviewController.current
        if (controller?.attach(requestId, this) != true) {
            closeFromHost()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        surface = VirtualScreenSurfaceView(this).apply {
            touchEnabled = false
            setBackgroundColor(Color.BLACK)
            contentDescription = getString(R.string.virtual_screen_preview_open)
        }.also { setContentView(it) }
        lifecycleScope.launch {
            VirtualScreenSession.state.collect { state ->
                if (controller?.accepts(requestId) != true) {
                    closeFromHost()
                } else {
                    state.display?.let { setPictureInPictureParams(VirtualScreenPipPreviewController.params(this@VirtualScreenPipPreviewActivity, it)) }
                    surface?.showGesture(state.gesture)
                    updateCapture()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (isInPictureInPictureMode) enteredPictureInPicture()
        visibility = lifecycleScope.launch {
            while (isActive && started && !hostClosing) {
                updateVisibility()
                delay(250)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (enteredPip && !isInPictureInPictureMode) openViewer()
        else if (!enteredPip && !isInPictureInPictureMode && !enterRequested) {
            // Enter only once, after the independent preview task has resumed. This gives the
            // system Expand action a real Activity to restore instead of a service-only host.
            window.decorView.post {
                if (hostClosing || !resumed || enteredPip || enterRequested || controller?.accepts(requestId) != true) return@post
                enterRequested = true
                val display = VirtualScreenSession.state.value.display ?: return@post
                try {
                    if (!enterPictureInPictureMode(VirtualScreenPipPreviewController.params(this, display))) controller?.failed(requestId)
                } catch (error: RuntimeException) {
                    AndroidAgentLogger.warn("Virtual PiP entry unavailable: type=${error.javaClass.simpleName}")
                    controller?.failed(requestId)
                }
            }
        }
    }

    override fun onPause() {
        resumed = false
        // PiP remains visible while PAUSED. Only visibility, stash and session identity gate capture.
        super.onPause()
    }

    override fun onStop() {
        started = false
        visibility?.cancel(); visibility = null
        stopCapture()
        if (!hostClosing && !isChangingConfigurations) {
            if (!VirtualScreenPipPreviewController.screenAvailable(this)) controller?.hide()
            // System Close stops a PiP task before some ROMs clear isInPictureInPictureMode
            // or mark the Activity finishing. Treat a stopped, unlocked preview as dismissed.
            else controller?.dismissed(requestId)
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopCapture()
        visibility?.cancel(); visibility = null
        if (!hostClosing && !isChangingConfigurations && VirtualScreenPipPreviewController.screenAvailable(this)) controller?.dismissed(requestId)
        surface = null
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) enteredPictureInPicture()
        else if (enteredPip && resumed) openViewer()
        updateVisibility()
    }

    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        reportedStashed = pipState.isStashed
        updateVisibility()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        VirtualScreenSession.state.value.display?.let {
            setPictureInPictureParams(VirtualScreenPipPreviewController.params(this, it))
        }
    }

    private fun enteredPictureInPicture() {
        enteredPip = true
        controller?.markReady(requestId)
    }

    private fun openViewer() {
        if (hostClosing || openingViewer || controller?.accepts(requestId) != true) return
        openingViewer = true
        stopCapture()
        try {
            startActivity(VirtualScreenViewerActivity.newIntent(this),
                ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }.toBundle())
            controller?.openViewer(requestId)
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn("Virtual PiP viewer unavailable: type=${error.javaClass.simpleName}")
            controller?.failed(requestId)
        }
    }

    internal fun closeFromHost() {
        if (hostClosing) return
        hostClosing = true
        stopCapture()
        finishAndRemoveTask()
    }

    private fun shouldCapture(): Boolean = started && !hostClosing && !openingViewer &&
        isInPictureInPictureMode && !stashed && controller?.accepts(requestId) == true

    private fun updateVisibility() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val screen = windowManager.maximumWindowMetrics.bounds
        stashed = VirtualScreenPipVisibility.isStashed(reportedStashed, bounds.left, bounds.right, screen.left, screen.right)
        updateCapture()
    }

    private fun updateCapture() {
        if (!shouldCapture()) {
            // A tucked-away PiP keeps its last frame, without a lease or ongoing capture.
            stopCapture(clearFrame = !started || hostClosing || openingViewer || !isInPictureInPictureMode ||
                controller?.accepts(requestId) != true)
            return
        }
        if (capture?.isActive == true) return
        val token = ++generation
        VirtualScreenSession.setViewerVisible(viewerId, true, preview = true)
        capture = lifecycleScope.launch {
            try {
                while (isActive && token == generation && shouldCapture()) {
                    val pending = AtomicReference<Bitmap?>()
                    try {
                        val session = VirtualScreenSession.state.value.display?.sessionId
                        val afterFrame = lastFrame
                        val next = withContext(Dispatchers.IO) {
                            val result = VirtualScreenSession.observeForViewer(this@VirtualScreenPipPreviewActivity, afterFrame, viewerId)
                            val json = JSONObject(result.content)
                            val bitmap = result.images.firstOrNull()?.reference?.substringAfter("base64,")?.let {
                                val bytes = Base64.decode(it, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            pending.set(bitmap)
                            bitmap?.let { decoded ->
                                val display = VirtualScreenSession.state.value.display
                                if (display != null && display.sessionId == session && display.displayId == json.optInt("displayId") &&
                                    display.width == decoded.width && display.height == decoded.height && display.rotation == json.optInt("rotation")) {
                                    Frame(decoded, json.optLong("frameId"), display)
                                } else null
                            }
                        }
                        val currentSurface = surface
                        if (next != null && token == generation && shouldCapture() &&
                            VirtualScreenSession.state.value.display?.sessionId == session && currentSurface != null) {
                            currentSurface.setFrame(next.bitmap, next.display)
                            lastFrame = next.id
                            pending.set(null)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AndroidAgentLogger.warn("Virtual PiP preview frame unavailable: type=${error.javaClass.simpleName}")
                        delay(1_000)
                    } finally { pending.getAndSet(null)?.recycle() }
                    delay(VirtualScreenFrameCapturePolicy.FRAME_INTERVAL_MS)
                }
            } finally {
                if (token == generation) {
                    capture = null
                    VirtualScreenSession.setViewerVisible(viewerId, false, preview = true)
                    surface?.clearFrame()
                    lastFrame = 0
                }
            }
        }
    }

    private fun stopCapture(clearFrame: Boolean = true) {
        generation++
        capture?.cancel(); capture = null
        VirtualScreenSession.setViewerVisible(viewerId, false, preview = true)
        if (clearFrame) { surface?.clearFrame(); lastFrame = 0 }
    }

    private data class Frame(val bitmap: Bitmap, val id: Long, val display: VirtualDisplayInfo)
}
