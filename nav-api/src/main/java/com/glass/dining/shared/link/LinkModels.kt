package com.glass.dining.shared.link

import com.glass.dining.shared.nav.P2pOffer

enum class LinkPhase {
    Idle,
    CxrReady,
    GlassStarting,
    GlassReady,
    WifiEnabling,
    GroupCreating,
    PeerJoining,
    P2pReady,
    RtcStarting,
    Streaming,
    Stopping,
    Failed,
}

data class LinkSnapshot(
    val phase: LinkPhase = LinkPhase.Idle,
    val attemptId: String = "",
    val startedAtMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val lastError: String = "",
    val failedPhase: LinkPhase? = null,
    val cxr: String = "未连接",
    val glassApp: String = "未启动",
    val mic: String = "关",
    val glassWifi: String = "未知",
    val direct: String = "未建组",
    val rtc: String = "未开",
) {
    val open: Boolean
        get() = phase != LinkPhase.Idle &&
            phase != LinkPhase.Failed &&
            phase != LinkPhase.Stopping
}

sealed class LinkEffect {
    data object None : LinkEffect()
    data class StartGlass(val attemptId: String) : LinkEffect()
    data class KeepWifi(val attemptId: String) : LinkEffect()
    data class CreateGroup(val attemptId: String) : LinkEffect()
    data class SendOffer(val offer: P2pOffer) : LinkEffect()
    data class StartRtc(val attemptId: String) : LinkEffect()
    data class StartOffer(val attemptId: String) : LinkEffect()
    data class Cleanup(val attemptId: String) : LinkEffect()
}
