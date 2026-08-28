package com.glass.dining.phone

import com.glass.dining.shared.agent.AgentToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorePanelSessionTest {
    @Test
    fun askStoreDoesNotDismiss() {
        val env = Env(store = true)
        env.session.beginTurn()
        env.session.noteTool(AgentToolCatalog.ASK_STORE.name)
        env.session.maybeDismissOffTopic()
        assertEquals(0, env.dismissed)
        assertTrue(env.matchBound)
    }

    @Test
    fun chatDismissesHudNotBind() {
        val env = Env(store = true)
        env.session.beginTurn()
        env.session.maybeDismissOffTopic()
        assertEquals(1, env.dismissed)
        assertTrue(env.matchBound)
    }

    @Test
    fun idleTimeoutDismissesHudNotBind() {
        val env = Env(store = true)
        env.session.noteStoreShown()
        env.busy = false
        env.session.armIfIdle()
        assertEquals(0, env.dismissed)
        env.scheduled!!.run()
        assertEquals(1, env.dismissed)
        assertTrue(env.matchBound)
    }

    @Test
    fun speechPausesIdleTimer() {
        val env = Env(store = true)
        env.session.noteStoreShown()
        env.busy = false
        env.session.armIfIdle()
        val pending = env.scheduled
        env.session.pause()
        pending?.run()
        assertEquals(0, env.dismissed)
    }

    @Test
    fun searchNearbyWithoutStoreDoesNotKeep() {
        val env = Env(store = true)
        env.session.beginTurn()
        env.session.noteTool(AgentToolCatalog.SEARCH_NEARBY.name)
        assertFalse(env.session.touchedThisTurn)
        env.session.maybeDismissOffTopic()
        assertEquals(1, env.dismissed)
    }

    private class Env(store: Boolean) {
        var store = store
        var busy = false
        var navigating = false
        var dismissed = 0
        var matchBound = true
        var scheduled: Runnable? = null
        val session = StorePanelSession(
            postDelayed = { r, _ -> scheduled = r },
            cancelDelayed = { if (scheduled === it) scheduled = null },
            storeShowing = { this.store },
            navigating = { navigating },
            busy = { busy },
            onDismiss = {
                dismissed += 1
                this.store = false
            },
        )
    }
}
