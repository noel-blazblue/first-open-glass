package com.glass.dining.shared.link

import org.json.JSONObject

data class GlassPose(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val tracking: String = "",
    val tNs: Long = 0L,
)

object PoseWire {
    const val DC_POSE = "pose"

    fun poseJson(pose: GlassPose): String {
        return JSONObject()
            .put("yaw", pose.yaw.toDouble())
            .put("pitch", pose.pitch.toDouble())
            .put("roll", pose.roll.toDouble())
            .put("x", pose.x.toDouble())
            .put("y", pose.y.toDouble())
            .put("z", pose.z.toDouble())
            .put("tracking", pose.tracking)
            .put("tNs", pose.tNs)
            .toString()
    }

    fun parsePose(raw: String?): GlassPose? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            GlassPose(
                yaw = obj.optDouble("yaw").toFloat(),
                pitch = obj.optDouble("pitch").toFloat(),
                roll = obj.optDouble("roll").toFloat(),
                x = obj.optDouble("x").toFloat(),
                y = obj.optDouble("y").toFloat(),
                z = obj.optDouble("z").toFloat(),
                tracking = obj.optString("tracking"),
                tNs = obj.optLong("tNs"),
            )
        } catch (_: Exception) {
            raw.toFloatOrNull()?.let { GlassPose(yaw = it) }
        }
    }
}
