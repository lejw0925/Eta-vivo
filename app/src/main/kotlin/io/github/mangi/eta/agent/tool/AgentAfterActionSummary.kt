package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.NodeSnapshot
import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementSource
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import io.github.mangi.eta.agent.model.AgentGuiGuidance
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动作后观察的模型投影：先给结论（界面是否变化、是否切换应用或窗口），再给新的节点列表。
 * 比较节点语义、位置与控件状态，不依赖时间戳或动画帧。
 */
internal object AgentAfterActionSummary {
    fun build(before: NodeSnapshot?, after: NodeSnapshot): JSONObject =
        build(before?.asElements(), after.asElements())

    fun build(before: ElementObservation?, after: ElementObservation): JSONObject {
        val changed = when {
            before == null -> null
            before.packageName != after.packageName || before.windowId != after.windowId -> true
            before.nodes.isEmpty() || after.nodes.isEmpty() -> null
            // The lightweight after snapshot may contain fewer nodes than the previous observation.
            else -> {
                val limit = minOf(
                    before.maxNodes, after.maxNodes,
                    if (before.truncated) before.nodes.size else Int.MAX_VALUE,
                    if (after.truncated) after.nodes.size else Int.MAX_VALUE,
                )
                signature(before.nodes.take(limit)) != signature(after.nodes.take(limit))
            }
        }
        return JSONObject()
            .put("observation_id", after.id)
            .put("package", after.packageName)
            .put("screen_changed", changed ?: JSONObject.NULL)
            .put("comparison_scope", "sampled_ui_nodes_and_window")
            .put("package_changed", before != null && before.packageName != after.packageName)
            .put("window_changed", before != null && before.windowId != after.windowId)
            .put("ui_tree_truncated", after.truncated)
            .put("ui_nodes", JSONArray().also { array -> after.nodes.forEach { array.put(it.compactJson()) } })
            .put(
                "note",
                when (changed) {
                    true -> "应用、窗口或采样节点有变化，不代表业务已完成；核对后可用新的 observation_id 继续操作，需要更多节点或截图时再调用 observe_screen。"
                    false -> AgentGuiGuidance.UNCHANGED_AFTER_ACTION
                    null -> AgentGuiGuidance.UNKNOWN_AFTER_ACTION
                },
            )
    }

    private fun signature(nodes: List<UiNode>): List<String> =
        nodes.map {
            "${it.className}|${it.viewId}|${it.text}|${it.desc}|${it.bounds.toShortString()}|" +
                "${it.checked}|${it.selected}|${it.focused}|${it.enabled}|${it.hint}|" +
                "${it.clickable}|${it.longClickable}|${it.scrollable}|${it.editable}"
        }

    private fun NodeSnapshot.asElements() = ElementObservation(
        id = id, source = ElementSource.ACCESSIBILITY, packageName = packageName, windowId = windowId,
        nodes = nodes.map { node -> UiNode(
            node.index, node.text, node.desc, node.className, node.packageName, node.viewId, node.bounds,
            node.clickable, node.longClickable, node.scrollable, node.focused, node.editable,
            node.password, node.enabled, node.checked, node.selected, node.hint,
        ) }, maxNodes = 30, truncated = truncated,
    )

    /** 精简字段：省略默认值为 false 的布尔与包名，降低每步附带观察的 token 开销。 */
    private fun UiNode.compactJson(): JSONObject = JSONObject()
        .put("index", index)
        .apply {
            if (text.isNotBlank()) put("text", text)
            if (desc.isNotBlank()) put("desc", desc)
            put("class", className.substringAfterLast('.'))
            if (viewId.isNotBlank()) put("view_id", viewId.substringAfterLast('/'))
            put("center", JSONObject().put("x", centerX).put("y", centerY))
            if (clickable) put("clickable", true)
            if (longClickable) put("long_clickable", true)
            if (scrollable) put("scrollable", true)
            if (editable) put("editable", true)
            if (focused) put("focused", true)
            if (password) put("password", true)
            if (!enabled) put("enabled", false)
            checked?.let { put("checked", it) }
            if (selected) put("selected", true)
            if (hint.isNotBlank()) put("hint", hint)
        }
}
