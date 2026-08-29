package com.glass.dining.shared.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pJoinPolicyTest {
    @Test
    fun sameAttemptDoesNotCreateGroupTwice() {
        assertFalse(P2pJoinPolicy.mayCreateGroup(sameAttempt = true, alreadyOffered = true, creating = false))
        assertFalse(P2pJoinPolicy.mayCreateGroup(sameAttempt = true, alreadyOffered = false, creating = true))
        assertTrue(P2pJoinPolicy.mayCreateGroup(sameAttempt = true, alreadyOffered = false, creating = false))
        assertTrue(P2pJoinPolicy.mayCreateGroup(sameAttempt = false, alreadyOffered = true, creating = false))
    }

    @Test
    fun sameOfferDoesNotReconnect() {
        assertTrue(P2pJoinPolicy.keepCurrentOffer("DIRECT-AB", joined = false, nextSsid = "DIRECT-AB"))
        assertTrue(P2pJoinPolicy.ignoreOffer(joined = true, currentSsid = "DIRECT-AB", nextSsid = "DIRECT-AB"))
        assertFalse(P2pJoinPolicy.mayConnect(active = true, joined = false, connecting = true))
        assertFalse(P2pJoinPolicy.mayConnect(active = true, joined = true, connecting = false))
        assertFalse(P2pJoinPolicy.mayDiscover(active = true, joined = true))
        assertTrue(P2pJoinPolicy.mayDiscover(active = true, joined = false))
    }

    @Test
    fun noRealIpIsNotReady() {
        assertNull(P2pJoinPolicy.readyIp(groupFormed = true, isGroupOwner = false, ipv4 = ""))
        assertNull(P2pJoinPolicy.readyIp(groupFormed = true, isGroupOwner = false, ipv4 = "10.0.0.2"))
        assertNull(P2pJoinPolicy.readyIp(groupFormed = false, isGroupOwner = false, ipv4 = "192.168.49.2"))
        assertNull(P2pJoinPolicy.readyIp(groupFormed = true, isGroupOwner = true, ipv4 = "192.168.49.1"))
        assertEquals("192.168.49.18", P2pJoinPolicy.readyIp(true, false, "192.168.49.18"))
    }

    @Test
    fun onlyDirectIceIsAccepted() {
        assertTrue(P2pJoinPolicy.acceptIce("candidate:1 1 UDP 2130706431 192.168.49.226 9 typ host"))
        assertFalse(P2pJoinPolicy.acceptIce("candidate:2 1 UDP 2130706431 192.168.0.101 9 typ host"))
        assertFalse(P2pJoinPolicy.acceptIce("candidate:3 1 UDP 1686052607 2408:8256::1 9 typ host"))
        assertFalse(P2pJoinPolicy.acceptIce(""))
    }
}
