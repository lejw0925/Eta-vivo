package io.github.mangi.eta.validation

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.VirtualScreenDisplayView
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.agent.display.VirtualScreenViewport
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.ui.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Native IME and Recents integration on an account-free editor, without a model. */
internal class DeviceViewerImeValidation(
    private val instrumentation: Instrumentation,
    private val automation: UiAutomation,
    private val createTools: (String) -> AgentLocalTools,
    private val record: (String) -> Unit,
) {
    private val context = instrumentation.targetContext

    fun run(recentsOnly: Boolean = false) {
        createTools("viewer-ime-validation").use { tools ->
            call(tools, "create editor display", JSONObject().put("action", "create"))
            call(tools, "launch editor fixture", JSONObject().put("action", "launch")
                .put("component", "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"))
            val info = checkNotNull(VirtualScreenSession.state.value.display)
            eventually("editor lays out on virtual display") { probe().optInt("display") == info.displayId && probe().optInt("editorY") > 0 }
            main { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            val tasks = context.getSystemService(ActivityManager::class.java)
            fun recentTasks() = tasks.appTasks.mapNotNull { it.taskInfo }
            eventually("Eta task available") { recentTasks().any { it.baseActivity?.className == MainActivity::class.java.name } }
            val etaTask = recentTasks().first { it.baseActivity?.className == MainActivity::class.java.name }.taskId
            val viewer = instrumentation.startActivitySync(VirtualScreenViewerActivity.newIntent(context),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()) as VirtualScreenViewerActivity
            try {
                main { viewer.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                eventually("native mirror ready for keyboard") { main { findView(viewer.window.decorView)?.isLive == true } }
                verify("Eta and viewer have different task IDs", viewer.taskId != etaTask)
                verify("both task roots remain in Recents", recentTasks().any { it.taskId == etaTask } &&
                    recentTasks().any { it.taskId == viewer.taskId && it.baseActivity?.className == viewer.javaClass.name })
                verify("viewer uses a document task with stable identity", recentTasks().first { it.taskId == viewer.taskId }
                    .baseIntent.let { it.flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0 && it.dataString == "eta-virtual-screen://viewer" })
                verify("viewer launch leaves agent running", !VirtualScreenSession.state.value.manualControl &&
                    VirtualScreenSession.state.value.isAgentControlling)
                if (recentsOnly) {
                    shell("input keyevent KEYCODE_APP_SWITCH")
                    SystemClock.sleep(1500)
                    val title = context.getString(R.string.virtual_screen_title)
                    val seen = mutableSetOf<String>()
                    val host = main { viewer.windowManager.currentWindowMetrics.bounds }
                    repeat(6) { page ->
                        val matches = mutableListOf<String>()
                        fun scan(node: AccessibilityNodeInfo) {
                            val label = node.text?.toString()
                            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                            if (node.viewIdResourceName == "com.vivo.recents:id/label" &&
                                label in setOf("Eta", title) && !bounds.isEmpty && android.graphics.Rect.intersects(bounds, host)) {
                                seen += checkNotNull(label)
                                matches += "$label:$bounds"
                            }
                            for (index in 0 until node.childCount) node.getChild(index)?.let(::scan)
                        }
                        automation.rootInActiveWindow?.let(::scan)
                        record("METRIC own_recents_page=$page,labels=$matches")
                        if (matches.isNotEmpty()) {
                            val image = checkNotNull(automation.takeScreenshot())
                            try { File(context.cacheDir, "viewer-recents-$page.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            finally { image.recycle() }
                        }
                        if (!seen.containsAll(setOf("Eta", title))) {
                            val fromX = if (page == 0) host.width() * 4 / 5 else host.width() / 5
                            val toX = if (page == 0) host.width() / 5 else host.width() * 4 / 5
                            shell("input swipe $fromX ${host.height() / 2} $toX ${host.height() / 2} 350")
                            SystemClock.sleep(700)
                        }
                    }
                    verify("OriginOS shows Eta and viewer as separate labeled cards", seen.containsAll(setOf("Eta", title)))
                    record("RECENTS_CAPTURED")
                    SystemClock.sleep(3000)
                    shell("input keyevent KEYCODE_BACK")
                    eventually("returning from Recents restores mirror") { main { findView(viewer.window.decorView)?.isLive == true } }
                    verify("viewing Recents leaves task running", !VirtualScreenSession.state.value.manualControl)
                } else {
                    val view = checkNotNull(main { findView(viewer.window.decorView) })
                    val viewport = main { checkNotNull(VirtualScreenViewport.fit(view.width, view.height, info.width, info.height)) }
                    val location = IntArray(2).also { main { view.getLocationOnScreen(it) } }
                    tap(viewport, location, probe().getInt("editorX"), probe().getInt("editorY"))
                    capture(viewer, view, "viewer-native-keyboard")
                    record("METRIC editor_focused=${probe().optBoolean("editorFocused")},window_focused=${probe().optBoolean("windowFocused")}")
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    var nativeIme = false
                    while (!nativeIme && SystemClock.elapsedRealtime() < deadline) {
                        val state = shell("dumpsys input_method")
                        nativeIme = Regex("mCurTokenDisplayId=${info.displayId}(?:\\s|$)").containsMatchIn(state) && imeShown(state)
                        if (!nativeIme) SystemClock.sleep(200)
                    }
                    record("PROBE automatic_local_ime=$nativeIme")
                    if (nativeIme) {
                        capture(viewer, view, "viewer-native-keyboard")
                        shell("input -d ${info.displayId} text eta120")
                        eventually("native editor accepts text on its own display") { probe().optString("text").contains("eta120") }
                        main { viewer.onBackPressedDispatcher.onBackPressed() }
                        eventually("system back dismisses virtual keyboard") { !imeShown(shell("dumpsys input_method")) }
                    } else {
                        // OWN_FOCUS keeps UI/key events scoped, but IMMS also requires the
                        // globally top focused display. Keep the working host input entry.
                        record("LIMITATION local IME unavailable without transferring global display focus")
                        click(context.getString(R.string.virtual_screen_controls))
                        eventually("keyboard fallback remains available") { node(context.getString(R.string.virtual_screen_text_toggle)) != null }
                        click(context.getString(R.string.virtual_screen_text_toggle))
                        eventually("keyboard entry opens host IME automatically") {
                            val state = shell("dumpsys input_method")
                            Regex("mCurTokenDisplayId=0(?:\\s|$)").containsMatchIn(state) && imeShown(state)
                        }
                        shell("input text eta120")
                        eventually("host draft receives typing") { node("eta120") != null }
                        click(context.getString(R.string.virtual_screen_text_insert))
                        eventually("host keyboard inserts into virtual editor") { probe().optString("text").contains("eta120") }
                        capture(viewer, view, "viewer-keyboard-fallback")
                        main { viewer.onBackPressedDispatcher.onBackPressed() }
                    }
                    verify("keyboard keeps editor on virtual display", probe().optInt("display") == info.displayId)
                }
                verify("top status bar remains visible", main {
                    androidx.core.view.ViewCompat.getRootWindowInsets(viewer.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) == true
                })
            } finally { main { viewer.finish() } }
            call(tools, "close editor display", JSONObject().put("action", "close"))
        }
    }

    private fun capture(viewer: VirtualScreenViewerActivity, view: VirtualScreenDisplayView, name: String) {
        verify("viewer permits screenshots by default", main { viewer.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 })
        SystemClock.sleep(250)
        val bitmap = checkNotNull(automation.takeScreenshot())
        try { File(context.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun tap(viewport: VirtualScreenViewport, location: IntArray, x: Int, y: Int) {
        val point = viewport.toView(x, y)
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, point.x + location[0], point.y + location[1], 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                HiddenApiBypass.invoke(android.view.InputEvent::class.java, event, "setDisplayId", Display.DEFAULT_DISPLAY)
                verify("editor/keyboard touch delivered $action", automation.injectInputEvent(event, true))
            } finally { event.recycle() }
            SystemClock.sleep(50)
        }
    }

    private fun imeShown(state: String): Boolean = Regex("mInputShown=true|mImeWindowVis=0x3|mImeWindowVis=3").containsMatchIn(state)

    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.contentDescription?.toString() == label || current.text?.toString() == label) return current
            for (index in 0 until current.childCount) current.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return automation.rootInActiveWindow?.let(::find)?.also { it.refresh() }
    }

    private fun click(label: String) {
        val target = checkNotNull(node(label)) { "Missing control: $label" }
        var button: AccessibilityNodeInfo? = target
        while (button != null && !button.isClickable) button = button.parent
        verify("control accessible: $label", button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private fun findView(view: View): VirtualScreenDisplayView? {
        if (view is VirtualScreenDisplayView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun probe(): JSONObject = context.contentResolver.query(Uri.parse("content://io.github.mangi.eta.test.display_probe/state"),
        null, null, null, null)?.use { if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject() } ?: JSONObject()

    private fun call(tools: AgentLocalTools, label: String, args: JSONObject) {
        val result = tools.execute(AgentModelClient.ToolCall("viewer-ime-${System.nanoTime()}", "virtual_screen", args.toString()))
        val response = JSONObject(result.content)
        verify("$label (${response.optString("code")})", response.optBoolean("ok"))
    }

    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }

    private fun eventually(label: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) { verify(label, true); return }
            SystemClock.sleep(100)
        }
        verify(label, condition())
    }

    private fun verify(label: String, value: Boolean) { check(value) { label }; record("PASS $label") }
}
