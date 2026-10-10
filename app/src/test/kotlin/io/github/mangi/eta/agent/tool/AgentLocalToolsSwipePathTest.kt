package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.model.Settings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalToolsSwipePathTest {
    @Test
    fun invalidInteriorPointIsRejectedBeforeAnyTouchForAllCoordinateSpaces() {
        tools().use { tools ->
            tools.execute(AgentModelClient.ToolCall("observe", "observe_screen", "{}"))
            listOf(
                "normalized" to "[{\"x\":200,\"y\":300},{\"x\":1000,\"y\":500},{\"x\":800,\"y\":700}]",
                "screen" to "[{\"x\":200,\"y\":300},{\"x\":1080,\"y\":500},{\"x\":800,\"y\":700}]",
                "screenshot" to "[{\"x\":100,\"y\":150},{\"x\":540,\"y\":500},{\"x\":400,\"y\":700}]",
            ).forEach { (space, path) ->
                val result = tools.execute(AgentModelClient.ToolCall("bad-$space", "swipe",
                    """{"coordinate_space":"$space","path":$path}"""))
                assertEquals(space, "INVALID_ARGUMENT", JSONObject(result.content).getString("code"))
            }
        }
    }

    @Test
    fun missingAccessibilityAndRootAlsoGatePathSwipes() {
        tools().use { tools ->
            val result = tools.execute(AgentModelClient.ToolCall("disabled", "swipe",
                """{"coordinate_space":"normalized","path":[{"x":1,"y":2},{"x":3,"y":4}]}"""))
            assertEquals("ACCESSIBILITY_UNAVAILABLE", JSONObject(result.content).getString("code"))
        }
    }

    @Test
    fun invalidPointTypesAndMixedFormsReturnArgumentErrors() {
        tools().use { tools ->
            listOf(
                """{"coordinate_space":"screen","path":[{"x":"1","y":2},{"x":3,"y":4}]}""",
                """{"coordinate_space":"screen","path":[{"x":1,"y":2},{"x":3,"y":4}],"x1":1}""",
            ).forEach { arguments ->
                val result = tools.execute(AgentModelClient.ToolCall("invalid", "swipe", arguments))
                assertEquals("INVALID_ARGUMENT", JSONObject(result.content).getString("code"))
            }
        }
    }

    private fun tools() = AgentLocalTools(
        context = RuntimeEnvironment.getApplication() as Context,
        logger = NoOpLogger,
        rootAvailable = { false },
        deviceDirectToolsEnabled = { true },
        virtualScreenSettings = { Settings(virtualScreenEnabled = false) },
        screenObservationProvider = {
            RootShellDeviceController.Observation(
                content = JSONObject().put("ok", true).put("tool", "observe_screen").toString(),
                image = null,
                elementObservation = null,
                coordinateSpace = RootShellDeviceController.CoordinateSpace(1080, 2400, 540, 1200),
            )
        },
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
