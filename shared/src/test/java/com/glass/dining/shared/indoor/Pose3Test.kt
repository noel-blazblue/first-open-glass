package com.glass.dining.shared.indoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Pose3Test {
    @Test
    fun yawRotatesForwardToRight() {
        val q = Quat.fromYawPitchRoll(90f, 0f, 0f)
        val v = q.rotate(Vec3(0f, 1f, 0f))
        assertTrue(v.x > 0.8f)
        assertTrue(abs(v.y) < 0.2f)
    }

    @Test
    fun yawDegRoundTripsWithFromYawPitchRoll() {
        val q = Quat.fromYawPitchRoll(90f, 0f, 0f)
        assertTrue(abs(q.yawDeg() - 90f) < 2f)
    }

    @Test
    fun wrapKeepsDeltaSmall() {
        assertEquals(-20f, Angle.deltaDeg(170f, 150f), 0.1f)
        assertTrue(abs(Angle.wrapDeg(190f) + 170f) < 1f)
    }

    @Test
    fun transformRoundTrip() {
        val pose = Pose3(position = Vec3(1f, 2f, 0f), orientation = Quat.fromYawPitchRoll(30f, 0f, 0f))
        val local = Vec3(0f, 3f, 0f)
        val world = pose.transformPoint(local)
        val back = pose.inverseTransform(world)
        assertTrue((back - local).length() < 0.05f)
    }
}
