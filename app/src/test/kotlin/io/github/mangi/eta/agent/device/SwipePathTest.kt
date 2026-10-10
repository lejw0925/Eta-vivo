package io.github.mangi.eta.agent.device

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SwipePathTest {
    @Test
    fun legacyEndpointsAndClosedPathKeepTheirOrder() {
        assertEquals(listOf(point(1, 2), point(3, 4)), SwipePath.parse(JSONObject()
            .put("x1", 1).put("y1", 2).put("x2", 3).put("y2", 4)))
        val closed = listOf(point(20, 30), point(90, 30), point(90, 80), point(20, 30))
        assertEquals(closed, SwipePath.parse(JSONObject().put("path", SwipePath.toJson(closed))))
    }

    @Test
    fun ambiguousOrIncompleteInputsAreRejected() {
        listOf("x1", "y1", "x2", "y2").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) {
                SwipePath.parse(JSONObject().put("path", SwipePath.toJson(listOf(point(1, 2), point(3, 4)))).put(key, 1))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { SwipePath.parse(JSONObject()) }
        assertThrows(IllegalArgumentException::class.java) {
            SwipePath.parse(JSONObject().put("x1", 1).put("y1", 2).put("x2", 3))
        }
    }

    @Test
    fun pointCountAndEveryPointShapeAreBounded() {
        listOf(0, 1, 65).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) {
                SwipePath.parsePath(SwipePath.toJson(List(count) { point(it, it) }))
            }
        }
        assertEquals(64, SwipePath.parsePath(SwipePath.toJson(List(64) { point(it, it) })).size)
        listOf(JSONObject().put("x", 4), JSONArray().put(4).put(5),
            JSONObject().put("x", 4).put("y", 5).put("z", 6), JSONObject.NULL).forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) {
                SwipePath.parsePath(JSONArray().put(JSONObject().put("x", 1).put("y", 2)).put(malformed))
            }
        }
    }

    @Test
    fun coordinateStringsFractionsAndOverflowAreRejected() {
        listOf("5", 5.25, 2147483648L, Long.MIN_VALUE, JSONObject.NULL, true).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                SwipePath.parsePath(JSONArray().put(JSONObject().put("x", 1).put("y", 2))
                    .put(JSONObject().put("x", invalid).put("y", 5)))
            }
        }
    }

    @Test
    fun interiorPointsMustBeInsideTheSameDisplay() {
        SwipePath.validate(listOf(point(0, 0), point(99, 199)), 100, 200)
        listOf(point(-1, 20), point(100, 20), point(20, 200)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                SwipePath.validate(listOf(point(1, 2), invalid, point(90, 190)), 100, 200)
            }
        }
    }

    private fun point(x: Int, y: Int) = SwipePath.Point(x, y)
}
