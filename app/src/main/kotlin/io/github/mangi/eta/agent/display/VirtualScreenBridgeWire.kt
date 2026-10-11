package io.github.mangi.eta.agent.display

import android.os.IBinder
import android.os.Parcel

/** Private, session-scoped Binder protocol. Image bytes never travel over this channel. */
internal object VirtualScreenBridgeWire {
    const val AUTHORITY = "io.github.mangi.eta.virtualscreen.bridge"
    const val DISPLAY = "io.github.mangi.eta.virtualscreen.Display"
    const val CALLBACK = "io.github.mangi.eta.virtualscreen.Callback"
    const val ATTACH = IBinder.FIRST_CALL_TRANSACTION
    const val DETACH = ATTACH + 1
    const val LAYOUT = ATTACH + 2
    const val MOTION = ATTACH + 3
    const val CANCEL = ATTACH + 4
    const val RESUME = ATTACH + 5
    const val TAKEOVER = ATTACH + 6
    const val GEOMETRY = IBinder.FIRST_CALL_TRANSACTION
    const val INPUT_ERROR = GEOMETRY + 1
    const val INPUT_DISPATCHED = GEOMETRY + 2

    fun <T> call(binder: IBinder, descriptor: String, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            check(binder.transact(code, data, reply, 0)) { "VIRTUAL_BRIDGE_UNAVAILABLE" }
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun send(binder: IBinder, descriptor: String, code: Int, write: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            check(binder.transact(code, data, null, IBinder.FLAG_ONEWAY)) { "VIRTUAL_BRIDGE_UNAVAILABLE" }
        } finally { data.recycle() }
    }
}
