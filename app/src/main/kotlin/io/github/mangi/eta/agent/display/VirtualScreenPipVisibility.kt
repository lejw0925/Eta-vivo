package io.github.mangi.eta.agent.display

/** Some ROMs move the PiP task off-screen without delivering the public stash callback. */
internal object VirtualScreenPipVisibility {
    fun isStashed(reported: Boolean, left: Int, right: Int, screenLeft: Int, screenRight: Int): Boolean {
        if (reported) return true
        val width = right.toLong() - left
        if (width <= 0 || screenRight <= screenLeft) return false
        val visible = (minOf(right, screenRight).toLong() - maxOf(left, screenLeft)).coerceAtLeast(0)
        return visible * 2 < width
    }
}
