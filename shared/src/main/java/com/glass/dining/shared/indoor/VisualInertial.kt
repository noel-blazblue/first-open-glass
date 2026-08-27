package com.glass.dining.shared.indoor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Keyframe(
    val id: Int,
    val pose: Pose3,
    val grid: IntArray,
)

data class TrackerState(
    val pose: Pose3 = Pose3(),
    val velocity: Vec3 = Vec3(),
    val quality: TrackQuality = TrackQuality.LOST,
    val keyframes: List<Keyframe> = emptyList(),
    val lastImuNs: Long = 0L,
    val lastCamNs: Long = 0L,
    val stillHits: Int = 0,
    val visualHits: Int = 0,
)

/**
 * 轻量单目 VIO：陀螺积分姿态、加速度积分位移、16×16 视觉栅格纠偏与回环。
 * 不替代完整 SLAM，但给出可贴地投影的局部位姿。
 */
class VisualInertialFilter(
    private val calib: SensorCalibration = SensorCalibration.rokidGlass3(),
) {
    var state: TrackerState = TrackerState()
        private set

    fun reset(tNs: Long = 0L) {
        state = TrackerState(pose = Pose3(tNs = tNs), quality = TrackQuality.WEAK)
    }

    fun ingestImu(sample: ImuSample) {
        if (state.lastImuNs == 0L) {
            state = state.copy(lastImuNs = sample.tNs, pose = state.pose.copy(tNs = sample.tNs))
            return
        }
        val dt = ((sample.tNs - state.lastImuNs).coerceIn(1_000_000L, 40_000_000L)) / 1_000_000_000f
        val gx = sample.gx * calib.gyroScale.x
        val gy = sample.gy * calib.gyroScale.y
        val gz = sample.gz * calib.gyroScale.z
        val orient = Quat.fromGyro(gx, gy, gz, dt).times(state.pose.orientation).normalized()
        val accel = Vec3(
            sample.ax * calib.accelScale.x,
            sample.ay * calib.accelScale.y,
            sample.az * calib.accelScale.z,
        )
        val worldAcc = orient.rotate(accel) - Vec3(0f, 0f, GRAVITY)
        val still = isStill(gx, gy, gz, accel)
        val stillHits = if (still) state.stillHits + 1 else 0
        val velocity = if (stillHits >= ZUPT_HITS) {
            Vec3()
        } else {
            (state.velocity + worldAcc * dt).damp(0.02f)
        }
        val position = if (stillHits >= ZUPT_HITS) {
            state.pose.position
        } else {
            state.pose.position + velocity * dt
        }
        val quality = when {
            dt > 0.05f -> TrackQuality.LOST
            stillHits >= ZUPT_HITS -> if (state.visualHits > 0) TrackQuality.GOOD else TrackQuality.WEAK
            velocity.length() > 3.5f -> TrackQuality.LOST
            state.visualHits > 0 -> TrackQuality.GOOD
            else -> TrackQuality.WEAK
        }
        state = state.copy(
            pose = Pose3(sample.tNs, position, orient),
            velocity = velocity,
            quality = quality,
            lastImuNs = sample.tNs,
            stillHits = stillHits,
        )
    }

    fun ingestVisual(sample: VisualSample): Boolean {
        if (sample.grid.size < 16) return false
        val relocated = tryRelocalize(sample.grid)
        val last = state.keyframes.lastOrNull()
        val shift = if (last != null) gridShift(last.grid, sample.grid) else 0f to 0f
        val yawCorr = (-shift.first * 0.35f).coerceIn(-4f, 4f)
        val yaw = Angle.wrapDeg(state.pose.yawDeg + yawCorr)
        val pitch = state.pose.pitchDeg
        val roll = state.pose.rollDeg
        val orient = Quat.fromYawPitchRoll(yaw, pitch, roll)
        val visualHits = state.visualHits + 1
        val quality = when {
            relocated -> TrackQuality.GOOD
            sample.sharpness < 8.0 -> TrackQuality.WEAK
            abs(shift.first) > 6f -> TrackQuality.WEAK
            else -> TrackQuality.GOOD
        }
        val pose = state.pose.copy(tNs = sample.tNs, orientation = orient)
        val keys = maybeKeyframe(pose, sample.grid)
        state = state.copy(
            pose = pose,
            quality = quality,
            visualHits = visualHits,
            lastCamNs = sample.tNs,
            keyframes = keys,
        )
        return relocated
    }

    fun pose(): Pose3 = state.pose
    fun quality(): TrackQuality = state.quality

    fun tryRelocalize(grid: IntArray): Boolean {
        if (grid.size < 16) return false
        val hit = state.keyframes.minByOrNull { gridDistance(it.grid, grid) } ?: return false
        val dist = gridDistance(hit.grid, grid)
        if (dist > LOOP_GRID) return false
        if (state.pose.distanceTo(hit.pose) < 1.2f) return false
        state = state.copy(
            pose = hit.pose.copy(tNs = state.pose.tNs),
            velocity = Vec3(),
            quality = TrackQuality.GOOD,
        )
        return true
    }

    private fun maybeKeyframe(pose: Pose3, grid: IntArray): List<Keyframe> {
        val last = state.keyframes.lastOrNull()
        if (last != null && pose.distanceTo(last.pose) < 1.6f &&
            abs(Angle.deltaDeg(last.pose.yawDeg, pose.yawDeg)) < 25f
        ) {
            return state.keyframes
        }
        val next = state.keyframes + Keyframe(state.keyframes.size + 1, pose, grid.copyOf())
        return if (next.size <= 48) next else next.takeLast(48)
    }

    companion object {
        const val GRAVITY = 9.81f
        const val ZUPT_HITS = 8
        const val LOOP_GRID = 18f

        fun isStill(gx: Float, gy: Float, gz: Float, accel: Vec3): Boolean {
            val gyro = sqrt(gx * gx + gy * gy + gz * gz)
            val mag = accel.length()
            return gyro < 0.12f && abs(mag - GRAVITY) < 0.55f
        }

        fun gridDistance(a: IntArray, b: IntArray): Float {
            val n = minOf(a.size, b.size)
            if (n == 0) return 999f
            var sum = 0
            for (i in 0 until n) sum += abs(a[i] - b[i])
            return sum / n.toFloat()
        }

        fun gridShift(a: IntArray, b: IntArray): Pair<Float, Float> {
            if (a.size < 256 || b.size < 256) return 0f to 0f
            var ax = 0f
            var ay = 0f
            var bx = 0f
            var by = 0f
            var aw = 0f
            var bw = 0f
            for (y in 0 until 16) {
                for (x in 0 until 16) {
                    val i = y * 16 + x
                    val va = a[i].toFloat()
                    val vb = b[i].toFloat()
                    ax += x * va
                    ay += y * va
                    aw += va
                    bx += x * vb
                    by += y * vb
                    bw += vb
                }
            }
            if (aw < 1f || bw < 1f) return 0f to 0f
            return (bx / bw - ax / aw) to (by / bw - ay / aw)
        }
    }
}

private fun Vec3.damp(k: Float): Vec3 = this * (1f - k)

fun headingFromYaw(currentYaw: Float, targetYaw: Float): Float {
    return Angle.wrapDeg(targetYaw - currentYaw)
}

fun pointAhead(pose: Pose3, meters: Float, headingOffsetDeg: Float = 0f): Vec3 {
    val yaw = Math.toRadians((pose.yawDeg + headingOffsetDeg).toDouble())
    val dx = (sin(yaw) * meters).toFloat()
    val dy = (cos(yaw) * meters).toFloat()
    return pose.position + Vec3(dx, dy, -pose.position.z)
}
