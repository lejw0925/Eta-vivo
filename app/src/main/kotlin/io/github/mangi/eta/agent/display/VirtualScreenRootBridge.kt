package io.github.mangi.eta.agent.display

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.view.MotionEvent
import android.view.SurfaceControl
import java.util.concurrent.atomic.AtomicBoolean

/** App-side capability for one Root display. All synchronous calls run off the UI thread. */
internal class VirtualScreenRootBridge(
    val sessionId: String,
    private val token: String,
    private val remote: IBinder,
    private val onGeometry: (Bundle) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onDispatched: (String) -> Unit,
    private val onDeath: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val death = IBinder.DeathRecipient { if (closed.compareAndSet(false, true)) onDeath() }
    val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(VirtualScreenBridgeWire.CALLBACK); return true }
            if (code !in VirtualScreenBridgeWire.GEOMETRY..VirtualScreenBridgeWire.INPUT_DISPATCHED) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(VirtualScreenBridgeWire.CALLBACK)
            check(Binder.getCallingUid() == 0) { "ROOT_REQUIRED" }
            if (code == VirtualScreenBridgeWire.GEOMETRY) {
                val geometry = requireNotNull(data.readBundle(javaClass.classLoader))
                data.enforceNoDataAvail()
                if (!closed.get()) onGeometry(geometry)
            } else if (code == VirtualScreenBridgeWire.INPUT_ERROR) {
                val error = data.readString().orEmpty()
                data.enforceNoDataAvail()
                if (!closed.get()) onFailure(error)
            } else {
                val action = data.readString().orEmpty()
                data.enforceNoDataAvail()
                if (!closed.get() && action in setOf("tap", "swipe", "long_press")) onDispatched(action)
            }
            return true
        }
    }

    init { remote.linkToDeath(death, 0) }

    val isAlive: Boolean get() = !closed.get() && remote.isBinderAlive

    fun attach(viewerId: String, parent: SurfaceControl, width: Int, height: Int): Bundle = call(VirtualScreenBridgeWire.ATTACH, {
        it.writeString(viewerId)
        parent.writeToParcel(it, 0)
        it.writeInt(width)
        it.writeInt(height)
    }) { requireNotNull(it.readBundle(javaClass.classLoader)) }.also(onGeometry)

    fun layout(viewerId: String, width: Int, height: Int): Bundle = call(VirtualScreenBridgeWire.LAYOUT, {
        it.writeString(viewerId); it.writeInt(width); it.writeInt(height)
    }) { requireNotNull(it.readBundle(javaClass.classLoader)) }

    fun detach(viewerId: String) = call(VirtualScreenBridgeWire.DETACH, { it.writeString(viewerId) }) { Unit }

    fun motion(viewerId: String, info: VirtualDisplayInfo, gestureId: Long, event: MotionEvent) {
        check(isAlive) { "VIRTUAL_BRIDGE_UNAVAILABLE" }
        VirtualScreenBridgeWire.send(remote, VirtualScreenBridgeWire.DISPLAY, VirtualScreenBridgeWire.MOTION) {
            it.writeString(token); it.writeString(viewerId)
            it.writeInt(info.width); it.writeInt(info.height); it.writeInt(info.rotation)
            it.writeLong(gestureId)
            event.writeToParcel(it, 0)
        }
    }

    fun cancel(viewerId: String, gestureId: Long) = call(VirtualScreenBridgeWire.CANCEL, {
        it.writeString(viewerId); it.writeLong(gestureId)
    }) { Unit }
    fun resume() = call(VirtualScreenBridgeWire.RESUME, {}) { Unit }
    fun takeover(viewerId: String) = call(VirtualScreenBridgeWire.TAKEOVER, { it.writeString(viewerId) }) { Unit }

    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        check(isAlive) { "VIRTUAL_BRIDGE_UNAVAILABLE" }
        return VirtualScreenBridgeWire.call(remote, VirtualScreenBridgeWire.DISPLAY, code, {
            it.writeString(token); write(it)
        }, read)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { remote.unlinkToDeath(death, 0) }
    }
}
