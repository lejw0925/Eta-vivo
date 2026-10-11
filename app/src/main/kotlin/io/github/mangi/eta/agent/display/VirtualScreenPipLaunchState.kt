package io.github.mangi.eta.agent.display

/** Rejects late Activity callbacks and remembers user dismissal for the current run. */
internal class VirtualScreenPipLaunchState {
    data class Target(val runId: String, val sessionId: String)

    var target: Target? = null
        private set
    var requestId: String? = null
        private set
    var ready = false
        private set
    var waitingForViewer = false
        private set
    private var dismissedRun: String? = null
    private var failed: Target? = null

    val canLaunch: Boolean
        get() = target != null && target?.runId != dismissedRun && target != failed &&
            requestId == null && !waitingForViewer
    val useFallback: Boolean get() = target != null && target == failed

    fun updateTarget(next: Target?) {
        if (next == target) return
        if (next?.runId != target?.runId) dismissedRun = null
        hide()
        target = next
        failed = null
        waitingForViewer = false
    }

    fun launch(id: String): Boolean {
        if (!canLaunch) return false
        requestId = id
        ready = false
        return true
    }

    fun owns(id: String): Boolean = id == requestId && target != null

    fun markReady(id: String): Boolean {
        if (!owns(id)) return false
        ready = true
        return true
    }

    fun dismiss(id: String) {
        if (!owns(id)) return
        dismissedRun = target?.runId
        hide()
    }

    fun fail(id: String) {
        if (!owns(id)) return
        failed = target
        hide()
    }

    fun openViewer(id: String): Boolean {
        if (!owns(id)) return false
        hide()
        waitingForViewer = true
        return true
    }

    fun viewerVisible() {
        waitingForViewer = false
        hide()
    }

    fun viewerLaunchFailed(): Boolean {
        if (!waitingForViewer) return false
        waitingForViewer = false
        failed = target
        return true
    }

    fun hide() {
        requestId = null
        ready = false
        waitingForViewer = false
    }

    fun reset() {
        hide()
        dismissedRun = null
        failed = null
        waitingForViewer = false
    }
}
