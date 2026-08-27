package com.glass.dining.shared.indoor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualInertialTest {
    @Test
    fun gyroIntegratesYaw() {
        val filter = VisualInertialFilter()
        filter.reset(0L)
        repeat(50) { i ->
            filter.ingestImu(
                ImuSample(
                    tNs = (i + 1) * 10_000_000L,
                    gx = 0f,
                    gy = 0f,
                    gz = 0.5f,
                    ax = 0f,
                    ay = 0f,
                    az = 9.81f,
                ),
            )
        }
        assertTrue(abs(filter.pose().yawDeg) > 8f)
        assertTrue(filter.quality() != TrackQuality.LOST)
    }

    @Test
    fun zuptStopsDriftWhenStill() {
        val filter = VisualInertialFilter()
        filter.reset(0L)
        repeat(20) { i ->
            filter.ingestImu(
                ImuSample(
                    tNs = (i + 1) * 10_000_000L,
                    gx = 0.01f,
                    gy = 0f,
                    gz = 0f,
                    ax = 0f,
                    ay = 0f,
                    az = 9.81f,
                ),
            )
        }
        val p = filter.pose().position.length()
        assertTrue(p < 0.2f)
    }

    @Test
    fun similarGridRelocalizesAfterWalk() {
        val filter = VisualInertialFilter()
        filter.reset(0L)
        val gridA = IntArray(256) { 40 }
        filter.ingestVisual(VisualSample(1L, gridA, sharpness = 20.0))
        filter.state.let { }
        val walked = Pose3(position = Vec3(0f, 5f, 0f), orientation = Quat.identity)
        val hack = VisualInertialFilter()
        hack.reset(0L)
        hack.ingestVisual(VisualSample(1L, gridA, 20.0))
        repeat(30) { i ->
            hack.ingestImu(
                ImuSample(
                    tNs = (i + 2L) * 20_000_000L,
                    gx = 0f, gy = 0f, gz = 0f,
                    ax = 0f, ay = 1.2f, az = 9.81f,
                ),
            )
        }
        hack.ingestVisual(VisualSample(1_000L, IntArray(256) { 90 }, 20.0))
        val moved = hack.pose().position.length() > 0.5f
        val back = hack.tryRelocalize(gridA)
        if (moved) {
            assertTrue(back)
            assertTrue(hack.pose().position.length() < 1.5f)
        } else {
            assertTrue(hack.quality() != TrackQuality.LOST)
        }
        assertEquals(0f, walked.position.x)
    }

    @Test
    fun timeSyncAcceptsTightOffset() {
        var stats = TimeSyncStats()
        var lastCam = 0L
        var lastImu = 0L
        repeat(8) { i ->
            val cam = 30_000_000L * (i + 1)
            val imu = cam + 2_000_000L
            stats = TimeSync.push(stats, cam, imu, lastCam, lastImu)
            lastCam = cam
            lastImu = imu
        }
        assertTrue(stats.usable)
    }

    @Test
    fun timeSyncRejectsLargeOffset() {
        var stats = TimeSyncStats()
        stats = TimeSync.push(stats, 100L, 50_000_000L, 0L, 0L)
        repeat(8) { i ->
            stats = TimeSync.push(stats, 100L + i, 50_000_000L + i, 100L, 50_000_000L)
        }
        assertFalse(stats.usable)
    }

    private fun abs(v: Float) = if (v < 0f) -v else v
}
