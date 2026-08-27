package com.glass.dining.shared.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassForegroundPolicyTest {
    @Test
    fun openedPageDoesNotStart() {
        val policy = GlassForegroundPolicy()
        policy.markOpened()
        assertFalse(policy.shouldStart(true, true, false, 1_000))
    }

    @Test
    fun readyImpliesOpened() {
        val policy = GlassForegroundPolicy()
        policy.markReady()
        assertTrue(policy.opened)
        assertTrue(policy.ready)
        assertFalse(policy.shouldStart(true, true, false, 1_000))
    }

    @Test
    fun leavingClearsReadyImmediately() {
        val policy = GlassForegroundPolicy()
        policy.markReady()
        policy.markLeaving(1_000)
        assertFalse(policy.opened)
        assertFalse(policy.ready)
        assertTrue(policy.inGrace)
        assertFalse(policy.shouldStart(true, true, false, 1_000))
        assertFalse(policy.shouldStart(true, true, false, 1_000 + 2_499))
    }

    @Test
    fun afterGraceShouldStart() {
        val policy = GlassForegroundPolicy()
        policy.markReady()
        policy.markLeaving(1_000)
        assertTrue(policy.expireGrace(1_000 + GlassForegroundPolicy.GRACE_MS))
        assertFalse(policy.inGrace)
        assertTrue(policy.shouldStart(true, true, false, 1_000 + GlassForegroundPolicy.GRACE_MS))
    }

    @Test
    fun resumeDuringGraceCancelsStart() {
        val policy = GlassForegroundPolicy()
        policy.markReady()
        policy.markLeaving(1_000)
        policy.markOpened()
        assertTrue(policy.opened)
        assertFalse(policy.ready)
        assertFalse(policy.inGrace)
        assertFalse(policy.expireGrace(1_000 + GlassForegroundPolicy.GRACE_MS))
        assertFalse(policy.shouldStart(true, true, false, 10_000))
    }

    @Test
    fun debounceAfterNoteStart() {
        val policy = GlassForegroundPolicy()
        val now = 5_000L
        policy.noteStart(now)
        assertFalse(policy.shouldStart(true, true, false, now + 1_999))
        assertTrue(policy.shouldStart(true, true, false, now + GlassForegroundPolicy.START_DEBOUNCE_MS))
    }

    @Test
    fun missingWantOrCxrOrCloseSkips() {
        val policy = GlassForegroundPolicy()
        assertFalse(policy.shouldStart(false, true, false, 1_000))
        assertFalse(policy.shouldStart(true, false, false, 1_000))
        assertFalse(policy.shouldStart(true, true, true, 1_000))
        assertTrue(policy.shouldStart(true, true, false, 1_000))
    }
}
