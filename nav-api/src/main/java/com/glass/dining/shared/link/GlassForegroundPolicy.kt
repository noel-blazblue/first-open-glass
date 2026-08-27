package com.glass.dining.shared.link

/**
 * 眼镜 CustomApp 是否在前台。打开/离开由 CXR 回调喂入；要不要 appStart 是纯判定。
 * 入网会短暂 pause，离开后有宽限期，宽限内不重拉。
 */
class GlassForegroundPolicy(
    private val graceMs: Long = GRACE_MS,
    private val startDebounceMs: Long = START_DEBOUNCE_MS,
) {
    var opened: Boolean = false
        private set
    var ready: Boolean = false
        private set
    var inGrace: Boolean = false
        private set

    private var leftAtMs: Long = 0L
    private var lastStartAtMs: Long = 0L

    fun markOpened() {
        opened = true
        inGrace = false
        leftAtMs = 0L
    }

    fun markReady() {
        opened = true
        ready = true
        inGrace = false
        leftAtMs = 0L
    }

    fun markLeaving(nowMs: Long) {
        opened = false
        ready = false
        inGrace = true
        leftAtMs = nowMs
    }

    fun expireGrace(nowMs: Long): Boolean {
        if (!inGrace) return false
        if (nowMs - leftAtMs < graceMs) return false
        inGrace = false
        return true
    }

    fun noteStart(nowMs: Long) {
        lastStartAtMs = nowMs
    }

    fun shouldStart(
        phoneWantsGlass: Boolean,
        cxrReady: Boolean,
        closeGlass: Boolean,
        nowMs: Long,
    ): Boolean {
        expireGrace(nowMs)
        if (!phoneWantsGlass || !cxrReady || closeGlass) return false
        if (opened || inGrace) return false
        if (lastStartAtMs > 0L && nowMs - lastStartAtMs < startDebounceMs) return false
        return true
    }

    fun reset() {
        opened = false
        ready = false
        inGrace = false
        leftAtMs = 0L
        lastStartAtMs = 0L
    }

    companion object {
        const val GRACE_MS = 2_500L
        const val START_DEBOUNCE_MS = 2_000L
    }
}
