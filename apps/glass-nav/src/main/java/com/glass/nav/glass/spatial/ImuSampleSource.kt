package com.glass.nav.glass.spatial

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.glass.dining.shared.indoor.ImuSample
import java.util.concurrent.atomic.AtomicReference

class ImuSampleSource(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val latest = AtomicReference<ImuSample?>()
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var ax = 0f
    private var ay = 0f
    private var az = 9.81f
    private var gyroNs = 0L
    private var accelNs = 0L
    var onSample: ((ImuSample) -> Unit)? = null

    val last: ImuSample? get() = latest.get()
    val gyroName: String get() = gyro?.name ?: "none"
    val accelName: String get() = accel?.name ?: "none"

    fun start() {
        gyro?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        Log.i(TAG, "imu raw gyro=$gyroName accel=$accelName")
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values.getOrElse(0) { 0f }
                gy = event.values.getOrElse(1) { 0f }
                gz = event.values.getOrElse(2) { 0f }
                gyroNs = event.timestamp
            }
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values.getOrElse(0) { 0f }
                ay = event.values.getOrElse(1) { 0f }
                az = event.values.getOrElse(2) { 9.81f }
                accelNs = event.timestamp
            }
            else -> return
        }
        val tNs = if (gyroNs > 0L) gyroNs else event.timestamp
        val sample = ImuSample(tNs = tNs, gx = gx, gy = gy, gz = gz, ax = ax, ay = ay, az = az)
        latest.set(sample)
        onSample?.invoke(sample)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()

    companion object {
        private const val TAG = "GlassNav"
    }
}
