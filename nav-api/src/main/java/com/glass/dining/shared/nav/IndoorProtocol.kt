package com.glass.dining.shared.nav

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
