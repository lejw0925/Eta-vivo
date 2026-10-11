package io.github.mangi.eta.agent.display

import android.annotation.SuppressLint
import android.content.Context
import android.content.AttributionSource
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** UID 0 only. Mirrors our own display; never accepts a display ID from the viewer. */
@SuppressLint("BlockedPrivateApi")
internal class RootVirtualScreenBridge(
    private val context: Context,
    private val display: Display,
    private val handler: Handler,
    private val sessionId: String,
    private val token: String,
    private val etaUid: Int,
    private val onMotion: (String, VirtualDisplayInfo, Long, MotionEvent) -> Unit,
    private val onCancel: (Long) -> Unit,
    private val onResume: () -> Unit,
    private val onTakeover: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val connectionExecutor = Executors.newSingleThreadExecutor { Thread(it, "eta-root-bridge-bind").apply { isDaemon = true } }
    private var callback: IBinder? = null
    private var mirror: SurfaceControl? = null
    private var parent: SurfaceControl? = null
    private var viewerId: String? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var activityManager: Any? = null
    private var providerAcquired = false
    @Volatile private var bootstrapFailure: String? = null
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) { if (displayId == display.displayId) close() }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == display.displayId) synchronized(lock) {
                if (!closed.get()) {
                    onCancel(0)
                    runCatching { applyLayout() }.onFailure { reportFailure("DISPLAY_LAYOUT_UNAVAILABLE") }
                    publishGeometry()
                }
            }
        }
    }
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(VirtualScreenBridgeWire.DISPLAY); return true }
            if (code !in VirtualScreenBridgeWire.ATTACH..VirtualScreenBridgeWire.TAKEOVER) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(VirtualScreenBridgeWire.DISPLAY)
            check(Binder.getCallingUid() == etaUid && data.readString() == token && !closed.get()) { "DISPLAY_OWNER_MISMATCH" }
            if (code == VirtualScreenBridgeWire.MOTION) {
                val viewer = data.readString().orEmpty()
                val width = data.readInt(); val height = data.readInt(); val rotation = data.readInt()
                val gestureId = data.readLong()
                val event = MotionEvent.CREATOR.createFromParcel(data)
                try {
                    data.enforceNoDataAvail()
                    synchronized(lock) {
                        require(viewerId == viewer && mirror != null) { "VIEWER_HIDDEN" }
                        val info = geometryInfo()
                        require(info.width == width && info.height == height && info.rotation == rotation) { "STALE_OBSERVATION" }
                        onMotion(viewer, info, gestureId, event)
                    }
                } catch (failure: Exception) {
                    onCancel(gestureId)
                    reportFailure(failure.message?.takeIf { it in setOf("VIEWER_HIDDEN", "STALE_OBSERVATION") } ?: "INPUT_DISPATCH_UNCONFIRMED")
                } finally { event.recycle() }
                return true
            }
            synchronized(lock) {
                when (code) {
                    VirtualScreenBridgeWire.ATTACH -> {
                        val viewer = data.readString().orEmpty()
                        val nextParent = SurfaceControl.CREATOR.createFromParcel(data)
                        val width = data.readInt(); val height = data.readInt()
                        data.enforceNoDataAvail()
                        require(viewer.isNotBlank() && width in 1..16_384 && height in 1..16_384 && nextParent.isValid) { "INVALID_SURFACE" }
                        detach()
                        try {
                            mirror = createMirror()
                            parent = nextParent
                            viewerId = viewer
                            viewWidth = width; viewHeight = height
                            applyLayout()
                        } catch (failure: Exception) { if (parent !== nextParent) nextParent.release(); detach(); throw failure }
                        reply?.writeNoException(); reply?.writeBundle(geometry())
                    }
                    VirtualScreenBridgeWire.LAYOUT -> {
                        require(data.readString() == viewerId) { "VIEWER_HIDDEN" }
                        val width = data.readInt(); val height = data.readInt()
                        data.enforceNoDataAvail()
                        require(width in 1..16_384 && height in 1..16_384) { "INVALID_SURFACE" }
                        onCancel(0)
                        viewWidth = width; viewHeight = height
                        applyLayout()
                        reply?.writeNoException(); reply?.writeBundle(geometry())
                    }
                    VirtualScreenBridgeWire.DETACH -> {
                        val viewer = data.readString()
                        data.enforceNoDataAvail()
                        if (viewer == viewerId) detach()
                        reply?.writeNoException()
                    }
                    VirtualScreenBridgeWire.CANCEL -> {
                        val viewer = data.readString()
                        val gestureId = data.readLong()
                        data.enforceNoDataAvail()
                        if (viewer == viewerId) onCancel(gestureId)
                        reply?.writeNoException()
                    }
                    VirtualScreenBridgeWire.RESUME -> {
                        data.enforceNoDataAvail(); onCancel(0); onResume(); reply?.writeNoException()
                    }
                    VirtualScreenBridgeWire.TAKEOVER -> {
                        val viewer = data.readString()
                        // A compatibility viewer has no mirror. This only cancels automated
                        // input; streamed motion still requires an attached, matching viewer.
                        require(!viewer.isNullOrBlank() && (viewerId == null || viewerId == viewer)) { "VIEWER_HIDDEN" }
                        data.enforceNoDataAvail(); onTakeover(); reply?.writeNoException()
                    }
                }
            }
            return true
        }
    }
    init {
        require(display.displayId > 0 && etaUid > 0 && token.length >= 32)
        displayManager.registerDisplayListener(listener, handler)
        connectionExecutor.execute {
            runCatching { register() }.onFailure {
                bootstrapFailure = (it.cause ?: it).javaClass.simpleName
            }
        }
    }

    // app_process has no AMS ApplicationThread, so use the external-provider API.
    // The provider is private and independently checks UID 0 and the session nonce.
    private fun register() {
        val manager = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService").invoke(null)
        activityManager = manager
        val type = Class.forName("android.app.IActivityManager")
        val holder = requireNotNull(type.getMethod("getContentProviderExternal", String::class.java,
            Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(manager, VirtualScreenBridgeWire.AUTHORITY, etaUid / 100_000, binder, "Eta:virtualscreen"))
        providerAcquired = true
        val provider = holder.javaClass.getField("provider").get(holder)
        val args = Bundle().apply {
            putString("sessionId", sessionId); putString("token", token); putBinder("display", binder)
        }
        val result = Class.forName("android.content.IContentProvider").getMethod("call", AttributionSource::class.java,
            String::class.java, String::class.java, String::class.java, Bundle::class.java)
            .invoke(provider, AttributionSource.Builder(0).build(), VirtualScreenBridgeWire.AUTHORITY,
                "register", null, args) as Bundle
        synchronized(lock) {
            if (closed.get()) return
            callback = requireNotNull(result.getBinder("callback"))
            callback?.linkToDeath({ close() }, 0)
            publishGeometry()
        }
    }

    fun describe() = org.json.JSONObject().put("registered", callback != null)
        .put("attached", mirror != null).put("bootstrapError", bootstrapFailure ?: org.json.JSONObject.NULL)

    private fun geometryInfo(): VirtualDisplayInfo {
        @Suppress("DEPRECATION")
        val size = Point().also { display.getRealSize(it) }
        return VirtualDisplayInfo(sessionId, "", display.displayId, size.x, size.y, rotation = display.rotation)
    }

    private fun geometry(): Bundle = geometryInfo().let { info -> Bundle().apply {
        putInt("displayId", info.displayId); putInt("width", info.width); putInt("height", info.height)
        putInt("rotation", info.rotation); putFloat("refreshRate", display.refreshRate)
        putBoolean("attached", mirror != null)
    } }

    private fun createMirror(): SurfaceControl {
        val wm = Class.forName("android.view.WindowManagerGlobal").getDeclaredMethod("getWindowManagerService").invoke(null)
        val result = SurfaceControl::class.java.getDeclaredConstructor().newInstance()
        try {
            val ok = Class.forName("android.view.IWindowManager").getMethod("mirrorDisplay", Int::class.javaPrimitiveType, SurfaceControl::class.java)
                .invoke(wm, display.displayId, result) as Boolean
            check(ok && result.isValid) { "DISPLAY_MIRROR_UNAVAILABLE" }
            return result
        } catch (failure: Exception) { result.release(); throw failure }
    }

    private fun applyLayout() {
        val layer = mirror ?: return
        val info = geometryInfo()
        val viewport = requireNotNull(VirtualScreenViewport.fit(viewWidth, viewHeight, info.width, info.height))
        SurfaceControl.Transaction().use { transaction ->
            transaction.reparent(layer, parent).setLayer(layer, 1)
                .setCrop(layer, Rect(0, 0, info.width, info.height))
                .setFrameRate(layer, 120f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                .setVisibility(layer, true)
            val scale = viewport.scale
            val matrix = if (viewport.rotatesClockwise) floatArrayOf(0f, scale, -scale, 0f) else floatArrayOf(scale, 0f, 0f, scale)
            SurfaceControl.Transaction::class.java.getMethod("setMatrix", SurfaceControl::class.java,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                .invoke(transaction, layer, matrix[0], matrix[1], matrix[2], matrix[3])
            transaction.setPosition(layer, viewport.left + if (viewport.rotatesClockwise) info.height * scale else 0f, viewport.top)
            transaction.apply()
        }
    }

    private fun detach() {
        onCancel(0)
        mirror?.let { layer ->
            runCatching { SurfaceControl.Transaction().use { it.setVisibility(layer, false).reparent(layer, null).apply() } }
            layer.release()
        }
        parent?.release()
        mirror = null; parent = null; viewerId = null
    }

    private fun publishGeometry() { callback?.let { target -> runCatching {
        VirtualScreenBridgeWire.send(target, VirtualScreenBridgeWire.CALLBACK, VirtualScreenBridgeWire.GEOMETRY) { it.writeBundle(geometry()) }
    } } }

    fun reportFailure(code: String) { callback?.let { target -> runCatching {
        VirtualScreenBridgeWire.send(target, VirtualScreenBridgeWire.CALLBACK, VirtualScreenBridgeWire.INPUT_ERROR) { it.writeString(code) }
    } } }

    fun reportDispatched(action: String) { callback?.let { target -> runCatching {
        VirtualScreenBridgeWire.send(target, VirtualScreenBridgeWire.CALLBACK, VirtualScreenBridgeWire.INPUT_DISPATCHED) { it.writeString(action) }
    } } }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { detach() }
        displayManager.unregisterDisplayListener(listener)
        if (providerAcquired) runCatching {
            Class.forName("android.app.IActivityManager").getMethod("removeContentProviderExternalAsUser",
                String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
                .invoke(activityManager, VirtualScreenBridgeWire.AUTHORITY, binder, etaUid / 100_000)
        }
        connectionExecutor.shutdownNow()
    }
}
