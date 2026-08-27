package com.glass.dining.shared.indoor

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

/**
 * 相机、IMU、HUD 外参与时间同步统计。真机 [SensorProbe] 会覆盖内参。
 */
data class CameraIntrinsics(
    val width: Int = 1280,
    val height: Int = 720,
    val fx: Float = 820f,
    val fy: Float = 820f,
    val cx: Float = 640f,
    val cy: Float = 360f,
    val timestampSource: String = "unknown",
) {
    val hfovDeg: Float
        get() = Math.toDegrees(2.0 * atan((width / (2.0 * fx)))).toFloat()
    val vfovDeg: Float
        get() = Math.toDegrees(2.0 * atan((height / (2.0 * fy)))).toFloat()
}

data class TimeSyncStats(
    val samples: Int = 0,
    val meanOffsetNs: Long = 0L,
    val maxAbsOffsetNs: Long = 0L,
    val cameraDtNs: Long = 0L,
    val imuDtNs: Long = 0L,
) {
    val usable: Boolean
        get() = samples >= 8 && maxAbsOffsetNs < 20_000_000L && cameraDtNs in 20_000_000L..120_000_000L
}

data class SensorCalibration(
    val camera: CameraIntrinsics = CameraIntrinsics(),
    val hudHfovDeg: Float = 30f,
    val eyeHeightM: Float = 1.55f,
    val cameraTiltDeg: Float = 3f,
    val gyroScale: Vec3 = Vec3(1f, 1f, 1f),
    val accelScale: Vec3 = Vec3(1f, 1f, 1f),
    val timeSync: TimeSyncStats = TimeSyncStats(),
) {
    fun projectToHud(camX: Float, camY: Float, camZ: Float, viewW: Float, viewH: Float): Pair<Float, Float>? {
        if (camY <= 0.08f) return null
        val half = Math.toRadians(hudHfovDeg / 2.0)
        val tanHalf = tan(half).toFloat()
        val nx = (camX / camY) / tanHalf
        val ny = (camZ / camY) / tanHalf
        if (abs(nx) > 1.35f || abs(ny) > 1.6f) return null
        val x = viewW / 2f + nx * (viewW * 0.42f)
        val y = viewH * 0.20f + ((1f - (ny + 1f) / 2f).coerceIn(0f, 1f)) * (viewH * 0.48f)
        return x to y
    }

    companion object {
        fun rokidGlass3(): SensorCalibration = SensorCalibration()

        fun fromCamera2(
            width: Int,
            height: Int,
            fx: Float,
            fy: Float,
            cx: Float,
            cy: Float,
            timestampSource: String,
        ): SensorCalibration {
            return SensorCalibration(
                camera = CameraIntrinsics(width, height, fx, fy, cx, cy, timestampSource),
            )
        }
    }
}

object TimeSync {
    fun push(prev: TimeSyncStats, cameraNs: Long, imuNs: Long, lastCamNs: Long, lastImuNs: Long): TimeSyncStats {
        val offset = cameraNs - imuNs
        val camDt = if (lastCamNs > 0L) cameraNs - lastCamNs else 0L
        val imuDt = if (lastImuNs > 0L) imuNs - lastImuNs else 0L
        val n = prev.samples + 1
        val mean = prev.meanOffsetNs + (offset - prev.meanOffsetNs) / n
        return TimeSyncStats(
            samples = n,
            meanOffsetNs = mean,
            maxAbsOffsetNs = maxOf(prev.maxAbsOffsetNs, abs(offset)),
            cameraDtNs = camDt,
            imuDtNs = imuDt,
        )
    }
}
