package com.glass.dining.shared.indoor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseSendGateTest {
    @Test
    fun wifiSendsFirstThenRespectsMinInterval() {
        val origin = pose(0f, 0f, 0f)
        val (first, afterFirst) = PoseSendGate.wifi(PoseSendCursor(), 1_000L, origin, lost = false)
        assertTrue(first)
        val (tooSoon, _) = PoseSendGate.wifi(afterFirst, 1_150L, pose(0.4f, 0f, 20f), lost = false)
        assertFalse(tooSoon)
        val (turned, _) = PoseSendGate.wifi(afterFirst, 1_200L, pose(0f, 0f, 8f), lost = false)
        assertTrue(turned)
    }

    @Test
    fun wifiHeartbeatsWhenStill() {
        val origin = pose(0f, 0f, 0f)
        val (_, sent) = PoseSendGate.wifi(PoseSendCursor(), 0L, origin, lost = false)
        val (early, _) = PoseSendGate.wifi(sent, 400L, origin, lost = false)
        assertFalse(early)
        val (beat, _) = PoseSendGate.wifi(sent, 500L, origin, lost = false)
        assertTrue(beat)
    }

    @Test
    fun cxrSilentWhenMicOnOrNotNavigating() {
        val origin = pose(0f, 0f, 0f)
        val (mic, _) = PoseSendGate.cxr(PoseSendCursor(), 0L, origin, false, micOn = true, navOn = true)
        assertFalse(mic)
        val (idle, _) = PoseSendGate.cxr(PoseSendCursor(), 0L, origin, false, micOn = false, navOn = false)
        assertFalse(idle)
        val (ok, sent) = PoseSendGate.cxr(PoseSendCursor(), 0L, origin, false, micOn = false, navOn = true)
        assertTrue(ok)
        val (still, _) = PoseSendGate.cxr(sent, 600L, origin, false, micOn = false, navOn = true)
        assertFalse(still)
        val (moved, _) = PoseSendGate.cxr(sent, 600L, pose(0.9f, 0f, 0f), false, micOn = false, navOn = true)
        assertTrue(moved)
    }

    private fun pose(x: Float, y: Float, yaw: Float): Pose3 {
        return Pose3(position = Vec3(x, y, 0f), orientation = Quat.fromYawPitchRoll(yaw, 0f, 0f))
    }
}
