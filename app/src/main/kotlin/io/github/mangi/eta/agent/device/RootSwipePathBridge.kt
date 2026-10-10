package io.github.mangi.eta.agent.device

import android.content.Context
import org.json.JSONObject

internal object RootSwipePathBridge {
    fun execute(context: Context, root: BoundedRootCommandExecutor, points: List<SwipePath.Point>,
        durationMs: Int, width: Int, height: Int): JSONObject {
        val request = JSONObject().put("version", 1).put("path", SwipePath.toJson(points))
            .put("durationMs", durationMs).put("width", width).put("height", height)
        val command = "CLASSPATH=${DeviceToolContract.quote(context.applicationInfo.sourceDir)} " +
            "app_process /system/bin io.github.mangi.eta.agent.device.RootSwipeCommandMain"
        val result = root.execute(command, timeoutMillis = 12_000, maxOutputBytes = 16 * 1024,
            input = request.toString().toByteArray(Charsets.UTF_8))
        if (!result.ok || result.truncated) return unknown(result.errorCode.ifBlank { "ACTION_OUTCOME_UNKNOWN" })
        val payload = result.stdout.lineSequence().singleOrNull { it.startsWith(RootSwipeCommandMain.MARKER) }
            ?.removePrefix(RootSwipeCommandMain.MARKER) ?: return unknown("ACTION_OUTCOME_UNKNOWN")
        return try {
            JSONObject(payload)
        } catch (_: org.json.JSONException) {
            unknown("ACTION_OUTCOME_UNKNOWN")
        }
    }

    private fun unknown(code: String): JSONObject = JSONObject().put("ok", false).put("code", code)
        .put("effect", "unconfirmed").put("message", "未收到完整的路径输入结果，请先重新观察，避免重复操作。")
}
