package io.github.mangi.eta.agent.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenPipVisibilityTest {
    @Test
    fun reportedStashStopsCaptureEvenBeforeWindowHasMoved() {
        assertTrue(VirtualScreenPipVisibility.isStashed(true, 782, 1160, 0, 1216))
    }

    @Test
    fun oemStashWithoutCallbackIsRecognizedAtEitherEdge() {
        assertTrue(VirtualScreenPipVisibility.isStashed(false, -328, 50, 0, 1216))
        assertTrue(VirtualScreenPipVisibility.isStashed(false, 1166, 1544, 0, 1216))
    }

    @Test
    fun visibleOrMostlyVisibleWindowResumesCapture() {
        assertFalse(VirtualScreenPipVisibility.isStashed(false, 782, 1160, 0, 1216))
        assertFalse(VirtualScreenPipVisibility.isStashed(false, -10, 368, 0, 1216))
    }

    @Test
    fun invalidWindowMetricsDoNotInventAStashState() {
        assertFalse(VirtualScreenPipVisibility.isStashed(false, 0, 0, 0, 1216))
        assertFalse(VirtualScreenPipVisibility.isStashed(false, 0, 378, 0, 0))
    }
}
