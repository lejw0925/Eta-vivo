package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** 屏幕观察工具在模型 schema、执行器与运行轨迹之间共享的默认合同。 */
internal object AgentScreenObservationContract {
    const val DEFAULT_INCLUDE_SCREENSHOT = false
    const val DEFAULT_INCLUDE_UI_TREE = true
    const val DEFAULT_MAX_NODES = 60
    const val MIN_MAX_NODES = 1
    const val MAX_MAX_NODES = 120

    fun guidance(virtualScreen: Boolean): String =
        (if (virtualScreen) {
            "需要重新看虚拟屏时先按默认参数调用 observe_screen，默认请求 UI 树；省略 include_screenshot 时，空树会自动附截图，显式 false 才禁止附图。"
        } else {
            "需要重新看主屏时先按默认参数调用 observe_screen，默认只返回前台应用、屏幕尺寸、observation_id 与 UI 节点，不附截图。"
        }) +
            "节点为空、目标无法唯一识别、界面以 Canvas、地图、图片或二维码等视觉内容为主，或任务依赖颜色、图像、空间布局时，" +
            "先使用已有的新截图；未附截图或已有观察不足时，再显式设置 include_screenshot=true。" +
            "补截图时保持 include_ui_tree=true，让截图、节点与新的 observation_id 来自同一次观察，禁止把新截图与旧节点混用；" +
            "节点动作必须携带同一次观察的 observation_id。树被截断但节点语义仍有效时，优先提高 max_nodes 到 120，不要仅因截断请求截图。"

    fun screenshotDescription(virtualScreen: Boolean): String =
        if (virtualScreen) {
            "true 显式附图，false 禁止附图；省略时默认请求 UI 树，空树自动附截图。有新截图且足够时直接使用。"
        } else {
            "是否附加当前主屏原图给模型，默认 false；仅在 UI 节点不足以完成任务时显式开启。"
        }

    data class Options(
        val includeScreenshot: Boolean,
        val includeUiTree: Boolean,
        val maxNodes: Int,
    )

    fun resolve(arguments: JSONObject): Options = Options(
        includeScreenshot = arguments.optBoolean(
            "include_screenshot",
            DEFAULT_INCLUDE_SCREENSHOT,
        ),
        includeUiTree = arguments.optBoolean(
            "include_ui_tree",
            DEFAULT_INCLUDE_UI_TREE,
        ),
        maxNodes = arguments.optInt("max_nodes", DEFAULT_MAX_NODES),
    )

    fun shouldCaptureScreenshot(arguments: JSONObject, hasUiNodes: Boolean, virtualScreen: Boolean): Boolean {
        val options = resolve(arguments)
        return options.includeScreenshot ||
            (virtualScreen && !hasUiNodes && options.includeUiTree && !arguments.has("include_screenshot"))
    }
}
