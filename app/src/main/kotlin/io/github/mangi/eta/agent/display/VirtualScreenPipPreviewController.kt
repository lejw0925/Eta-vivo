package io.github.mangi.eta.agent.display

import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Rational
import android.view.Display
import android.view.WindowManager
import io.github.mangi.eta.core.AndroidAgentLogger
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread coordinator owned by the execution service, with one outstanding PiP launch. */
internal class VirtualScreenPipPreviewController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) : AutoCloseable {
    private val launchState = VirtualScreenPipLaunchState()
    private var activity: WeakReference<VirtualScreenPipPreviewActivity>? = null
    private var timeout: Job? = null
    val ready: Boolean get() = launchState.ready
    val canLaunch: Boolean get() = launchState.canLaunch

    fun updateTarget(state: VirtualScreenViewerState) {
        val target = state.activeRunId?.let { run -> state.display?.let {
            VirtualScreenPipLaunchState.Target(run, it.sessionId)
        } }
        if (target != launchState.target) hide()
        launchState.updateTarget(target)
        if (state.fullViewerVisible) {
            hide()
            launchState.viewerVisible()
        }
    }

    fun reset() { hide(); launchState.reset() }

    fun launch() {
        val id = UUID.randomUUID().toString()
        if (!launchState.launch(id)) return
        activeController = WeakReference(this)
        try {
            val intent = Intent(context, VirtualScreenPipPreviewActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            // launchIntoPip requires a host Activity task; a service has no host to restore on
            // system Expand. Use a separate normal task and enter PiP once it has resumed.
            val options = ActivityOptions.makeBasic().apply {
                launchDisplayId = Display.DEFAULT_DISPLAY
            }
            context.startActivity(intent, options.toBundle())
            timeout = scope.launch {
                delay(3_000)
                if (launchState.owns(id) && !ready) {
                    AndroidAgentLogger.warn("Virtual PiP preview entry timed out")
                    failed(id)
                }
            }
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn("Virtual PiP preview launch unavailable: type=${error.javaClass.simpleName}")
            failed(id)
        }
    }

    fun attach(id: String, preview: VirtualScreenPipPreviewActivity): Boolean {
        if (!accepts(id)) return false
        activity = WeakReference(preview)
        return true
    }

    fun accepts(id: String): Boolean {
        val state = VirtualScreenSession.state.value
        return launchState.owns(id) && state.isAgentControlling && !state.fullViewerVisible &&
            state.activeRunId == launchState.target?.runId &&
            state.display?.sessionId == launchState.target?.sessionId && screenAvailable(context) && allowed(context)
    }

    fun markReady(id: String) {
        if (!accepts(id) || !launchState.markReady(id)) return
        timeout?.cancel(); timeout = null
        onChanged()
    }

    fun dismissed(id: String) {
        if (!launchState.owns(id)) return
        launchState.dismiss(id)
        detach()
        onChanged()
    }

    fun failed(id: String) {
        if (!launchState.owns(id)) return
        launchState.fail(id)
        detach()
        onChanged()
    }

    fun openViewer(id: String): Boolean {
        if (!accepts(id) || !launchState.openViewer(id)) return false
        detach()
        timeout = scope.launch {
            delay(3_000)
            if (launchState.viewerLaunchFailed()) onChanged()
        }
        onChanged()
        return true
    }

    fun hide() { launchState.hide(); detach() }

    private fun detach() {
        timeout?.cancel(); timeout = null
        val preview = activity?.get()
        activity = null
        if (current === this) activeController = null
        preview?.closeFromHost()
    }

    override fun close() { hide() }

    companion object {
        const val EXTRA_REQUEST_ID = "virtual_screen_pip_request"
        private var activeController: WeakReference<VirtualScreenPipPreviewController>? = null
        val current: VirtualScreenPipPreviewController? get() = activeController?.get()

        fun supported(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

        fun allowed(context: Context): Boolean = supported(context) && runCatching {
            context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE, context.applicationInfo.uid, context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        fun screenAvailable(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isInteractive &&
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

        fun params(context: Context, display: VirtualDisplayInfo): PictureInPictureParams {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val ratio = aspectRatio(bounds.width(), bounds.height(), display.width, display.height)
            return PictureInPictureParams.Builder().setAspectRatio(ratio)
                .setAutoEnterEnabled(false).setSeamlessResizeEnabled(true).build()
        }

        fun aspectRatio(viewWidth: Int, viewHeight: Int, width: Int, height: Int): Rational {
            val rotated = width > height && viewHeight > viewWidth
            val ratio = Rational(if (rotated) height else width, if (rotated) width else height)
            return when {
                !ratio.isFinite || ratio.toFloat() < 100f / 239 -> Rational(100, 239)
                ratio.toFloat() > 239f / 100 -> Rational(239, 100)
                else -> ratio
            }
        }
    }
}
