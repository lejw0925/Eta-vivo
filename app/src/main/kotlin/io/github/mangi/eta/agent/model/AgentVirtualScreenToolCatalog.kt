package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentVirtualScreenToolCatalog {
    fun appendTo(tools: JSONArray) {
        fun number(min: Int, max: Int) =
            JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
        tools.put(
            AgentToolSchema.function(
                "virtual_screen",
                "Experimental Root display for isolated Android app operations. When Virtual screen is enabled, prefer normal launch_app, observe_screen, gesture and text tools; they automatically use this display. Use this tool for dimensions and lifecycle control. launch requires an exact package/activity component. Apps running elsewhere require the user's automatic-restart setting or a three-way choice: stop and restart virtually, continue on the phone, or cancel. Never infer permission to stop an app. observe returns this display's image; tap/swipe use its pixel coordinates. Users can touch the viewer and expand text input using their phone keyboard. Pass Unicode text directly to type_text; its internal Root paste needs no preparatory clipboard call. Normal GUI actions return an after observation: fresh nodes, or an image when the tree is unavailable. Verify with after before requesting another observation; null screen_changed means unknown, not unchanged; false only means sampled UI evidence is unchanged, not that the action failed. Repeated empty trees temporarily hide node tools for the current app window. observe_screen keeps probing and restores them when nodes return; app/window changes reset the restriction. With include_screenshot omitted, an empty tree automatically includes an image; explicit false suppresses it. Use an existing fresh image before requesting another. Always use a fresh observation for node actions. While trees are absent, use screenshot coordinates, never main-screen nodes. back and close apply only to this display. Other failures require the separate fallback setting and explicit notification approval to use the main screen. UI_DISPLAY_SWITCHED never replays the failed operation; obtain a fresh primary observation before interacting. allowScreenOff=true needs separate permission and does not unlock or power off the phone. Default resolution and DPI match the phone. Successful, stopped and failed turns retain this conversation's display and state; every new run must observe before acting; other conversations replace an idle display. Idle cleanup follows the user's 10/20/60 minute or Never setting; active runs do not expire. Explicit close, idle timeout and permission revocation release the display. Secure app content may stay black.",
                JSONObject().put("type", "object").put("additionalProperties", false).put(
                    "properties", JSONObject()
                        .put(
                            "action",
                            JSONObject().put("type", "string").put(
                                "enum",
                                JSONArray(
                                    listOf(
                                        "create",
                                        "launch",
                                        "observe",
                                        "tap",
                                        "swipe",
                                        "back",
                                        "close"
                                    )
                                )
                            )
                        )
                        .put("width", number(320, 1440)).put("height", number(480, 3200))
                        .put("density", number(120, 640))
                        .put("allowScreenOff", JSONObject().put("type", "boolean"))
                        .put("component", JSONObject().put("type", "string").put("maxLength", 300))
                        .put("x", number(0, 1439)).put("y", number(0, 3199))
                        .put("x1", number(0, 1439)).put("y1", number(0, 3199))
                        .put("x2", number(0, 1439)).put("y2", number(0, 3199))
                        .put("path", AgentToolSchema.swipePath().put("description", "swipe 的单指连续路径，2–64 个 {x,y} 点，使用此虚拟屏的像素坐标，中途不抬手。与 x1/y1/x2/y2 二选一；durationMs 为总时长。"))
                        .put("durationMs", number(100, 2000))
                )
                    .put("required", JSONArray().put("action"))
            )
        )
    }
}
