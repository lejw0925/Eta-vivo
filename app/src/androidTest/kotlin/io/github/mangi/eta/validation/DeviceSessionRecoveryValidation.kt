package io.github.mangi.eta.validation

import android.app.Instrumentation
import android.net.Uri
import android.os.SystemClock
import io.github.mangi.eta.agent.display.VirtualScreenResumeStore
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import org.json.JSONObject
import java.io.File

/** Two phases with an external force-stop between them verify private, durable recovery. */
internal class DeviceSessionRecoveryValidation(
    private val instrumentation: Instrumentation,
    private val createTools: (String) -> AgentLocalTools,
    private val record: (String) -> Unit,
) {
    private val context = instrumentation.targetContext
    private val component = "io.github.mangi.eta.test/${DisplayProbeActivity::class.java.name}"
    private val seedFile get() = File(context.cacheDir, "virtual-recovery-seed.json")

    fun seed(holdForDeath: Boolean = false) {
        createTools("process-recovery-seed").use { tools ->
            ok("seed display", call(tools, "virtual_screen", JSONObject().put("action", "create")))
            ok("seed app with a deep link", call(tools, "virtual_screen", JSONObject().put("action", "launch")
                .put("component", component).put("uri", "eta-validation://audit")))
            val info = checkNotNull(VirtualScreenSession.state.value.display)
            eventually("seed fixture focused") { probe().optInt("display") == info.displayId && probe().optInt("buttonY") > 0 }
            val observation = call(tools, "observe_screen", JSONObject().put("include_screenshot", true))
            ok("seed observation", observation)
            val position = probe()
            ok("seed one confirmed tap", call(tools, "tap", JSONObject().put("coordinate_space", "screen")
                .put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY"))))
            eventually("seed tap took effect") { probe().optInt("taps") == 1 }
            seedFile.writeText(JSONObject().put("session", info.sessionId)
                .put("x", position.getInt("buttonX")).put("y", position.getInt("buttonY")).toString())
            verify("target is durable for this owner", VirtualScreenResumeStore(context).component("device-validation") == component)
            verify("other conversation has no target", VirtualScreenResumeStore(context).component("other-recovery-owner") == null)
            record("READY_FORCE_STOP_ETA")
            if (holdForDeath) {
                verify("active run is alive before abrupt process death", VirtualScreenSession.isActive() &&
                    VirtualScreenSession.state.value.isAgentControlling)
                SystemClock.sleep(30_000)
                error("Expected the harness to force-stop Eta while this fixture was running")
            }
        }
    }

    fun resume() {
        val seed = JSONObject(seedFile.readText())
        verify("new process has no live display", !VirtualScreenSession.isActive())
        createTools("process-recovery-resume").use { tools ->
            val rejected = JSONObject(call(tools, "tap", JSONObject().put("coordinate_space", "screen")
                .put("x", seed.getInt("x")).put("y", seed.getInt("y"))).content)
            verify("old tap is rejected with a recovery instruction", !rejected.optBoolean("ok") &&
                rejected.optString("code") == "VIRTUAL_OBSERVATION_REQUIRED" && rejected.optBoolean("requires_observation") &&
                rejected.optString("next_tool") == "observe_screen" && !rejected.optBoolean("action_executed", true))
            val info = checkNotNull(VirtualScreenSession.state.value.display)
            verify("display was automatically recreated", info.sessionId != seed.getString("session"))
            eventually("last app returns to independent display") { probe().optInt("display") == info.displayId && probe().optInt("buttonY") > 0 }
            verify("recovery does not replay the old tap", probe().optInt("taps") == 0)
            verify("recovery does not replay the deep link", probe().optString("intentUri").isEmpty())
            val observed = call(tools, "observe_screen", JSONObject().put("include_screenshot", true))
            val json = ok("fresh recovered observation", observed)
            verify("new observation contains current source image", observed.images.size == 1 &&
                json.getJSONObject("focus").optString("package") == "io.github.mangi.eta.test")
            val recovery = json.getJSONObject("recovery")
            verify("model receives recreated/restored state", recovery.optBoolean("session_recreated") &&
                recovery.optBoolean("app_restored") && recovery.optString("app_component") == component &&
                !recovery.optBoolean("previous_observation_valid", true))
            val current = probe()
            ok("continue once using fresh observation", call(tools, "tap", JSONObject().put("coordinate_space", "screen")
                .put("x", current.getInt("buttonX")).put("y", current.getInt("buttonY"))))
            eventually("continued action happens exactly once") { probe().optInt("taps") == 1 }
            ok("explicit close", call(tools, "virtual_screen", JSONObject().put("action", "close")))
            verify("explicit close forgets the app", VirtualScreenResumeStore(context).component("device-validation") == null)
        }
        // The low-level observation API has the same automatic recovery behavior.
        VirtualScreenResumeStore(context).remember("device-validation", component)
        createTools("process-recovery-raw").use { tools ->
            val raw = call(tools, "virtual_screen", JSONObject().put("action", "observe"))
            ok("raw display observation automatically recovers", raw)
            verify("raw recovery returns a fresh image", raw.images.size == 1)
            ok("close raw recovery", call(tools, "virtual_screen", JSONObject().put("action", "close")))
        }
        seedFile.delete()
    }

    private fun call(tools: AgentLocalTools, name: String, args: JSONObject) = tools.execute(
        AgentModelClient.ToolCall("recovery-${System.nanoTime()}", name, args.toString()))

    private fun ok(label: String, result: AgentModelClient.ToolResult): JSONObject = JSONObject(result.content).also {
        verify("$label (${it.optString("code")})", it.optBoolean("ok"))
    }

    private fun probe(): JSONObject = context.contentResolver.query(Uri.parse("content://io.github.mangi.eta.test.display_probe/state"),
        null, null, null, null)?.use { if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject() } ?: JSONObject()

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
