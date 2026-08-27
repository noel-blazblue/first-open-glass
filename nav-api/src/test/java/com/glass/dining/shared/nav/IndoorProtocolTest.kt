package com.glass.dining.shared.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndoorProtocolTest {
    @Test
    fun waypointsRoundTrip() {
        val raw = IndoorProtocol.encodeWaypoints(
            listOf(floatArrayOf(0.2f, 2.2f, -1.55f), floatArrayOf(0.4f, 3.6f, -1.55f)),
        )
        val parsed = IndoorProtocol.parseWaypoints(raw)
        assertEquals(2, parsed.size)
        assertEquals(2.2f, parsed[0][1], 0.01f)
        assertEquals(-1.55f, parsed[1][2], 0.01f)
    }

    @Test
    fun hintJsonKeepsSessionAndTracking() {
        val json = NavProtocol.hintJson(
            NavHint(
                storeName = "海底捞",
                text = "沿走廊走",
                mode = "ground",
                sessionId = "nav-1",
                tracking = IndoorProtocol.TRACK_LOST,
                waypoints = "0,2.2,-1.55",
            ),
        )
        val hint = NavProtocol.parseHint(json)
        assertEquals("nav-1", hint.sessionId)
        assertEquals(IndoorProtocol.TRACK_LOST, hint.tracking)
        assertFalse(hint.visual)
        assertTrue(hint.waypoints.contains("2.2"))
    }

    @Test
    fun poseJsonRoundTrip() {
        val json = NavProtocol.poseJson(
            NavPose(
                yaw = 12.5f,
                pitch = -3f,
                roll = 0.4f,
                x = 0.8f,
                y = 2.2f,
                z = -1.55f,
                tracking = IndoorProtocol.TRACK_GOOD,
                tNs = 42L,
            ),
        )
        val pose = NavProtocol.parsePose(json)!!
        assertEquals(12.5f, pose.yaw, 0.01f)
        assertEquals(-3f, pose.pitch, 0.01f)
        assertEquals(0.8f, pose.x, 0.01f)
        assertEquals(2.2f, pose.y, 0.01f)
        assertEquals(-1.55f, pose.z, 0.01f)
        assertEquals(IndoorProtocol.TRACK_GOOD, pose.tracking)
        assertEquals(42L, pose.tNs)
    }
}
