package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Compositor-backed live display, with the established JPEG renderer as a compatibility path. */
internal class VirtualScreenDisplayView(context: Context, private val viewerId: String) : FrameLayout(context), SurfaceHolder.Callback {
    private val live = SurfaceView(context).apply {
        if (Build.VERSION.SDK_INT >= 35) setRequestedFrameRate(120f)
        holder.addCallback(this@VirtualScreenDisplayView)
    }
    private val fallback = VirtualScreenSurfaceView(context).apply { touchEnabled = false }
    private val connection = Executors.newSingleThreadExecutor { Thread(it, "eta-viewer-surface").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private var info: VirtualDisplayInfo? = null
    private var bridge: VirtualScreenRootBridge? = null
    private var attachedBridge: VirtualScreenRootBridge? = null
    private var surfaceReady = false
    private var resumed = false
    private var disposed = false
    private var touchReadyAt = Long.MAX_VALUE
    private var gestureId = 0L
    private var gestureInfo: VirtualDisplayInfo? = null
    private var gestureBridge: VirtualScreenRootBridge? = null
    var isLive = false
        private set
    var touchEnabled = true
        set(value) { if (!value) cancelGesture(); field = value }
    var onInput: (String, JSONObject) -> Unit = { _, _ -> }
    var onManualDown: (VirtualDisplayInfo) -> Boolean = { true }
    var onLiveChanged: (Boolean) -> Unit = {}
    var onGeometry: (Bundle) -> Unit = {}
    var onError: (String) -> Unit = {}

    init {
        setBackgroundColor(Color.BLACK)
        addView(live, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fallback.onInput = { session, args -> onInput(session, args) }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setTarget(display: VirtualDisplayInfo?, nextBridge: VirtualScreenRootBridge?) {
        val geometryChanged = info?.sessionId != display?.sessionId ||
            display != null && info?.hasSameGeometry(display) != true
        val bridgeChanged = bridge !== nextBridge
        if (geometryChanged || bridgeChanged) {
            cancelGesture()
            touchReadyAt = Long.MAX_VALUE
            setLive(false)
            fallback.clearFrame()
        }
        info = display
        bridge = nextBridge?.takeIf { it.sessionId == display?.sessionId && it.isAlive }
        if (display == null) clearFrame()
        if (geometryChanged || bridgeChanged) attachSurface()
    }

    fun setResumed(value: Boolean) {
        if (resumed == value) return
        resumed = value
        touchReadyAt = Long.MAX_VALUE
        if (value) attachSurface() else { cancelGesture(); detachSurface() }
    }

    fun setFrame(bitmap: Bitmap, display: VirtualDisplayInfo) {
        if (isLive || info?.sessionId != display.sessionId || info?.hasSameGeometry(display) != true) {
            bitmap.recycle()
        } else {
            fallback.setFrame(bitmap, display)
            if (resumed && touchReadyAt == Long.MAX_VALUE) touchReadyAt = SystemClock.uptimeMillis()
        }
    }

    fun clearFrame() { fallback.clearFrame(); if (!isLive) { touchReadyAt = Long.MAX_VALUE; cancelGesture() } }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; attachSurface() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        runCatching { holder.surface.setFrameRate(120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        attachSurface()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; cancelGesture(); detachSurface() }

    private fun attachSurface() {
        if (disposed) return
        val current = bridge
        val display = info
        val parentSurface = if (surfaceReady) live.surfaceControl else null
        if (!resumed || current == null || display == null || parentSurface?.isValid != true || width <= 0 || height <= 0) {
            detachSurface(); return
        }
        val epoch = generation.incrementAndGet()
        val viewWidth = width; val viewHeight = height
        connection.execute {
            if (generation.get() != epoch) return@execute
            try {
                attachedBridge?.takeIf { it !== current }?.let { runCatching { it.detach(viewerId) } }
                val geometry = current.attach(viewerId, parentSurface, viewWidth, viewHeight)
                attachedBridge = current
                post {
                    if (!disposed && resumed && generation.get() == epoch && bridge === current) {
                        onGeometry(geometry)
                        setLive(geometry.getBoolean("attached"))
                    }
                }
            } catch (_: Exception) {
                post { if (!disposed && generation.get() == epoch) { setLive(false); onError("DISPLAY_MIRROR_UNAVAILABLE") } }
            }
        }
    }

    private fun detachSurface() {
        generation.incrementAndGet()
        setLive(false)
        if (!disposed) connection.execute {
            attachedBridge?.let { runCatching { it.detach(viewerId) } }
            attachedBridge = null
        }
    }

    private fun setLive(value: Boolean) {
        if (isLive == value) return
        cancelGesture()
        isLive = value
        touchReadyAt = if (value && resumed) SystemClock.uptimeMillis() else Long.MAX_VALUE
        fallback.visibility = if (value) GONE else VISIBLE
        if (value) fallback.clearFrame()
        onLiveChanged(value)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val display = info ?: return false
        if (!resumed || !touchEnabled) { cancelGesture(); return false }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && event.downTime < touchReadyAt) {
            // Opening/expanding the viewer may carry a touch that began before the app
            // frame was attached. Only a new touch on a ready frame takes over the run.
            cancelGesture()
            return true
        }
        if (!isLive) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                cancelGesture()
                if (!fallback.canStartGesture(display, event.x, event.y) || !onManualDown(display)) return false
            }
            fallback.touchEnabled = true
            return fallback.onTouchEvent(event)
        }
        val viewport = VirtualScreenViewport.fit(width, height, display.width, display.height) ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelGesture()
            if (viewport.toDisplay(event.x, event.y) == null || !onManualDown(display)) return false
            gestureInfo = display
            gestureBridge = bridge
            gestureId = gestureIds.incrementAndGet()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val captured = gestureInfo ?: return true
        val channel = gestureBridge ?: return true
        val mapped = event.toVirtualDisplay(viewport)
        try {
            channel.motion(viewerId, captured, gestureId, mapped)
        } catch (_: Exception) { cancelGesture(); onError("INPUT_DISPATCH_UNCONFIRMED") }
        finally { mapped.recycle() }
        if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            gestureInfo = null; gestureBridge = null
            parent?.requestDisallowInterceptTouchEvent(false)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        }
        return true
    }

    fun cancelGesture() {
        val channel = gestureBridge
        val gesture = gestureId
        gestureInfo = null; gestureBridge = null
        fallback.touchEnabled = false
        if (!disposed && channel != null) connection.execute { runCatching { channel.cancel(viewerId, gesture) } }
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDetachedFromWindow() {
        cancelGesture()
        detachSurface()
        disposed = true
        connection.shutdown()
        fallback.clearFrame()
        super.onDetachedFromWindow()
    }

    private companion object { val gestureIds = AtomicLong() }
}
