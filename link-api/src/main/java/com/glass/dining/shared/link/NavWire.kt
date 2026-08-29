package com.glass.dining.shared.link

import org.json.JSONObject
import kotlin.math.max

data class NavHint(
    val turn: String = "straight",
    val meters: Int = 0,
    val text: String = "跟着走",
    val storeName: String = "",
    val remaining: Int = 0,
    val mode: String = "",
    val headingDeg: Float = 0f,
    val elevationDeg: Float = 0f,
    val stage: String = "",
    val sessionId: String = "",
    val tracking: String = "",
    val waypoints: String = "",
) {
    val visual: Boolean
        get() = mode.isNotBlank() && tracking != IndoorProtocol.TRACK_LOST
}

object NavWire {
    fun hintJson(hint: NavHint): String {
        return JSONObject()
            .put("turn", hint.turn)
            .put("meters", hint.meters)
            .put("text", hint.text)
            .put("storeName", hint.storeName)
            .put("remaining", hint.remaining)
            .put("mode", hint.mode)
            .put("headingDeg", hint.headingDeg.toDouble())
            .put("elevationDeg", hint.elevationDeg.toDouble())
            .put("stage", hint.stage)
            .put("sessionId", hint.sessionId)
            .put("tracking", hint.tracking)
            .put("waypoints", hint.waypoints)
            .toString()
    }

    fun parseHint(raw: String?): NavHint {
        if (raw.isNullOrBlank()) return NavHint()
        return try {
            val obj = JSONObject(raw)
            NavHint(
                turn = obj.optString("turn", "straight"),
                meters = obj.optInt("meters", 0),
                text = obj.optString("text"),
                storeName = obj.optString("storeName"),
                remaining = obj.optInt("remaining", 0),
                mode = obj.optString("mode"),
                headingDeg = obj.optDouble("headingDeg").toFloat(),
                elevationDeg = obj.optDouble("elevationDeg").toFloat(),
                stage = obj.optString("stage"),
                sessionId = obj.optString("sessionId"),
                tracking = obj.optString("tracking"),
                waypoints = obj.optString("waypoints"),
            )
        } catch (_: Exception) {
            NavHint(text = raw.take(24))
        }
    }

    fun scriptFor(storeName: String, distanceMeters: Int): List<NavHint> {
        val d = distanceMeters.coerceIn(16, 200)
        return listOf(
            NavHint("left", max(6, d / 8), "出电梯后左转", storeName),
            NavHint("straight", max(10, d / 2), "沿走廊再走约${max(10, d / 2)}米", storeName),
            NavHint("left", max(4, d / 10), "左手第三家就是目的店", storeName),
            NavHint("arrive", 0, "已到门口，停下即可", storeName),
        )
    }

    val indoorScript: List<NavHint> = emptyList()
}

object IndoorProtocol {
    const val TRACK_GOOD = "good"
    const val TRACK_WEAK = "weak"
    const val TRACK_LOST = "tracking_lost"
    const val SCAN_REQUIRED = "scan_required"

    fun encodeWaypoints(points: List<FloatArray>): String {
        if (points.isEmpty()) return ""
        return points.joinToString(";") { p ->
            val x = p.getOrElse(0) { 0f }
            val y = p.getOrElse(1) { 0f }
            val z = p.getOrElse(2) { 0f }
            "$x,$y,$z"
        }
    }

    fun parseWaypoints(raw: String?): List<FloatArray> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(";").mapNotNull { token ->
            val parts = token.split(",")
            if (parts.size < 3) return@mapNotNull null
            val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
            val z = parts[2].toFloatOrNull() ?: return@mapNotNull null
            floatArrayOf(x, y, z)
        }
    }
}
