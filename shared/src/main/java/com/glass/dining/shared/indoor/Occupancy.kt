package com.glass.dining.shared.indoor

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class CellKind {
    UNKNOWN,
    FREE,
    OCCUPIED,
}

data class OccupancyCell(
    val kind: CellKind = CellKind.UNKNOWN,
    val confidence: Float = 0f,
)

data class GroundPlane(
    val heightM: Float = 1.55f,
    val pitchDeg: Float = 0f,
)

class OccupancyGrid(
    val cellM: Float = 0.25f,
    val extentM: Float = 14f,
) {
    private val size: Int = ((extentM * 2f) / cellM).roundToInt().coerceAtLeast(8)
    private val cells = Array(size * size) { OccupancyCell() }
    var origin: Vec3 = Vec3()
        private set
    var plane: GroundPlane = GroundPlane()
        private set

    fun recenter(pose: Pose3, pitchDeg: Float = pose.pitchDeg) {
        origin = pose.position
        plane = GroundPlane(heightM = SensorCalibration.rokidGlass3().eyeHeightM, pitchDeg = pitchDeg)
    }

    fun markRay(pose: Pose3, headingOffsetDeg: Float, rangeM: Float, occupied: Boolean) {
        val steps = (rangeM / cellM).roundToInt().coerceIn(1, 40)
        val yaw = Math.toRadians((pose.yawDeg + headingOffsetDeg).toDouble())
        for (i in 1..steps) {
            val d = i * cellM
            val p = pose.position + Vec3((sin(yaw) * d).toFloat(), (cos(yaw) * d).toFloat(), 0f)
            val last = i == steps
            write(p, if (occupied && last) CellKind.OCCUPIED else CellKind.FREE, if (last) 0.7f else 0.45f)
        }
    }

    fun markCorridor(pose: Pose3, lengthM: Float = 5f) {
        markRay(pose, 0f, lengthM, occupied = false)
        markRay(pose, -8f, lengthM * 0.8f, occupied = false)
        markRay(pose, 8f, lengthM * 0.8f, occupied = false)
    }

    fun markBlocked(pose: Pose3, headingOffsetDeg: Float = 0f, atM: Float = 2.4f) {
        markRay(pose, headingOffsetDeg, atM, occupied = true)
    }

    fun isFree(world: Vec3, radiusM: Float = 0.3f): Boolean {
        val cell = read(world)
        if (cell.kind == CellKind.OCCUPIED && cell.confidence >= 0.5f) return false
        val ring = listOf(
            Vec3(radiusM, 0f, 0f),
            Vec3(-radiusM, 0f, 0f),
            Vec3(0f, radiusM, 0f),
            Vec3(0f, -radiusM, 0f),
        )
        return ring.none { read(world + it).kind == CellKind.OCCUPIED && read(world + it).confidence >= 0.5f }
    }

    fun waypoints(pose: Pose3, headingOffsetDeg: Float, distances: FloatArray): List<Vec3> {
        val yaw = Math.toRadians((pose.yawDeg + headingOffsetDeg).toDouble())
        return distances.toList().mapNotNull { meters ->
            val p = pose.position + Vec3(
                (sin(yaw) * meters).toFloat(),
                (cos(yaw) * meters).toFloat(),
                pose.position.z - plane.heightM,
            )
            if (isFree(p)) p else null
        }
    }

    fun read(world: Vec3): OccupancyCell {
        val i = indexOf(world) ?: return OccupancyCell()
        return cells[i]
    }

    private fun write(world: Vec3, kind: CellKind, confidence: Float) {
        val i = indexOf(world) ?: return
        val prev = cells[i]
        if (prev.kind == CellKind.OCCUPIED && kind == CellKind.FREE && prev.confidence >= 0.6f) return
        cells[i] = OccupancyCell(kind, confidence)
    }

    private fun indexOf(world: Vec3): Int? {
        val dx = ((world.x - origin.x) / cellM + size / 2f).roundToInt()
        val dy = ((world.y - origin.y) / cellM + size / 2f).roundToInt()
        if (dx !in 0 until size || dy !in 0 until size) return null
        return dy * size + dx
    }
}

object LocalPath {
    val CHEVRON_M = floatArrayOf(6f, 9f, 12f)

    fun conservative(grid: OccupancyGrid, pose: Pose3, headingDeg: Float): List<Vec3> {
        return grid.waypoints(pose, headingDeg, CHEVRON_M)
    }
}
