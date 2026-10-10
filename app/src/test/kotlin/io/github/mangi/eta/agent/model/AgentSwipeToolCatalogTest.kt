package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentSwipeToolCatalogTest {
    private val tools = JSONArray().also(AgentGestureToolCatalog::appendTo)
    private val validator = AgentToolCallValidator(tools)

    @Test
    fun publishedSchemaAcceptsEitherLegacyEndpointsOrContinuousPath() {
        assertNull(validate("""{"coordinate_space":"screen","x1":10,"y1":20,"x2":30,"y2":40}"""))
        assertNull(validate("""{"coordinate_space":"normalized","path":[{"x":10,"y":20},{"x":30,"y":40},{"x":10,"y":20}],"duration_ms":800}"""))
    }

    @Test
    fun missingCoordinateSpaceMissingShapeAndMixedShapesAreRejected() {
        assertNotNull(validate("""{"path":[{"x":10,"y":20},{"x":30,"y":40}]}"""))
        assertNotNull(validate("""{"coordinate_space":"screen"}"""))
        assertNotNull(validate("""{"coordinate_space":"screen","x1":10,"y1":20,"x2":30}"""))
        listOf("x1", "y1", "x2", "y2").forEach { key ->
            assertNotNull(validate(JSONObject().put("coordinate_space", "screen")
                .put("path", JSONArray("""[{"x":10,"y":20},{"x":30,"y":40}]""")).put(key, 0).toString()))
        }
    }

    @Test
    fun invalidPointShapesAndExcessivePathsCannotReachExecution() {
        listOf("[]", "[{\"x\":1,\"y\":2}]", "[[1,2],[3,4]]",
            "[{\"x\":1,\"y\":2},{\"x\":3.5,\"y\":4}]",
            "[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4,\"duration_ms\":10}]").forEach { path ->
            assertNotNull(validate(JSONObject().put("coordinate_space", "screen").put("path", JSONArray(path)).toString()))
        }
        assertNotNull(validate(JSONObject().put("coordinate_space", "screen")
            .put("path", JSONArray().apply { repeat(65) { put(JSONObject().put("x", it).put("y", it)) } }).toString()))
    }

    @Test
    fun explicitVirtualScreenToolAlsoPublishesPath() {
        val virtual = JSONArray().also(AgentVirtualScreenToolCatalog::appendTo)
        assertNull(AgentToolCallValidator(virtual).validate(AgentModelClient.ToolCall("virtual-path", "virtual_screen",
            """{"action":"swipe","path":[{"x":10,"y":20},{"x":30,"y":40}],"durationMs":800}""")))
    }

    private fun validate(arguments: String) = validator.validate(AgentModelClient.ToolCall("swipe-path", "swipe", arguments))
}
