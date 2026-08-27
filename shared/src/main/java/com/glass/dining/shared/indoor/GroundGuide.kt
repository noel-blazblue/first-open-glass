package com.glass.dining.shared.indoor

import kotlin.math.atan2

data class GroundChevron(
    val x: Float,
    val y: Float,
    val size: Float,
    val angleDeg: Float,
    val alpha: Float = 1f,
)

/**
 * 把地面世界点投成 HUD 上的贴地 chevron：近大远小，开口沿路径切向。
 */
object GroundGuide {
    fun project(
        world: List<Vec3>,
        pose: Pose3,
        calib: SensorCalibration,
        viewW: Float,
        viewH: Float,
        faded: Boolean = false,
    ): List<GroundChevron> {
        val hits = world.mapNotNull { point ->
            val cam = pose.inverseTransform(point)
            calib.projectHud(cam.x, cam.y, cam.z, viewW, viewH)?.let { hit -> point to hit }
        }
        if (hits.isEmpty()) return emptyList()
        val alpha = if (faded) 0.45f else 1f
        return hits.mapIndexed { index, (_, hit) ->
            val next = hits.getOrNull(index + 1)?.second
            val prev = hits.getOrNull(index - 1)?.second
            val angle = when {
                next != null -> angleOf(hit.x, hit.y, next.x, next.y)
                prev != null -> angleOf(prev.x, prev.y, hit.x, hit.y)
                else -> 0f
            }
            val depth = hit.depth.coerceAtLeast(4f)
            val size = (1.15f / depth).coerceIn(0.12f, 0.38f) * viewW
            GroundChevron(hit.x, hit.y, size, angle, alpha)
        }
    }

    fun angleOf(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        return Math.toDegrees(atan2((x1 - x0).toDouble(), (y0 - y1).toDouble())).toFloat()
    }
}
