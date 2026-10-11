package io.github.mangi.eta.agent.display

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

/** Old Activity callbacks cannot hide a newer viewer after recreation or navigation. */
internal class VirtualScreenViewerVisibility {
    private data class Viewer(val id: String, val capture: Boolean)
    private val viewer = AtomicReference<Viewer?>()
    private val previews = ConcurrentHashMap.newKeySet<String>()

    fun show(viewerId: String) { viewer.set(Viewer(viewerId, true)) }
    fun hide(viewerId: String): Boolean {
        while (true) {
            val current = viewer.get()?.takeIf { it.id == viewerId } ?: return false
            if (viewer.compareAndSet(current, null)) return true
        }
    }
    fun setCapture(viewerId: String, capture: Boolean): Boolean {
        while (true) {
            val current = viewer.get()?.takeIf { it.id == viewerId } ?: return false
            if (viewer.compareAndSet(current, current.copy(capture = capture))) return true
        }
    }
    fun showPreview(viewerId: String) { previews.add(viewerId) }
    fun hidePreview(viewerId: String): Boolean = previews.remove(viewerId)
    fun isCurrent(viewerId: String): Boolean = viewer.get()?.id == viewerId || viewerId in previews
    val fullViewerVisible: Boolean get() = viewer.get() != null
    val visible: Boolean get() = fullViewerVisible || previews.isNotEmpty()
    val captureVisible: Boolean get() = viewer.get()?.capture == true || previews.isNotEmpty()
}
