package io.github.mangi.eta.validation

import android.app.ActivityOptions
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.mangi.eta.agent.display.VirtualScreenDisplayView
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.agent.display.VirtualScreenViewport
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import org.lsposed.hiddenapibypass.HiddenApiBypass

internal class DeviceRealtimeValidation(
    private val instrumentation: Instrumentation,
    private val automation: UiAutomation,
    private val createTools: (String) -> AgentLocalTools,
    private val steadySeconds: Int = 15,
    private val inputOnly: Boolean = false,
    private val record: (String) -> Unit,
) {
    private val context = instrumentation.targetContext
    fun run() {
        createTools("realtime-validation").use { tools ->
            call(tools, "create realtime display", JSONObject().put("action", "create"))
            call(tools, "launch realtime fixture", JSONObject().put("action", "launch")
                .put("component", "io.github.mangi.eta.test/${RealtimeProbeActivity::class.java.name}"))
            try {
                eventually("Root Binder channel registered") { VirtualScreenSession.rootBridge.value?.isAlive == true }
            } catch (failure: Exception) {
                record("METRIC bridge_probe=" + VirtualScreenSession.execute(context, "device-validation",
                    JSONObject().put("action", "probe"), manual = true).content)
                throw failure
            }
            val viewer = instrumentation.startActivitySync(VirtualScreenViewerActivity.newIntent(context),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
                as VirtualScreenViewerActivity
            try {
                // Long performance runs must keep this synthetic fixture visible without
                // changing the user's screen timeout or the production viewer's policy.
                main { viewer.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                eventually("system layer mirror attached") { main { findView(viewer.window.decorView)?.isLive == true } }
                verify("opening viewer leaves task running", !VirtualScreenSession.state.value.manualControl &&
                    VirtualScreenSession.state.value.isAgentControlling)
                val view = checkNotNull(main { findView(viewer.window.decorView) })
                val info = checkNotNull(VirtualScreenSession.state.value.display)
                val viewport = main { checkNotNull(VirtualScreenViewport.fit(view.width, view.height, info.width, info.height)) }
                val location = IntArray(2).also { main { view.getLocationOnScreen(it) } }
                val decor = main { viewer.window.decorView }
                val safeTop = main {
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                    insets?.getInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout())?.top ?: 0
                }
                verify("content fills viewer below status bar and cutout", view.width == decor.width && view.height == decor.height - safeTop && location[1] >= safeTop)
                captureFixture(viewer, view, "realtime-viewer")
                if (!inputOnly) validateControls(viewer)
                val start = SystemClock.uptimeMillis()
                send(viewport, location, MotionEvent.ACTION_DOWN, start, info.width / 3, info.height / 3)
                eventually("DOWN reaches virtual app before finger release") { probe().optInt("downs") == 1 }
                send(viewport, location, MotionEvent.ACTION_MOVE, start, info.width * 2 / 3, info.height / 2)
                eventually("MOVE updates virtual app while finger remains down") { probe().optInt("moves") >= 1 }
                verify("manual input pauses automated UI", VirtualScreenSession.state.value.manualControl)
                send(viewport, location, MotionEvent.ACTION_UP, start, info.width * 2 / 3, info.height / 2)
                eventually("UP terminates virtual gesture") { probe().optInt("ups") == 1 }
                verify("gesture remains on independent display", probe().optInt("display") == info.displayId)
                verify("raw event time is preserved", probe().optLong("eventTime") >= start && probe().optLong("eventTime") - start < 5_000)
                if (!inputOnly) {
                    verify("resume before animated glow performance measurement", VirtualScreenSession.resumeFromViewer(info.sessionId, resumeRuntime = false))
                    validateFrameRate(viewer, view, info)
                }
                val capture = VirtualScreenSession.execute(context, info.owner, JSONObject().put("action", "probe"), manual = true)
                record("METRIC display_probe=${capture.content}")
                val screenshot = VirtualScreenSession.execute(context, info.owner, JSONObject().put("action", "observe"), manual = true)
                verify("AI screenshot coexists with compositor preview", screenshot.images.size == 1 && JSONObject(screenshot.content).optBoolean("ok"))
                if (!inputOnly) validateMultitouch(viewport, location, info)
                validateHandoff(viewport, info, view)
                val downsBeforeCancel = probe().optInt("downs")
                val cancelsBeforeExit = probe().optInt("cancels")
                val cancelStart = SystemClock.uptimeMillis()
                routedTouch(view, viewport, MotionEvent.ACTION_DOWN, cancelStart, info.width / 3, info.height / 3)
                eventually("second gesture begins") { probe().optInt("downs") == downsBeforeCancel + 1 }
                instrumentation.runOnMainSync { viewer.finish() }
                eventually("leaving viewer cancels held pointer") { probe().optInt("cancels") == cancelsBeforeExit + 1 }
            } finally { instrumentation.runOnMainSync { if (!viewer.isFinishing) viewer.finish() } }
            call(tools, "close realtime display", JSONObject().put("action", "close"))
        }
    }

    private fun validateFrameRate(viewer: VirtualScreenViewerActivity, view: VirtualScreenDisplayView,
        info: io.github.mangi.eta.agent.display.VirtualDisplayInfo) {
        eventually("animation frame counters available") { probe().optLong("frames") >= 120 }
        val drawBefore = probe().optLong("frames")
        eventually("high refresh animation remains active") { probe().optLong("frames") >= drawBefore + 240 }
        record("METRIC application_draw_fps=${probe().optDouble("drawFps")},p95_ms=${probe().optDouble("drawIntervalP95Ms")}")
        verify("test page draws near 120fps", probe().optDouble("drawFps") >= 115)
        record("READY_PRESENT_TRACE")
        var previousFrames = probe().optLong("frames")
        repeat(steadySeconds) { second ->
            SystemClock.sleep(1000)
            check(VirtualScreenSession.state.value.display?.let { it.sessionId == info.sessionId && it.owner == info.owner } == true) {
                "Performance fixture replaced; stopping measurement"
            }
            check(VirtualScreenSession.state.value.fullViewerVisible && main { view.isLive &&
                viewer.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }) {
                "Viewer no longer presents the fixture; stopping measurement"
            }
            val frames = probe().optLong("frames")
            check(frames > previousFrames) { "Animation stopped; cached FPS cannot validate presentation" }
            previousFrames = frames
            if ((second + 1) % 60 == 0) {
                record("METRIC steady_seconds=${second + 1},application_draw_fps=${probe().optDouble("drawFps")},p95_ms=${probe().optDouble("drawIntervalP95Ms")}")
            }
        }
        verify("animation sustains near 120fps after steady run", probe().optDouble("drawFps") >= 115)
    }

    private fun captureFixture(viewer: VirtualScreenViewerActivity, view: VirtualScreenDisplayView, name: String) {
        verify("viewer permits screenshots by default", main { viewer.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 })
        SystemClock.sleep(200)
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            val matching = (1..9).sumOf { row -> (1..9).count { col ->
                val color = bitmap.getPixel(bitmap.width * col / 10, bitmap.height * row / 10)
                Color.red(color) > 230 && Color.green(color) > 230 && Color.blue(color) > 230
            } }
            verify("mirrored pixels appear on the physical viewer", matching >= 20)
            FileOutputStream(File(context.cacheDir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun validateControls(viewer: VirtualScreenViewerActivity) {
        val description = context.getString(io.github.mangi.eta.R.string.virtual_screen_controls)
        eventually("edge handle is accessible") { node(description) != null }
        val downsBefore = probe().optInt("downs")
        val handle = checkNotNull(node(description))
        var clickable: AccessibilityNodeInfo? = handle
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        val clicked = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!clicked) {
            val bounds = Rect().also(handle::getBoundsInScreen)
            hostTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        SystemClock.sleep(500)
        captureFixture(viewer, checkNotNull(main { findView(viewer.window.decorView) }), "realtime-controls")
        eventually("capsule expands with history and exit") { node(context.getString(io.github.mangi.eta.R.string.virtual_screen_exit_viewer)) != null }
        verify("capsule omits navigation and touch-mode buttons", listOf(
            io.github.mangi.eta.R.string.virtual_screen_app_back, io.github.mangi.eta.R.string.virtual_screen_home,
            io.github.mangi.eta.R.string.virtual_screen_menu, io.github.mangi.eta.R.string.virtual_screen_touch_on,
            io.github.mangi.eta.R.string.virtual_screen_touch_off,
            io.github.mangi.eta.R.string.virtual_screen_close,
        ).none { node(context.getString(it)) != null })
        verify("opening controls does not touch the virtual app", probe().optInt("downs") == downsBefore)
        val view = checkNotNull(main { findView(viewer.window.decorView) })
        captureFixture(viewer, view, "realtime-controls")
        main { viewer.onBackPressedDispatcher.onBackPressed() }
        eventually("compact capsule hides back to edge handle") { node(description) != null }
        // Allow the collapsed box to settle its smaller height before taking screen coords.
        SystemClock.sleep(350)
        val handleBounds = Rect().also(checkNotNull(node(description))::getBoundsInScreen)
        val hostWidth = main { viewer.window.decorView.width }
        val beforeDrag = probe().optInt("downs")
        val preferences = context.getSharedPreferences("virtual_screen_controls", android.content.Context.MODE_PRIVATE)
        val originalRight = preferences.getBoolean("right", true)
        val originalFraction = preferences.getFloat("fraction", 0.3f)
        try {
            val down = SystemClock.uptimeMillis()
            val startX = handleBounds.centerX().toFloat()
            val endX = if (startX > hostWidth / 2) 20f else hostWidth - 20f
            for (index in 0..12) {
                val action = if (index == 0) MotionEvent.ACTION_DOWN else if (index == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    startX + (endX - startX) * index / 12, handleBounds.centerY().toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try {
                    HiddenApiBypass.invoke(android.view.InputEvent::class.java, event, "setDisplayId", Display.DEFAULT_DISPLAY)
                    verify("capsule drag sample delivered", automation.injectInputEvent(event, true))
                } finally { event.recycle() }
                SystemClock.sleep(30)
            }
            record("METRIC drag_dock_before=$originalRight,after=${preferences.getBoolean("right", true)},remote_downs=${probe().optInt("downs") - beforeDrag},bounds=${Rect().also { node(description)?.getBoundsInScreen(it) }}")
            eventually("drag docks controls to the opposite edge") {
                val bounds = Rect().also { node(description)?.getBoundsInScreen(it) }
                bounds.width() > 0 && (bounds.centerX() > hostWidth / 2) != (startX > hostWidth / 2)
            }
            verify("dragging controls does not start remote touch", probe().optInt("downs") == beforeDrag)
        } finally {
            preferences.edit().putBoolean("right", originalRight).putFloat("fraction", originalFraction).commit()
        }
    }

    private fun hostTap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { HiddenApiBypass.invoke(android.view.InputEvent::class.java, event, "setDisplayId", Display.DEFAULT_DISPLAY)
                verify("control tap delivered", automation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
    }

    private fun node(description: String): AccessibilityNodeInfo? {
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.contentDescription?.toString()?.contains(description) == true || current.text?.toString() == description) return current
            for (index in 0 until current.childCount) current.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        // UiAutomation can retain pre-drag bounds when only a Compose offset changes.
        return automation.rootInActiveWindow?.let(::find)?.takeIf { it.refresh() }
    }

    private fun validateMultitouch(viewport: VirtualScreenViewport, location: IntArray,
        info: io.github.mangi.eta.agent.display.VirtualDisplayInfo) {
        val downTime = SystemClock.uptimeMillis()
        val initial = listOf(4 to (info.width / 3 to info.height / 3))
        val both = initial + (9 to (info.width * 2 / 3 to info.height * 2 / 3))
        multi(viewport, location, MotionEvent.ACTION_DOWN, downTime, initial)
        multi(viewport, location, MotionEvent.ACTION_POINTER_DOWN or 256, downTime, both)
        eventually("both fingers reach virtual display") { probe().optInt("pointerCount") == 2 }
        SystemClock.sleep(2200)
        val moved = listOf(4 to (info.width / 4 to info.height / 4), 9 to (info.width * 3 / 4 to info.height * 3 / 4))
        multi(viewport, location, MotionEvent.ACTION_MOVE, downTime, moved)
        multi(viewport, location, MotionEvent.ACTION_POINTER_UP, downTime, moved)
        multi(viewport, location, MotionEvent.ACTION_MOVE, downTime, moved.drop(1))
        multi(viewport, location, MotionEvent.ACTION_UP, downTime, moved.drop(1))
        eventually("multi-finger gesture ends normally") { probe().optInt("ups") == 2 }
        val events = JSONArray(probe().getString("events"))
        val final = events.getJSONObject(events.length() - 1)
        verify("remaining pointer keeps its ID after first finger lifts", final.getJSONArray("pointers").getJSONObject(0).getInt("id") == 9)
        verify("held gesture preserves duration beyond old two-second cap", final.getLong("time") - final.getLong("downTime") >= 2200)
        verify("raw multi-finger order is retained", (0 until events.length()).map { events.getJSONObject(it).getInt("action") }
            .containsAll(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP)))
        record("METRIC input_delivery_ms=" + (probe().getLong("receivedTime") - probe().getLong("eventTime")))
    }

    private fun validateHandoff(viewport: VirtualScreenViewport,
        info: io.github.mangi.eta.agent.display.VirtualDisplayInfo, view: VirtualScreenDisplayView) {
        val args = JSONObject().put("action", "tap").put("x", info.width / 2).put("y", info.height / 2)
        verify("automatic input is refused while manually controlled", JSONObject(checkNotNull(
            VirtualScreenSession.checkUiAction(context, info.owner, args)).content).optString("code") == "UI_MANUAL_CONTROL")
        verify("explicit continue returns control", VirtualScreenSession.resumeFromViewer(info.sessionId))
        verify("continue requires a new model observation", JSONObject(checkNotNull(
            VirtualScreenSession.checkUiAction(context, info.owner, args)).content).optString("code") == "STALE_OBSERVATION")
        val observed = VirtualScreenSession.execute(context, info.owner, JSONObject().put("action", "observe"))
        verify("model can observe after continue", JSONObject(observed.content).optBoolean("ok") && observed.images.size == 1)
        val result = AtomicReference<AgentModelClient.ToolResult>()
        val complete = CountDownLatch(1)
        val before = probe().optInt("downs")
        Thread {
            try { result.set(VirtualScreenSession.execute(context, info.owner, JSONObject().put("action", "swipe")
                .put("x1", info.width / 4).put("y1", info.height / 3)
                .put("x2", info.width * 3 / 4).put("y2", info.height * 2 / 3).put("durationMs", 2000))) }
            finally { complete.countDown() }
        }.start()
        eventually("automatic long gesture starts") { probe().optInt("downs") == before + 1 }
        val downTime = SystemClock.uptimeMillis()
        // Concurrent framework injections share Android's virtual pointer. Exercise a
        // primary touch already routed to the viewer, then the real Root/OS display path.
        routedTouch(view, viewport, MotionEvent.ACTION_DOWN, downTime, info.width / 2, info.height / 2)
        eventually("manual DOWN preempts gesture while JSON command is in flight") { probe().optInt("downs") == before + 2 }
        verify("old command terminates after takeover", complete.await(2, TimeUnit.SECONDS))
        verify("old command reports manual handoff without replay", JSONObject(checkNotNull(result.get()).content).optString("code") == "UI_MANUAL_CONTROL")
        routedTouch(view, viewport, MotionEvent.ACTION_UP, downTime, info.width / 2, info.height / 2)
        eventually("new manual pointer is released normally") { probe().optInt("action") == MotionEvent.ACTION_UP }
    }

    private fun routedTouch(view: VirtualScreenDisplayView, viewport: VirtualScreenViewport,
        action: Int, downTime: Long, x: Int, y: Int) {
        val point = viewport.toView(x, y)
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { verify("routed viewer touch accepted $action", main { view.dispatchTouchEvent(event) }) }
        finally { event.recycle() }
    }

    private fun multi(viewport: VirtualScreenViewport, location: IntArray, action: Int, downTime: Long,
        pointers: List<Pair<Int, Pair<Int, Int>>>) {
        val props = pointers.map { MotionEvent.PointerProperties().apply { id = it.first; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = pointers.map { point -> viewport.toView(point.second.first, point.second.second).let { mapped ->
            MotionEvent.PointerCoords().apply { x = mapped.x + location[0]; y = mapped.y + location[1]; pressure = 1f; size = 1f }
        } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, pointers.size,
            props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            HiddenApiBypass.invoke(android.view.InputEvent::class.java, event, "setDisplayId", Display.DEFAULT_DISPLAY)
            verify("host multi-pointer event $action delivered", automation.injectInputEvent(event, true))
        } finally { event.recycle() }
    }

    private fun send(viewport: VirtualScreenViewport, location: IntArray, action: Int, downTime: Long, x: Int, y: Int) {
        val mapped = viewport.toView(x, y)
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = mapped.x + location[0]; this.y = mapped.y + location[1]; pressure = 1f; size = 1f }),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            HiddenApiBypass.invoke(android.view.InputEvent::class.java, event, "setDisplayId", Display.DEFAULT_DISPLAY)
            verify("host event $action delivered", automation.injectInputEvent(event, true))
        } finally { event.recycle() }
    }

    private fun findView(view: View): VirtualScreenDisplayView? {
        if (view is VirtualScreenDisplayView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun probe(): JSONObject = context.contentResolver.query(Uri.parse("content://io.github.mangi.eta.test.display_probe/realtime"),
        null, null, null, null)?.use { if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject() } ?: JSONObject()

    private fun call(tools: AgentLocalTools, label: String, args: JSONObject) {
        val result = tools.execute(AgentModelClient.ToolCall("realtime-${System.nanoTime()}", "virtual_screen", args.toString()))
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
            SystemClock.sleep(50)
        }
        verify(label, condition())
    }

    private fun verify(label: String, value: Boolean) { check(value) { label }; record("PASS $label") }
}
