package com.glass.dining.shared.hud

import com.glass.dining.shared.agent.AgentToolCatalog

/** 门店详情何时留下、何时只关画面。不扫用户原话。 */
object StoreHudPolicy {
    const val IDLE_MS = 7_000L

    private val KEEP = setOf(
        AgentToolCatalog.ASK_STORE.name,
        AgentToolCatalog.SELECT_STORE.name,
        AgentToolCatalog.LOOK_STORE.name,
        AgentToolCatalog.RECOMMEND.name,
        AgentToolCatalog.START_NAV.name,
        AgentToolCatalog.STOP_NAV.name,
        AgentToolCatalog.READ_MENU.name,
        AgentToolCatalog.LIST_COUPONS.name,
        AgentToolCatalog.SCAN_COUPON.name,
        AgentToolCatalog.REDEEM.name,
        AgentToolCatalog.CHECKOUT.name,
    )

    fun keepsStorePanel(toolName: String): Boolean {
        val name = AgentToolCatalog.ALIASES[toolName] ?: toolName
        return name in KEEP
    }

    fun shouldDismissOffTopic(
        storeShowing: Boolean,
        navigating: Boolean,
        storeTouchedThisTurn: Boolean,
    ): Boolean {
        return storeShowing && !navigating && !storeTouchedThisTurn
    }

    fun shouldDismissIdle(
        storeShowing: Boolean,
        navigating: Boolean,
        busy: Boolean,
    ): Boolean {
        return storeShowing && !navigating && !busy
    }
}
