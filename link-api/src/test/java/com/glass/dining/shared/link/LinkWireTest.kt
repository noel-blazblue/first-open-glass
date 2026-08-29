package com.glass.dining.shared.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkWireTest {
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
        val json = NavWire.hintJson(
            NavHint(
                storeName = "望京药店",
                text = "沿走廊走",
                mode = "ground",
                sessionId = "nav-1",
                tracking = IndoorProtocol.TRACK_LOST,
                waypoints = "0,2.2,-1.55",
            ),
        )
        val hint = NavWire.parseHint(json)
        assertEquals("nav-1", hint.sessionId)
        assertEquals(IndoorProtocol.TRACK_LOST, hint.tracking)
        assertFalse(hint.visual)
        assertTrue(hint.waypoints.contains("2.2"))
    }

    @Test
    fun poseJsonRoundTrip() {
        val json = PoseWire.poseJson(
            GlassPose(
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
        val pose = PoseWire.parsePose(json)!!
        assertEquals(12.5f, pose.yaw, 0.01f)
        assertEquals(-3f, pose.pitch, 0.01f)
        assertEquals(0.8f, pose.x, 0.01f)
        assertEquals(2.2f, pose.y, 0.01f)
        assertEquals(-1.55f, pose.z, 0.01f)
        assertEquals(IndoorProtocol.TRACK_GOOD, pose.tracking)
        assertEquals(42L, pose.tNs)
    }

    @Test
    fun hudTalkPoseRoundTrip() {
        val json = HudWire.cardJson(
            title = "",
            layout = HudWire.LAYOUT_TALK,
            speech = "思考中",
            pose = "think",
        )
        val lines = HudWire.parseCard(json)
        assertEquals("think", lines.pose)
        assertEquals("思考中", lines.speech)
        assertTrue(lines.isTalk)
    }

    @Test
    fun downChannelIsGenericCmd() {
        assertEquals("rk_cmd", GlassLink.CHANNEL_DOWN)
        assertEquals("rk_evt", GlassLink.CHANNEL_UP)
        assertEquals("hud.draw", GlassLink.CMD_DRAW)
    }
}
