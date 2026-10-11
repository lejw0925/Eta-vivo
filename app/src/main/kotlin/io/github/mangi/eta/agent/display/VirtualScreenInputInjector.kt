package io.github.mangi.eta.agent.display

import android.view.InputEvent
import android.view.MotionEvent
import io.github.mangi.eta.agent.device.RootInputInjector
import io.github.mangi.eta.agent.device.SwipePath
import org.json.JSONObject

/** Virtual-screen callers may never inject into the primary display. */
internal class VirtualScreenInputInjector(
    displayId: Int,
    inputTimeoutMs: Long = 8_000,
    dispatch: ((InputEvent, Boolean) -> Boolean)? = null,
) : AutoCloseable {
    init {
        require(displayId > 0) { "INVALID_DISPLAY" }
    }

    private val delegate = RootInputInjector(displayId, inputTimeoutMs, dispatch)

    fun execute(vararg args: String): JSONObject = delegate.execute(*args)

    fun executePath(points: List<SwipePath.Point>, durationMs: Int): JSONObject = delegate.executePath(points, durationMs)

    fun cancelActive(): Boolean = delegate.cancelActive()
    fun injectRealtime(event: MotionEvent): Boolean = delegate.injectRealtime(event)

    override fun close() = delegate.close()
}
