package io.github.mangi.eta.agent.display

import org.junit.Assert.*
import org.junit.Test

class OverlayDockPositionTest {
    @Test fun dockAvoidsCutoutAndKeyboardAndSurvivesRotation() {
        val position = OverlayDockPosition(true, 1f)
        assertEquals(OverlayDockPosition.Point(696f, 536f), position.origin(720f, 800f, 24f, 64f, 40f, 200f))
        assertEquals(OverlayDockPosition.Point(1576f, 456f), position.origin(1600f, 720f, 24f, 64f, 40f, 200f))
        val expanded = position.origin(720f, 800f, 272f, 260f, 40f, 200f)
        assertTrue(expanded.x >= 0 && expanded.y + 260 <= 600)
    }
    @Test fun dragChoosesNearestEdgeAndClampsVerticalPosition() {
        assertEquals(OverlayDockPosition(false, 0f), OverlayDockPosition.nearest(-200f, -100f, 720f, 800f, 272f, 260f, 40f, 200f))
        assertEquals(OverlayDockPosition(true, 1f), OverlayDockPosition.nearest(600f, 2000f, 720f, 800f, 272f, 260f, 40f, 200f))
        val tiny = OverlayDockPosition(true, 0.5f).origin(100f, 100f, 272f, 260f, 40f, 200f)
        assertEquals(OverlayDockPosition.Point(0f, 0f), tiny)
    }
}
