package io.github.mangi.eta.agent.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenPipLaunchStateTest {
    private val fixtureTarget = VirtualScreenPipLaunchState.Target("run-a", "display-a")
    private fun launched() = VirtualScreenPipLaunchState().apply {
        updateTarget(fixtureTarget)
        assertTrue(launch("request-a"))
    }

    @Test
    fun outstandingLaunchCannotBeRepeatedAndLateAcknowledgmentsAreRejected() {
        val state = launched()
        assertFalse(state.launch("request-b"))
        assertFalse(state.markReady("request-b"))
        assertTrue(state.markReady("request-a"))
        assertTrue(state.ready)
        state.hide()
        assertFalse(state.markReady("request-a"))
        assertTrue(state.canLaunch)
    }

    @Test
    fun userDismissalSuppressesSameRunEvenWhenTemporarilyHidden() {
        val state = launched()
        state.dismiss("request-a")
        state.hide()
        state.updateTarget(fixtureTarget)
        assertFalse(state.canLaunch)
        state.updateTarget(fixtureTarget.copy(runId = "run-b"))
        assertTrue(state.canLaunch)
    }

    @Test
    fun explicitPreferenceChangeCanRestoreDismissedPreview() {
        val state = launched()
        state.dismiss("request-a")
        state.reset()
        assertTrue(state.canLaunch)
    }

    @Test
    fun displayReplacementDoesNotOverrideUserDismissalInSameRun() {
        val state = launched()
        state.dismiss("request-a")
        state.updateTarget(fixtureTarget.copy(sessionId = "display-b"))
        assertFalse(state.canLaunch)
    }

    @Test
    fun startupFailureStopsWithoutAnActivityLaunchLoop() {
        val state = launched()
        state.fail("request-a")
        assertFalse(state.canLaunch)
        assertFalse(state.markReady("request-a"))
        state.updateTarget(fixtureTarget.copy(sessionId = "display-b"))
        assertTrue(state.canLaunch)
    }

    @Test
    fun oldActivityCannotDismissOrAcknowledgeNewSession() {
        val state = launched()
        state.updateTarget(fixtureTarget.copy(sessionId = "display-b"))
        assertTrue(state.launch("request-b"))
        state.dismiss("request-a")
        state.fail("request-a")
        assertFalse(state.markReady("request-a"))
        assertTrue(state.markReady("request-b"))
    }

    @Test
    fun expandingWaitsForFullViewerThenAllowsPreviewOnReturn() {
        val state = launched()
        assertTrue(state.openViewer("request-a"))
        assertFalse(state.canLaunch)
        assertFalse(state.markReady("request-a"))
        state.viewerVisible()
        assertTrue(state.canLaunch)
    }

    @Test
    fun endedTaskRejectsAllPendingCallbacks() {
        val state = launched()
        state.updateTarget(null)
        assertFalse(state.canLaunch)
        assertFalse(state.markReady("request-a"))
        assertFalse(state.openViewer("request-a"))
    }

    @Test
    fun rejectedViewerLaunchFallsBackInsteadOfWaitingForever() {
        val state = launched()
        assertTrue(state.openViewer("request-a"))
        assertTrue(state.viewerLaunchFailed())
        assertFalse(state.canLaunch)
        assertFalse(state.viewerLaunchFailed())
    }

    @Test
    fun temporaryHideDuringExpansionCancelsViewerWait() {
        val state = launched()
        assertTrue(state.openViewer("request-a"))
        state.hide()
        assertTrue(state.canLaunch)
        assertFalse(state.viewerLaunchFailed())
    }
}
