package com.glass.dining.glass.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import com.glass.dining.shared.indoor.SensorCalibration
import com.glass.dining.shared.indoor.TimeSync
import com.glass.dining.shared.indoor.TimeSyncStats
import kotlin.math.abs

object SensorProbe {
    @Volatile var calibration: SensorCalibration = SensorCalibration.rokidGlass3()
        private set
    @Volatile var stats: TimeSyncStats = TimeSyncStats()
        private set
    private var lastCamNs: Long = 0L
    private var lastImuNs: Long = 0L

    fun dump(context: Context, imu: ImuSampleSource): SensorCalibration {
        val camera = dumpCamera(context)
        val sensors = dumpImu(context)
        calibration = camera
        Log.i(TAG, "spatial probe camera fx=${camera.camera.fx} fy=${camera.camera.fy} " +
            "src=${camera.camera.timestampSource} $sensors")
        return camera
    }

    fun noteSync(cameraNs: Long, imuNs: Long) {
        if (cameraNs <= 0L || imuNs <= 0L) return
        stats = TimeSync.push(stats, cameraNs, imuNs, lastCamNs, lastImuNs)
        lastCamNs = cameraNs
        lastImuNs = imuNs
        if (stats.samples == 8 || stats.samples % 40 == 0) {
            Log.i(
                TAG,
                "spatial sync n=${stats.samples} meanNs=${stats.meanOffsetNs} " +
                    "maxNs=${stats.maxAbsOffsetNs} camDt=${stats.cameraDtNs} imuDt=${stats.imuDtNs} " +
                    "usable=${stats.usable}",
            )
            calibration = calibration.copy(timeSync = stats)
        }
    }

    fun dumpCamera(context: Context): SensorCalibration {
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull() ?: return SensorCalibration.rokidGlass3()
            val chars = manager.getCameraCharacteristics(id)
            val size = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val source = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            val sourceName = when (source) {
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "realtime"
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "unknown"
                else -> "other"
            }
            var fx = 820f
            var fy = 820f
            var cx = (size?.width ?: 1280) / 2f
            var cy = (size?.height ?: 720) / 2f
            if (Build.VERSION.SDK_INT >= 23) {
                val intrinsic = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                if (intrinsic != null && intrinsic.size >= 4 && intrinsic[0] > 1f) {
                    fx = intrinsic[0]
                    fy = intrinsic[1]
                    cx = intrinsic[2]
                    cy = intrinsic[3]
                }
            }
            val w = size?.width ?: 1280
            val h = size?.height ?: 720
            Log.i(TAG, "spatial camera id=$id ${w}x$h fx=$fx fy=$fy cx=$cx cy=$cy ts=$sourceName")
            SensorCalibration.fromCamera2(w, h, fx, fy, cx, cy, sourceName)
        } catch (error: Exception) {
            Log.w(TAG, "spatial camera dump ${error.message}")
            SensorCalibration.rokidGlass3()
        }
    }

    fun dumpImu(context: Context): String {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        return "gyro=${gyro?.name}:${gyro?.minDelay} accel=${accel?.name}:${accel?.minDelay}"
    }

    fun offsetMs(): Float = abs(stats.meanOffsetNs) / 1_000_000f

    private const val TAG = "GlassApp"
}
