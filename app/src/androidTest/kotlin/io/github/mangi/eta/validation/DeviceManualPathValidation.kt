package io.github.mangi.eta.validation

import android.app.ActivityOptions
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.mangi.eta.agent.device.SwipePath
import io.github.mangi.eta.agent.display.VirtualDisplayInfo
import io.github.mangi.eta.agent.display.VirtualScreenPipPreviewController
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenDisplayView
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.agent.display.VirtualScreenViewport
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Injects host touches into the real viewer and checks the separate app's received path. */
internal class DeviceManualPathValidation(
    private val instrumentation: Instrumentation,
    private val automation: UiAutomation,
    private val toolsFactory: (String) -> AgentLocalTools,
    private val record: (String) -> Unit,
) {
    private val context = instrumentation.targetContext

    fun run() {
        verify("phone unlocked for manual paths", VirtualScreenPipPreviewController.screenAvailable(context))
        runBlocking { SettingsDataStore.updateSettings { it.copy(virtualScreenPreviewEnabled = false) } }
        toolsFactory("manual-viewer-path").use { tools ->
            call(tools, "create manual path display", JSONObject().put("action", "create"))
            for ((name, activity) in listOf("portrait" to DisplayProbeActivity::class.java,
                "landscape" to LandscapeProbeActivity::class.java)) {
                if (VirtualScreenSession.state.value.manualControl) {
                    val current = checkNotNull(VirtualScreenSession.state.value.display)
                    verify("continue before next fixture", VirtualScreenSession.resumeFromViewer(current.sessionId))
                    verify("fresh observation before next fixture", JSONObject(VirtualScreenSession.execute(context, current.owner,
                        JSONObject().put("action", "observe")).content).optBoolean("ok"))
                }
                call(tools, "launch $name path fixture", JSONObject().put("action", "launch")
                    .put("component", "io.github.mangi.eta.test/${activity.name}").put("uri", "eta-validation://swipe-path"))
                eventually("$name fixture geometry ready") {
                    // Rotation can finish after launch returns. Refresh the
                    // session geometry before the viewer starts its frame loop.
                    VirtualScreenSession.observeForViewer(context)
                    val display = VirtualScreenSession.state.value.display
                    val state = probe()
                    display != null && state.optInt("display") == display.displayId &&
                        state.optInt("displayWidth") == display.width && state.optInt("displayHeight") == display.height &&
                        (name != "landscape" || display.width > display.height)
                }
                val viewer = instrumentation.startActivitySync(VirtualScreenViewerActivity.newIntent(context),
                    ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
                    as VirtualScreenViewerActivity
                try {
                    eventually("$name viewer receives a current frame") {
                        main { findSurface(viewer.window.decorView)?.let { view ->
                            val frame = view.javaClass.getDeclaredField("info").apply { isAccessible = true }.get(view) as? VirtualDisplayInfo
                            view.isLive && frame != null && frame.hasSameGeometry(checkNotNull(VirtualScreenSession.state.value.display)) &&
                                view.width > 0 && view.height > 0
                        } == true }
                    }
                    SystemClock.sleep(500)
                    val display = checkNotNull(VirtualScreenSession.state.value.display)
                    val view = checkNotNull(main { findSurface(viewer.window.decorView) })
                    val viewport = main { checkNotNull(VirtualScreenViewport.fit(view.width, view.height, display.width, display.height)) }
                    verify("$name viewer rotation matches display", viewport.rotatesClockwise == (name == "landscape" && view.height > view.width))
                    val rectangle = listOf(
                        SwipePath.Point(display.width / 4, display.height / 3),
                        SwipePath.Point(display.width * 3 / 4, display.height / 3),
                        SwipePath.Point(display.width * 3 / 4, display.height * 2 / 3),
                        SwipePath.Point(display.width / 4, display.height * 2 / 3),
                        SwipePath.Point(display.width / 4, display.height / 3),
                    )
                    val samples = buildList {
                        add(rectangle.first())
                        rectangle.zipWithNext().forEach { (start, end) ->
                            for (step in 1..24) add(SwipePath.Point(start.x + (end.x - start.x) * step / 24,
                                start.y + (end.y - start.y) * step / 24))
                        }
                    }
                    verify("$name closed rectangle host input delivered", stroke(view, viewport, samples))
                    verifyStroke("$name rectangle", rectangle, 1)

                    // Use a sparse polygon for device-level geometry checks.
                    // Dense paths and historical samples are covered by the
                    // deterministic surface-view tests.
                    val circle = (0..16).map { index ->
                        val angle = index * 2 * Math.PI / 16
                        SwipePath.Point((display.width * (0.5 + 0.2 * cos(angle))).toInt(),
                            (display.height * (0.5 + 0.2 * sin(angle))).toInt())
                    }
                    verify("$name curved host input delivered", stroke(view, viewport, circle, stepMs = 70))
                    verifyStroke("$name circle", circle, 2)

                    val reversal = listOf(rectangle[0], rectangle[1], rectangle[0])
                    verify("$name reversal host input delivered", stroke(view, viewport, reversal, stepMs = 180))
                    verifyStroke("$name reversal", reversal, 3)
                    verify("$name cancelled host input delivered", stroke(view, viewport, rectangle.take(3), cancel = true))
                    SystemClock.sleep(500)
                    verify("$name cancelled path reaches app as cancellation", probe().optInt("gestures") == 4 &&
                        JSONArray(probe().getString("touchEvents")).let { events ->
                            events.getJSONObject(events.length() - 1).getInt("action") == MotionEvent.ACTION_CANCEL
                        })
                } finally { instrumentation.runOnMainSync { viewer.finish() } }
            }
            call(tools, "close manual path display", JSONObject().put("action", "close"))
        }
    }

    private fun findSurface(view: View): VirtualScreenDisplayView? {
        if (view is VirtualScreenDisplayView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findSurface(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun stroke(view: VirtualScreenDisplayView, viewport: VirtualScreenViewport, points: List<SwipePath.Point>,
        stepMs: Long = 12, cancel: Boolean = false): Boolean {
        val location = IntArray(2).also { main { view.getLocationOnScreen(it) } }
        val start = SystemClock.uptimeMillis()
        var accepted = true
        for ((index, point) in points.withIndex()) {
            val mapped = viewport.toView(point.x, point.y)
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                mapped.x + location[0], mapped.y + location[1], 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                HiddenApiBypass.invoke(android.view.InputEvent::class.java, event, "setDisplayId", Display.DEFAULT_DISPLAY)
                val delivered = automation.injectInputEvent(event, true)
                if (!delivered) record("METRIC host_input_failed index=$index action=$action view=${main { view.width }}x${main { view.height }} viewport=$viewport")
                accepted = delivered && accepted
            } finally { event.recycle() }
            if (index != points.lastIndex) SystemClock.sleep(stepMs)
        }
        return accepted
    }

    private fun verifyStroke(name: String, expected: List<SwipePath.Point>, count: Int) {
        eventually("$name reaches virtual app") { probe().optInt("gestures") == count }
        val raw = JSONArray(probe().getString("touchEvents"))
        val events = (0 until raw.length()).map(raw::getJSONObject)
        verify("$name uses one continuous pointer", events.count { it.getInt("action") == MotionEvent.ACTION_DOWN } == 1 &&
            events.count { it.getInt("action") == MotionEvent.ACTION_UP } == 1 &&
            events.none { it.getInt("action") == MotionEvent.ACTION_CANCEL } &&
            events.all { it.getLong("downTime") == events.first().getLong("downTime") })
        var from = 0
        for ((index, vertex) in expected.withIndex()) {
            val found = (from until events.size).firstOrNull { at ->
                abs(events[at].getDouble("x") - vertex.x) < 40 && abs(events[at].getDouble("y") - vertex.y) < 40
            }
            verify("$name visits vertex $index in order", found != null)
            from = checkNotNull(found) + 1
        }
        verify("$name does not fall back to primary display", probe().getInt("display") == VirtualScreenSession.state.value.display?.displayId)
        eventually("$name manual operation succeeds") {
            VirtualScreenSession.state.value.operations.lastOrNull { it.manual }?.let {
                it.name == "swipe" && it.success
            } == true
        }
    }

    private fun probe(): JSONObject = context.contentResolver.query(Uri.parse("content://io.github.mangi.eta.test.display_probe/state"),
        null, null, null, null)?.use { cursor -> if (cursor.moveToFirst()) JSONObject(cursor.getString(0)) else JSONObject() } ?: JSONObject()

    private fun call(tools: AgentLocalTools, label: String, args: JSONObject) {
        val result = tools.execute(AgentModelClient.ToolCall("manual-path-${System.nanoTime()}", "virtual_screen", args.toString()))
        val response = JSONObject(result.content)
        verify(label + response.optString("code").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(), response.optBoolean("ok"))
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

    private fun verify(label: String, value: Boolean) {
        check(value) { label }
        record("PASS $label")
    }
}
