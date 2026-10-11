package io.github.mangi.eta.agent.display

import kotlin.math.max

/** Saved independently of pixel dimensions so a dock survives rotation and keyboard resize. */
internal data class OverlayDockPosition(val right: Boolean = true, val fraction: Float = 0.3f) {
    data class Point(val x: Float, val y: Float)

    fun origin(width: Float, height: Float, controlWidth: Float, controlHeight: Float,
        top: Float = 0f, bottom: Float = 0f, margin: Float = 0f): Point {
        val maxX = max(0f, width - controlWidth)
        val minY = top.coerceIn(0f, max(0f, height - controlHeight))
        val maxY = max(minY, height - bottom - controlHeight)
        return Point(if (right) (maxX - margin).coerceAtLeast(0f) else margin.coerceAtMost(maxX),
            minY + fraction.coerceIn(0f, 1f) * (maxY - minY))
    }

    companion object {
        fun nearest(x: Float, y: Float, width: Float, height: Float, controlWidth: Float, controlHeight: Float,
            top: Float = 0f, bottom: Float = 0f): OverlayDockPosition {
            val minY = top.coerceIn(0f, max(0f, height - controlHeight))
            val range = max(0f, height - bottom - controlHeight - minY)
            return OverlayDockPosition(x + controlWidth / 2 >= width / 2,
                if (range > 0f) ((y - minY) / range).coerceIn(0f, 1f) else 0f)
        }
    }
}
