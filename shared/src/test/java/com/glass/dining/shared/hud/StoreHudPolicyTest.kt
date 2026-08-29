package com.glass.dining.shared.hud

import com.glass.dining.shared.agent.AgentToolCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreHudPolicyTest {
    @Test
    fun askStoreKeepsPanel() {
        assertTrue(StoreHudPolicy.keepsStorePanel(AgentToolCatalog.GET_PLACE_DETAILS.name))
        assertTrue(StoreHudPolicy.keepsStorePanel("ask_store"))
        assertTrue(StoreHudPolicy.keepsStorePanel(AgentToolCatalog.SELECT_STORE.name))
        assertTrue(StoreHudPolicy.keepsStorePanel(AgentToolCatalog.LOOK_AT_SCENE.name))
        assertTrue(StoreHudPolicy.keepsStorePanel("look_store"))
        assertTrue(StoreHudPolicy.keepsStorePanel(AgentToolCatalog.RECOMMEND.name))
        assertTrue(StoreHudPolicy.keepsStorePanel("start_nav"))
        assertFalse(StoreHudPolicy.shouldDismissOffTopic(true, false, true))
    }

    @Test
    fun chatAndUnrelatedToolsDismiss() {
        assertFalse(StoreHudPolicy.keepsStorePanel(AgentToolCatalog.SEARCH_NEARBY.name))
        assertFalse(StoreHudPolicy.keepsStorePanel(AgentToolCatalog.RESOLVE_DEST.name))
        assertFalse(StoreHudPolicy.keepsStorePanel(AgentToolCatalog.DRAW_HUD.name))
        assertFalse(StoreHudPolicy.keepsStorePanel(AgentToolCatalog.CLOSE_HUD.name))
        assertTrue(StoreHudPolicy.shouldDismissOffTopic(true, false, false))
    }

    @Test
    fun idleClearsHudOnlyWhenQuiet() {
        assertTrue(StoreHudPolicy.shouldDismissIdle(true, false, false))
        assertFalse(StoreHudPolicy.shouldDismissIdle(true, false, true))
        assertFalse(StoreHudPolicy.shouldDismissIdle(true, true, false))
        assertFalse(StoreHudPolicy.shouldDismissIdle(false, false, false))
    }

    @Test
    fun navAndOffTopicDoNotDismissToTalk() {
        assertFalse(StoreHudPolicy.shouldDismissOffTopic(true, true, false))
        assertFalse(StoreHudPolicy.shouldDismissOffTopic(false, false, false))
    }
}
