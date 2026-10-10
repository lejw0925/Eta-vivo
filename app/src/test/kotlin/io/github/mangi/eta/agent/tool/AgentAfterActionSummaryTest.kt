package io.github.mangi.eta.agent.tool

import android.graphics.Rect
import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementSource
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentAfterActionSummaryTest {
    @Test
    fun unchangedSampleReportsEvidenceWithoutClaimingTheActionFailed() {
        val before = observation("o1", "app.a", listOf(node(0, "搜索")))
        val summary = AgentAfterActionSummary.build(before, observation("o2", "app.a", listOf(node(0, "搜索"))))
        assertFalse(summary.getBoolean("screen_changed"))
        assertFalse(summary.getBoolean("package_changed"))
        assertEquals("o2", summary.getString("observation_id"))
        assertEquals("sampled_ui_nodes_and_window", summary.getString("comparison_scope"))
        assertTrue(summary.getString("note").contains("不能据此判定动作失败或应用卡死"))
        assertTrue(summary.getString("note").contains("提交结果不明确时只读核对"))
    }

    @Test
    fun missingBaselineAndEmptyNodesReportUnknownRatherThanProgress() {
        val after = observation("o2", "app", listOf(node(0, "提交")))
        for (summary in listOf(
            AgentAfterActionSummary.build(null, after),
            AgentAfterActionSummary.build(observation("o1", "app", emptyList()), after),
            AgentAfterActionSummary.build(after, observation("o3", "app", emptyList())),
        )) {
            assertTrue(summary.isNull("screen_changed"))
            assertTrue(summary.getString("note").contains("不直接重放动作"))
        }
    }

    @Test
    fun smallerAfterSnapshotDoesNotInventProgress() {
        val nodes = (0 until 60).map { node(it, "Row $it") }
        val before = observation("o1", "app", nodes).copy(maxNodes = 60, truncated = true)
        val after = observation("o2", "app", nodes.take(30)).copy(truncated = true)
        assertFalse(AgentAfterActionSummary.build(before, after).getBoolean("screen_changed"))
        // NodeSnapshot carries truncation without the requested node limit.
        val smallerBefore = observation("o0", "app", nodes.take(1)).copy(truncated = true)
        assertFalse(AgentAfterActionSummary.build(smallerBefore, after).getBoolean("screen_changed"))
        val changed = after.copy(nodes = after.nodes.toMutableList().apply { this[29] = node(29, "Updated") })
        assertTrue(AgentAfterActionSummary.build(before, changed).getBoolean("screen_changed"))
        val removed = after.copy(nodes = nodes.take(20), truncated = false)
        assertTrue(AgentAfterActionSummary.build(before, removed).getBoolean("screen_changed"))
    }

    @Test
    fun packageOrWindowChangeIsObservedEvenWhenNodesAreIdentical() {
        val before = observation("o1", "app", listOf(node(0, "提交")))
        for (after in listOf(before.copy(id = "o2", packageName = "other"), before.copy(id = "o3", windowId = 2))) {
            assertTrue(AgentAfterActionSummary.build(before, after).getBoolean("screen_changed"))
        }
    }

    @Test
    fun changedScreenCarriesCompactNodesAndPackageSwitch() {
        val before = observation("o1", "app.a", listOf(node(0, "搜索")))
        val after = observation("o2", "app.b", listOf(node(0, "结果", clickable = true)))
        val summary = AgentAfterActionSummary.build(before, after)
        assertTrue(summary.getBoolean("screen_changed"))
        assertTrue(summary.getBoolean("package_changed"))
        val compact = summary.getJSONArray("ui_nodes").getJSONObject(0)
        assertEquals("结果", compact.getString("text"))
        assertEquals("TextView", compact.getString("class"))
        assertTrue(compact.getBoolean("clickable"))
        // 默认值为 false 的字段不输出，节省 token。
        assertFalse(compact.has("editable"))
        assertFalse(compact.has("password"))
    }

    @Test
    fun stateChangesWithoutTextOrPositionChangesCountAsProgress() {
        val before = node(0, "蓝牙").copy(checked = false)
        for (changed in listOf(
            before.copy(checked = true), before.copy(selected = true), before.copy(focused = true),
            before.copy(enabled = false), before.copy(hint = "可用设备"),
        )) {
            assertTrue(AgentAfterActionSummary.build(observation("o1", "app", listOf(before)),
                observation("o2", "app", listOf(changed))).getBoolean("screen_changed"))
        }
    }

    @Test
    fun toggleStateAndHintAreVisibleToTheModel() {
        val toggle = node(0, "蓝牙").copy(checked = false)
        val field = node(1, "").copy(editable = true, hint = "搜索联系人")
        val nodes = AgentAfterActionSummary.build(null, observation("o1", "app", listOf(toggle, field)))
            .getJSONArray("ui_nodes")
        assertFalse(nodes.getJSONObject(0).getBoolean("checked"))
        assertEquals("搜索联系人", nodes.getJSONObject(1).getString("hint"))
        assertFalse(nodes.getJSONObject(1).has("checked"))
    }

    private fun observation(id: String, pkg: String, nodes: List<UiNode>) = ElementObservation(
        id = id, source = ElementSource.ACCESSIBILITY, packageName = pkg, windowId = 1,
        nodes = nodes, maxNodes = 30, truncated = false,
    )

    private fun node(index: Int, text: String, clickable: Boolean = false) = UiNode(
        index = index, text = text, desc = "", className = "android.widget.TextView", packageName = "app",
        viewId = "", bounds = Rect(0, 0, 100, 50), clickable = clickable, longClickable = false,
        scrollable = false, focused = false, editable = false, password = false, enabled = true,
    )
}
