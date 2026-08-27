package com.glass.dining.shared.link

import com.glass.dining.shared.nav.NavProtocol
import com.glass.dining.shared.nav.P2pOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionLinkMachineTest {
    @Test
    fun beginWithoutCxrFails() {
        val machine = VisionLinkMachine { 1_000 }
        val effects = machine.begin(cxrReady = false, glassReady = false)
        assertEquals(LinkPhase.Failed, machine.snapshot.phase)
        assertEquals("CXR 未连接", machine.snapshot.lastError)
        assertTrue(effects.any { it is LinkEffect.Cleanup })
    }

    @Test
    fun doesNotSendOfferBeforeGlassReady() {
        val machine = VisionLinkMachine { 1_000 }
        val started = machine.begin(cxrReady = true, glassReady = false)
        assertTrue(started.any { it is LinkEffect.StartGlass })
        assertFalse(started.any { it is LinkEffect.SendOffer })
        assertEquals(LinkPhase.GlassStarting, machine.snapshot.phase)
        val earlyGroup = machine.onGroupReady(sampleOffer(machine.snapshot.attemptId))
        assertTrue(earlyGroup.isEmpty())
        assertFalse(machine.snapshot.phase.name.contains("Peer"))
    }

    @Test
    fun glassReadyThenGroupThenOffer() {
        val machine = VisionLinkMachine { 2_000 }
        machine.begin(cxrReady = true, glassReady = false)
        val afterReady = machine.onGlassReady()
        assertTrue(afterReady.any { it is LinkEffect.KeepWifi })
        assertTrue(afterReady.any { it is LinkEffect.CreateGroup })
        assertFalse(afterReady.any { it is LinkEffect.SendOffer })
        val offer = sampleOffer(machine.snapshot.attemptId)
        val afterGroup = machine.onGroupReady(offer)
        assertEquals(1, afterGroup.filterIsInstance<LinkEffect.SendOffer>().size)
        assertEquals(LinkPhase.PeerJoining, machine.snapshot.phase)
    }

    @Test
    fun duplicateCallbacksAreIdempotent() {
        val machine = VisionLinkMachine { 3_000 }
        machine.begin(cxrReady = true, glassReady = true)
        val again = machine.onGlassReady()
        assertTrue(again.isEmpty())
        val offer = sampleOffer(machine.snapshot.attemptId)
        machine.onGroupReady(offer)
        val second = machine.onGroupReady(offer)
        assertTrue(second.isEmpty())
    }

    @Test
    fun oldAttemptIsIgnored() {
        val machine = VisionLinkMachine { 4_000 }
        machine.begin(cxrReady = true, glassReady = true)
        val current = machine.snapshot.attemptId
        machine.onGroupReady(sampleOffer(current))
        val ignored = machine.onPeerReady("192.168.49.2", "old-attempt")
        assertTrue(ignored.isEmpty())
        assertEquals(LinkPhase.PeerJoining, machine.snapshot.phase)
        val failIgnored = machine.onPeerFail("stale", "old-attempt")
        assertTrue(failIgnored.isEmpty())
        assertEquals(LinkPhase.PeerJoining, machine.snapshot.phase)
    }

    @Test
    fun fakeIpDoesNotBecomeReady() {
        val machine = VisionLinkMachine { 5_000 }
        machine.begin(cxrReady = true, glassReady = true)
        machine.onGroupReady(sampleOffer(machine.snapshot.attemptId))
        assertTrue(machine.onPeerReady("", machine.snapshot.attemptId).isEmpty())
        assertTrue(machine.onPeerReady("10.0.0.8", machine.snapshot.attemptId).isEmpty())
        assertEquals(LinkPhase.PeerJoining, machine.snapshot.phase)
    }

    @Test
    fun phaseTimeoutFails() {
        val machine = VisionLinkMachine { 6_000 }
        machine.begin(cxrReady = true, glassReady = false)
        val effects = machine.onTimeout()
        assertEquals(LinkPhase.Failed, machine.snapshot.phase)
        assertTrue(machine.snapshot.lastError.contains("GlassStarting"))
        assertEquals(LinkPhase.GlassStarting, machine.snapshot.failedPhase)
        assertTrue(effects.any { it is LinkEffect.Cleanup })
    }

    @Test
    fun stopCleansUpAndKeepsFailureReason() {
        val machine = VisionLinkMachine { 7_000 }
        machine.begin(cxrReady = true, glassReady = false)
        machine.onTimeout()
        val reason = machine.snapshot.lastError
        val effects = machine.stop()
        assertEquals(LinkPhase.Idle, machine.snapshot.phase)
        assertEquals(reason, machine.snapshot.lastError)
        assertEquals(LinkPhase.GlassStarting, machine.snapshot.failedPhase)
        assertEquals(1, effects.filterIsInstance<LinkEffect.Cleanup>().size)
        machine.begin(cxrReady = true, glassReady = true)
        machine.stop()
        assertEquals(LinkPhase.Idle, machine.snapshot.phase)
        assertEquals("", machine.snapshot.lastError)
    }

    @Test
    fun secondBeginWhileOpenIsNoOp() {
        val machine = VisionLinkMachine { 8_000 }
        machine.begin(cxrReady = true, glassReady = true)
        val attempt = machine.snapshot.attemptId
        val again = machine.begin(cxrReady = true, glassReady = true)
        assertTrue(again.isEmpty())
        assertEquals(attempt, machine.snapshot.attemptId)
    }

    @Test
    fun glassLostReturnsToStarting() {
        val machine = VisionLinkMachine { 9_000 }
        machine.begin(cxrReady = true, glassReady = true)
        machine.onGroupReady(sampleOffer(machine.snapshot.attemptId))
        assertEquals(LinkPhase.PeerJoining, machine.snapshot.phase)
        val effects = machine.onGlassLost()
        assertEquals(LinkPhase.GlassStarting, machine.snapshot.phase)
        assertTrue(effects.any { it is LinkEffect.Cleanup })
        assertTrue(effects.any { it is LinkEffect.StartGlass })
        assertTrue(effects.none { it is LinkEffect.SendOffer })
        val afterReady = machine.onGlassReady()
        assertTrue(afterReady.any { it is LinkEffect.CreateGroup })
        assertFalse(afterReady.any { it is LinkEffect.SendOffer })
    }

    @Test
    fun glassLostWhileIdleIsNoOp() {
        val machine = VisionLinkMachine { 10_000 }
        assertTrue(machine.onGlassLost().isEmpty())
        assertEquals(LinkPhase.Idle, machine.snapshot.phase)
    }

    @Test
    fun glassLostWhileStartingStillStarts() {
        val machine = VisionLinkMachine { 11_000 }
        machine.begin(cxrReady = true, glassReady = false)
        val effects = machine.onGlassLost()
        assertEquals(LinkPhase.GlassStarting, machine.snapshot.phase)
        assertTrue(effects.any { it is LinkEffect.StartGlass })
        assertTrue(effects.any { it is LinkEffect.Cleanup })
    }

    @Test
    fun protocolKeepsAttemptId() {
        val offer = P2pOffer("DIRECT-AB", "passpass", "192.168.49.1", "aa:bb", "phone", "a9-1")
        val parsed = NavProtocol.parseP2pOffer(NavProtocol.p2pOfferJson(offer))
        assertEquals("a9-1", parsed?.attemptId)
        val ready = NavProtocol.p2pReadyJson("192.168.49.2", "a9-1")
        assertEquals("192.168.49.2", NavProtocol.parseP2pReady(ready))
        assertEquals("a9-1", NavProtocol.parseP2pAttemptId(ready))
        val fail = NavProtocol.parseP2pFail(NavProtocol.p2pFailJson("timeout", "a9-1"))
        assertEquals("timeout", fail.first)
        assertEquals("a9-1", fail.second)
    }

    private fun sampleOffer(attemptId: String): P2pOffer {
        return P2pOffer("DIRECT-AB", "secret12", "192.168.49.1", attemptId = attemptId)
    }
}
