package com.glass.nav.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

class ImuTracker(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val matrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var yawZero = 0f
    private val yawMilli = AtomicInteger(0)

    val yawDegrees: Float
        get() = yawMilli.get() / 1000f

    val sensorName: String
        get() = rotation?.name ?: "none"

    fun start() {
        val sensor = rotation
        if (sensor == null) {
            Log.w(TAG, "no rotation/gyro sensor")
            return
        }
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        Log.i(TAG, "imu start name=${sensor.name} type=${sensor.type}")
    }

    fun stop() {
        manager.unregisterListener(this)
    }

    fun calibrate() {
        yawZero = rawYaw()
        publish()
        Log.i(TAG, "imu calibrated zero=$yawZero")
    }

    fun sensorList(): String {
        return manager.getSensorList(Sensor.TYPE_ALL).joinToString(",") { it.name }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        SensorManager.getOrientation(matrix, orientation)
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun rawYaw(): Float {
        return Math.toDegrees(orientation[0].toDouble()).toFloat()
    }

    private fun publish() {
        var delta = rawYaw() - yawZero
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        yawMilli.set((delta * 1000f).toInt())
    }

    companion object {
        private const val TAG = "GlassNav"
    }
}
