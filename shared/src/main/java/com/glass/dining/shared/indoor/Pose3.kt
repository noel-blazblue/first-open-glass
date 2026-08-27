package com.glass.dining.shared.indoor

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 局部切空间：+X 右、+Y 前、+Z 上。yaw=0 朝 +Y，正 yaw 朝右（+X）。
 */
data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val n = length()
        return if (n < 1e-6f) this else this * (1f / n)
    }
    fun horizontal(): Vec3 = Vec3(x, y, 0f)
}

data class Quat(
    val w: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
) {
    fun rotate(v: Vec3): Vec3 {
        val qv = Vec3(x, y, z)
        val uv = qv.cross(v)
        val uuv = qv.cross(uv)
        return v + (uv * w + uuv) * 2f
    }

    fun times(other: Quat): Quat {
        return Quat(
            w = w * other.w - x * other.x - y * other.y - z * other.z,
            x = w * other.x + x * other.w + y * other.z - z * other.y,
            y = w * other.y - x * other.z + y * other.w + z * other.x,
            z = w * other.z + x * other.y - y * other.x + z * other.w,
        )
    }

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-8f) return identity
        val s = 1f / n
        return Quat(w * s, x * s, y * s, z * s)
    }

    fun yawDeg(): Float {
        val siny = 2f * (w * z + x * y)
        val cosy = 1f - 2f * (y * y + z * z)
        return -Math.toDegrees(atan2(siny.toDouble(), cosy.toDouble())).toFloat()
    }

    fun pitchDeg(): Float {
        val sinp = (2f * (w * x - z * y)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.asin(sinp.toDouble())).toFloat()
    }

    fun rollDeg(): Float {
        val sinr = 2f * (w * y + z * x)
        val cosr = 1f - 2f * (x * x + y * y)
        return Math.toDegrees(atan2(sinr.toDouble(), cosr.toDouble())).toFloat()
    }

    fun inverse(): Quat = Quat(w, -x, -y, -z).normalized()

    companion object {
        val identity = Quat()

        fun fromYawPitchRoll(yawDeg: Float, pitchDeg: Float, rollDeg: Float): Quat {
            val y = Math.toRadians((-yawDeg).toDouble() / 2.0)
            val p = Math.toRadians(pitchDeg.toDouble() / 2.0)
            val r = Math.toRadians(rollDeg.toDouble() / 2.0)
            val cy = cos(y).toFloat()
            val sy = sin(y).toFloat()
            val cp = cos(p).toFloat()
            val sp = sin(p).toFloat()
            val cr = cos(r).toFloat()
            val sr = sin(r).toFloat()
            return Quat(
                w = cr * cp * cy + sr * sp * sy,
                x = sr * cp * cy - cr * sp * sy,
                y = cr * sp * cy + sr * cp * sy,
                z = cr * cp * sy - sr * sp * cy,
            ).normalized()
        }

        fun fromGyro(wx: Float, wy: Float, wz: Float, dt: Float): Quat {
            val half = dt * 0.5f
            return Quat(1f, wx * half, wy * half, wz * half).normalized()
        }
    }
}

private fun Vec3.cross(other: Vec3): Vec3 {
    return Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )
}

data class Pose3(
    val tNs: Long = 0L,
    val position: Vec3 = Vec3(),
    val orientation: Quat = Quat.identity,
) {
    val yawDeg: Float get() = orientation.yawDeg()
    val pitchDeg: Float get() = orientation.pitchDeg()
    val rollDeg: Float get() = orientation.rollDeg()

    fun transformPoint(local: Vec3): Vec3 = position + orientation.rotate(local)

    fun inverseTransform(world: Vec3): Vec3 = orientation.inverse().rotate(world - position)

    fun distanceTo(other: Pose3): Float = (position - other.position).length()
}

enum class TrackQuality {
    GOOD,
    WEAK,
    LOST,
}

data class ImuSample(
    val tNs: Long,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val ax: Float,
    val ay: Float,
    val az: Float,
)

data class VisualSample(
    val tNs: Long,
    val grid: IntArray,
    val sharpness: Double = 0.0,
)

object Angle {
    fun wrapDeg(deg: Float): Float {
        var v = deg
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }

    fun deltaDeg(from: Float, to: Float): Float = wrapDeg(to - from)
}
