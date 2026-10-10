package io.github.mangi.eta.agent.device

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Looper
import android.os.Process
import android.view.Display
import kotlin.system.exitProcess
import org.json.JSONObject

/** Fixed Root entry point for one primary-display path; all model data arrives through stdin. */
internal object RootSwipeCommandMain {
    const val MARKER = "ETA_SWIPE_RESULT:"

    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(arguments: Array<String>) {
        var attempted = false
        val result = try {
            require(Process.myUid() == 0) { "ROOT_REQUIRED" }
            val input = System.`in`.readNBytes(16 * 1024 + 1)
            require(input.size <= 16 * 1024) { "INVALID_ARGUMENT" }
            val request = JSONObject(input.toString(Charsets.UTF_8))
            require(SwipePath.integer(request, "version") == 1) { "INVALID_ARGUMENT" }
            val points = SwipePath.parsePath(request.opt("path"))
            val duration = SwipePath.integer(request, "durationMs")
            require(duration in 100..2_000) { "INVALID_ARGUMENT" }
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val dimensions = Point().also(display::getRealSize)
            require(SwipePath.integer(request, "width") == dimensions.x && SwipePath.integer(request, "height") == dimensions.y) {
                "SCREEN_GEOMETRY_CHANGED"
            }
            SwipePath.validate(points, dimensions.x, dimensions.y)
            RootInputInjector(Display.DEFAULT_DISPLAY).use {
                attempted = true
                it.executePath(points, duration)
            }
        } catch (failure: Exception) {
            JSONObject().put("ok", false)
                .put("code", if (attempted) "ACTION_OUTCOME_UNKNOWN" else when (failure.message) {
                    "ROOT_REQUIRED", "SCREEN_GEOMETRY_CHANGED" -> failure.message
                    else -> if (failure is IllegalArgumentException || failure is org.json.JSONException)
                        "INVALID_ARGUMENT" else "ROOT_INPUT_UNAVAILABLE"
                })
                .put("effect", if (attempted) "unconfirmed" else "not_started")
                .put("message", if (attempted) "路径输入结果未确认，请重新观察后再决定下一步。" else "路径参数或屏幕状态无效，未发送触摸。")
        }
        println(MARKER + result.toString())
        exitProcess(0)
    }
}
