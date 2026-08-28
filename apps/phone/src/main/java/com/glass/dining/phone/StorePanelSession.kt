package com.glass.dining.phone

import com.glass.dining.shared.hud.StoreHudPolicy

/**
 * 门店详情在屏时的退出：换话题关画面，7 秒无互动关画面。
 * 不调用会话 unbind / clearLook。
 */
class StorePanelSession(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val cancelDelayed: (Runnable) -> Unit,
    private val idleMs: Long = StoreHudPolicy.IDLE_MS,
    private val storeShowing: () -> Boolean,
    private val navigating: () -> Boolean,
    private val busy: () -> Boolean,
    private val onDismiss: () -> Unit,
    private val onIdleFace: () -> Unit = {},
) {
    @Volatile var touchedThisTurn: Boolean = false
        private set

    private var armed: Boolean = false
    private val idle = Runnable {
        if (!armed) return@Runnable
        armed = false
        if (!StoreHudPolicy.shouldDismissIdle(storeShowing(), navigating(), busy())) return@Runnable
        onDismiss()
        onIdleFace()
    }

    fun beginTurn() {
        touchedThisTurn = false
        pause()
    }

    fun noteTool(name: String) {
        if (StoreHudPolicy.keepsStorePanel(name)) touchedThisTurn = true
    }

    fun noteStoreShown() {
        touchedThisTurn = true
        pause()
    }

    fun maybeDismissOffTopic() {
        if (!StoreHudPolicy.shouldDismissOffTopic(storeShowing(), navigating(), touchedThisTurn)) return
        cancel()
        onDismiss()
    }

    fun pause() {
        armed = false
        cancelDelayed(idle)
    }

    fun armIfIdle() {
        pause()
        if (!StoreHudPolicy.shouldDismissIdle(storeShowing(), navigating(), busy())) return
        armed = true
        postDelayed(idle, idleMs)
    }

    fun cancel() {
        armed = false
        cancelDelayed(idle)
    }
}
