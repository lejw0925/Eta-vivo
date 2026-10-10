package io.github.mangi.eta.agent.device

import org.json.JSONArray
import org.json.JSONObject

/** A single pointer follows these vertices without lifting between segments. */
internal object SwipePath {
    const val MAX_POINTS = 64
    private val ENDPOINT_KEYS = listOf("x1", "y1", "x2", "y2")

    data class Point(val x: Int, val y: Int)

    fun parse(arguments: JSONObject): List<Point> {
        if (arguments.has("path")) {
            require(ENDPOINT_KEYS.none(arguments::has)) { "path 与 x1/y1/x2/y2 不能同时填写" }
            return parsePath(arguments.opt("path"))
        }
        return listOf(
            Point(integer(arguments, "x1"), integer(arguments, "y1")),
            Point(integer(arguments, "x2"), integer(arguments, "y2")),
        )
    }

    fun parsePath(value: Any?): List<Point> {
        require(value is JSONArray && value.length() in 2..MAX_POINTS) { "path 必须包含 2–$MAX_POINTS 个坐标点" }
        return List(value.length()) { index ->
            val point = value.opt(index)
            require(point is JSONObject && point.length() == 2 && point.has("x") && point.has("y")) {
                "path[$index] 必须是仅含 x、y 整数的对象"
            }
            Point(integer(point, "x"), integer(point, "y"))
        }
    }

    fun validate(points: List<Point>, width: Int, height: Int) {
        require(points.size in 2..MAX_POINTS) { "path 必须包含 2–$MAX_POINTS 个坐标点" }
        require(width > 0 && height > 0) { "INVALID_SCREEN_DIMENSIONS" }
        points.forEachIndexed { index, point ->
            require(point.x in 0 until width && point.y in 0 until height) {
                "path[$index] 超出屏幕范围：(${point.x},${point.y}) not in ${width}x$height"
            }
        }
    }

    fun toJson(points: List<Point>): JSONArray = JSONArray().apply {
        points.forEach { put(JSONObject().put("x", it.x).put("y", it.y)) }
    }

    fun integer(arguments: JSONObject, name: String): Int {
        val value = arguments.opt(name)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) {
            "$name 必须是整数"
        }
        return value.toInt()
    }
}
