package io.github.mangi.eta.validation

import android.app.Activity
import android.app.ActivityOptions
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.VirtualScreenPipPreviewController
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.screens.tasks.VirtualScreenSettingsScreen
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Exercises the actual system PiP task using a synthetic virtual app, without paid model calls. */
internal class DevicePipPreviewValidation(
    private val instrumentation: Instrumentation,
    private val automation: UiAutomation,
    private val toolsFactory: (String) -> AgentLocalTools,
    private val record: (String) -> Unit,
) {
    private val context = instrumentation.targetContext

    fun run() {
        verify("PiP feature and permission available", VirtualScreenPipPreviewController.allowed(context))
        verify("phone unlocked for preview", VirtualScreenPipPreviewController.screenAvailable(context))
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, io.github.mangi.eta.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }.toBundle())
        }
        SystemClock.sleep(500)
        // A different app owns the foreground; the execution service must perform the PiP handoff.
        instrumentation.runOnMainSync {
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }.toBundle())
        }
        SystemClock.sleep(1_000)
        runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenPreviewEnabled = true) } }
        toolsFactory("pip-preview-run").use { tools ->
            call(tools, "create PiP fixture display", JSONObject().put("action", "create"))
            call(tools, "launch changing PiP fixture", JSONObject().put("action", "launch")
                .put("component", "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}")
                .put("uri", "eta-validation://frames"))
            awaitPip()
            eventually("native PiP decodes live frames", { lastFrame() > 0 })
            eventually("native PiP activates capture lease", { capture().optBoolean("viewer_visible") })
            val preview = checkNotNull(activity())
            verify("native preview retains secure flag", main { preview.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 })
            val etaTask = foregroundEtaTaskId()
            verify("native preview owns independent task", etaTask >= 0 && main { preview.taskId != etaTask })
            verify("preview does not claim interactive viewer", !VirtualScreenSession.state.value.fullViewerVisible)
            val before = capture().getLong("encoded_frames")
            val start = SystemClock.elapsedRealtime()
            SystemClock.sleep(1_000)
            val frames = capture().getLong("encoded_frames") - before
            verify("native PiP supplies changing frames", frames >= 3)
            verify("native PiP stays within ten fps", frames <= (SystemClock.elapsedRealtime() - start) / 100 + 1)
            screenshot("native-pip.png")

            val initial = bounds()
            verify("native PiP can move above dismiss target", drag(initial.centerX().toFloat(), initial.centerX().toFloat(),
                initial.centerY().toFloat(), toY = 1_000f))
            eventually("native PiP moves to upper screen", { bounds().centerY() < 1_500 })
            SystemClock.sleep(1_000)
            val full = bounds()
            verify("native PiP uses portrait orientation", full.height() > full.width())
            verify("native preview can drag to edge", drag(full.right - 60f, 8f, full.centerY().toFloat()))
            SystemClock.sleep(800)
            eventually("system stashes native preview", { stashed() })
            eventually("stashed PiP releases capture lease", { !capture().optBoolean("viewer_visible") })
            assertIdle("stashed PiP does not encode frames")
            screenshot("native-pip-stashed.png")
            val tucked = bounds()
            verify("native preview can drag back into view", drag(tucked.right - 16f, full.width() * 2f, tucked.centerY().toFloat()))
            SystemClock.sleep(800)
            eventually("system unstashes native preview", { activity() != null && !stashed() })
            eventually("unstashed PiP resumes live frames", { lastFrame() > 0 && capture().optBoolean("viewer_visible") })

            SystemClock.sleep(1_000)
            val expandedBounds = bounds()
            verify("native PiP menu can open", tap(expandedBounds.centerX().toFloat(), expandedBounds.centerY().toFloat()))
            eventually("system expand button is available", { menuButton("expand_button") != null })
            verify("system expand button can be pressed", checkNotNull(menuButton("expand_button"))
                .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            eventually("expanding PiP opens interactive viewer", { VirtualScreenSession.state.value.fullViewerVisible })
            eventually("full viewer removes native preview", { activity() == null })
            eventually("full viewer retains capture lease", { capture().optBoolean("viewer_visible") })
            verify("full viewer can return", automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            awaitPip()

            runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenPreviewEnabled = false) } }
            eventually("preview switch removes PiP", { activity() == null })
            eventually("disabled preview releases capture lease", { !capture().optBoolean("viewer_visible") })
            assertIdle("disabled native preview stays idle")
            runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenPreviewEnabled = true) } }
            awaitPip()

            SystemClock.sleep(1_000)
            val closeBounds = bounds()
            verify("native PiP close menu can open", tap(closeBounds.centerX().toFloat(), closeBounds.centerY().toFloat()))
            eventually("system close button is available", { menuButton("dismiss") != null })
            verify("system close button can be pressed", checkNotNull(menuButton("dismiss"))
                .performAction(AccessibilityNodeInfo.ACTION_CLICK))
            eventually("closing native PiP releases capture", { activity() == null && !capture().optBoolean("viewer_visible") })
            SystemClock.sleep(3_500)
            verify("closed PiP does not reappear in same run", activity() == null)
            assertIdle("closed PiP stays idle")
            runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenPreviewEnabled = false) } }
            runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenPreviewEnabled = true) } }
            awaitPip()
            tools.retainVirtualScreenOnSuccess()
        }
        eventually("ending task removes native PiP", { activity() == null })
        verify("task end retains virtual display", VirtualScreenSession.isActive())
        eventually("retained display releases preview lease", { !capture().optBoolean("viewer_visible") })
        assertIdle("idle retained display stays idle")
        validateSettings()
    }

    private fun validateSettings() {
        val viewer = instrumentation.startActivitySync(Intent(context, VirtualScreenViewerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()) as VirtualScreenViewerActivity
        try {
            instrumentation.runOnMainSync {
                viewer.setContent { AgentAppTheme(AppearanceSettings(), applyInterfaceScale = true) {
                    VirtualScreenSettingsScreen(onBack = {})
                } }
            }
            val title = context.getString(R.string.virtual_screen_preview_enable)
            val summary = context.getString(R.string.virtual_screen_pip_summary)
            eventually("system PiP preview setting is visible", { textNode(title) != null && textNode(summary) != null })
            verify("preview can be disabled from UI", clickText(title))
            eventually("disabled preview persists from UI", { !runBlocking { SettingsDataStore.settings() }.virtualScreenPreviewEnabled })
            verify("preview can be enabled from UI", clickText(title))
            eventually("enabled preview persists from UI", { runBlocking { SettingsDataStore.settings() }.virtualScreenPreviewEnabled })
            SystemClock.sleep(500)
            instrumentation.runOnMainSync { viewer.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            SystemClock.sleep(200)
            val image = checkNotNull(automation.takeScreenshot())
            try {
                val area = main { viewer.windowManager.currentWindowMetrics.bounds }
                check(area.intersect(0, 0, image.width, image.height))
                val crop = Bitmap.createBitmap(image, area.left, area.top, area.width(), area.height())
                try {
                    val directory = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
                    File(directory, "preview-settings.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { crop.recycle() }
            } finally { image.recycle() }
        } finally {
            instrumentation.runOnMainSync { viewer.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); viewer.finish() }
        }
    }

    private fun textNode(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { match -> return match } }
            return null
        }
        return automation.windows.asSequence().mapNotNull { it.root }.filter { it.packageName == context.packageName }
            .mapNotNull(::find).firstOrNull()
    }

    private fun clickText(text: String): Boolean {
        var node = textNode(text)
        while (node != null) {
            if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent
        }
        return false
    }

    private fun <T> main(block: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun activity(): Activity? = main {
        val controller = VirtualScreenPipPreviewController.current ?: return@main null
        (controller.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(controller) as? WeakReference<*>)
            ?.get() as? Activity
    }

    private fun foregroundEtaTaskId(): Int = context.getSystemService(android.app.ActivityManager::class.java)
        .appTasks.mapNotNull { it.taskInfo }.firstOrNull { it.baseActivity?.className == "io.github.mangi.eta.ui.MainActivity" }?.taskId ?: -1

    private fun stashed(): Boolean = activity()?.let { preview -> main {
        preview.javaClass.getDeclaredField("stashed").apply { isAccessible = true }.getBoolean(preview)
    } } ?: false

    private fun lastFrame(): Long = activity()?.let { preview -> main {
        preview.javaClass.getDeclaredField("lastFrame").apply { isAccessible = true }.getLong(preview)
    } } ?: 0

    private fun bounds(): Rect {
        // PiP transforms a task surface. DecorView coordinates describe the unscaled task;
        // accessibility window bounds describe the actual system-managed window on screen.
        val window = automation.windows.firstOrNull { it.isInPictureInPictureMode && it.root?.packageName == context.packageName }
        return Rect().also { checkNotNull(window) { "Native PiP accessibility window unavailable" }.getBoundsInScreen(it) }
    }

    private fun awaitPip() = eventually("native PiP visible on primary display", {
        activity()?.let { preview -> main { preview.isInPictureInPictureMode && preview.display?.displayId == 0 } } == true
    })

    private fun menuButton(id: String): android.view.accessibility.AccessibilityNodeInfo? {
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (node.viewIdResourceName?.endsWith("/$id") == true) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child -> find(child)?.let { return it } }
            }
            return null
        }
        return automation.windows.asSequence().filter { it.isInPictureInPictureMode }
            .mapNotNull { it.root?.let(::find) }.firstOrNull()
    }

    private fun capture() = JSONObject(VirtualScreenSession.execute(context, "device-validation",
        JSONObject().put("action", "probe"), manual = true).content).getJSONObject("capture")

    private fun assertIdle(label: String) {
        val count = capture().getLong("encoded_frames")
        SystemClock.sleep(500)
        verify(label, capture().getLong("encoded_frames") == count)
    }

    private fun call(tools: AgentLocalTools, label: String, args: JSONObject) {
        val result = tools.execute(AgentModelClient.ToolCall("pip-${System.nanoTime()}", "virtual_screen", args.toString()))
        verify(label, JSONObject(result.content).optBoolean("ok"))
    }

    private fun verify(label: String, value: Boolean) {
        check(value) { label }
        record("PASS $label")
    }

    private fun eventually(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) { verify(label, true); return }
            SystemClock.sleep(100)
        }
        verify(label, condition())
    }

    private fun drag(fromX: Float, toX: Float, y: Float, stepMs: Long = 16, toY: Float = y): Boolean {
        val down = SystemClock.uptimeMillis()
        if (!event(down, MotionEvent.ACTION_DOWN, fromX, y, down)) return false
        for (step in 1..30) {
            SystemClock.sleep(stepMs)
            if (!event(down, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * step / 30,
                y + (toY - y) * step / 30, SystemClock.uptimeMillis())) return false
        }
        return event(down, MotionEvent.ACTION_UP, toX, toY, SystemClock.uptimeMillis())
    }

    private fun tap(x: Float, y: Float): Boolean {
        val down = SystemClock.uptimeMillis()
        if (!event(down, MotionEvent.ACTION_DOWN, x, y, down)) return false
        SystemClock.sleep(40)
        return event(down, MotionEvent.ACTION_UP, x, y, SystemClock.uptimeMillis())
    }

    private fun event(down: Long, action: Int, x: Float, y: Float, time: Long): Boolean {
        val pointer = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(down, time, action, 1, pointer, coordinates, 0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try {
            event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, 0)
            automation.injectInputEvent(event, true)
        } finally { event.recycle() }
    }

    private fun screenshot(name: String) {
        // Temporarily unsecure only the synthetic fixture preview; crop out every other window.
        val preview = checkNotNull(activity())
        try {
            instrumentation.runOnMainSync { preview.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            SystemClock.sleep(250)
            val image = checkNotNull(automation.takeScreenshot())
            try {
                val area = bounds()
                check(area.intersect(0, 0, image.width, image.height)) {
                    "PiP fixture bounds $area do not intersect ${image.width}x${image.height}"
                }
                val crop = Bitmap.createBitmap(image, area.left, area.top, area.width(), area.height())
                try {
                    val directory = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
                    File(directory, name).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { crop.recycle() }
            } finally { image.recycle() }
        } finally {
            instrumentation.runOnMainSync { preview.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
}
