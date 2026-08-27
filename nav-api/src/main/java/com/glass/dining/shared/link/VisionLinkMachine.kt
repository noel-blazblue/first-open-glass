package com.glass.dining.shared.link

import com.glass.dining.shared.nav.P2pOffer
import java.util.concurrent.atomic.AtomicLong

class VisionLinkMachine(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    var snapshot: LinkSnapshot = LinkSnapshot()
        private set

    private var lastOffer: P2pOffer? = null

    fun begin(cxrReady: Boolean, glassReady: Boolean): List<LinkEffect> {
        if (snapshot.open) return emptyList()
        if (!cxrReady) {
            return fail("CXR 未连接", LinkPhase.Idle)
        }
        val attempt = nextAttemptId()
        val t = nowMs()
        snapshot = snapshot.copy(
            attemptId = attempt,
            startedAtMs = t,
            elapsedMs = 0,
            lastError = "",
            failedPhase = null,
            cxr = "已就绪",
            phase = LinkPhase.CxrReady,
            glassApp = if (glassReady) "已就绪" else "未启动",
            glassWifi = "未知",
            direct = "未建组",
            rtc = "未开",
        )
        lastOffer = null
        return if (glassReady) enterGlassReady() else enterGlassStarting()
    }

    fun onGlassReady(): List<LinkEffect> {
        if (!snapshot.open) return emptyList()
        if (snapshot.phase != LinkPhase.GlassStarting && snapshot.phase != LinkPhase.CxrReady) {
            return emptyList()
        }
        return enterGlassReady()
    }

    fun onGroupReady(offer: P2pOffer): List<LinkEffect> {
        if (!snapshot.open) return emptyList()
        if (!P2pJoinPolicy.acceptAttempt(snapshot.attemptId, offer.attemptId)) return emptyList()
        if (snapshot.phase != LinkPhase.GroupCreating && snapshot.phase != LinkPhase.WifiEnabling) {
            return emptyList()
        }
        lastOffer = offer
        snapshot = snapshot.copy(
            phase = LinkPhase.PeerJoining,
            elapsedMs = elapsed(),
            direct = "已建组 ${offer.ssid}，等待眼镜加入",
        )
        return listOf(LinkEffect.SendOffer(offer))
    }

    fun resendOffer(): List<LinkEffect> {
        val offer = lastOffer ?: return emptyList()
        if (snapshot.phase != LinkPhase.PeerJoining) return emptyList()
        return listOf(LinkEffect.SendOffer(offer))
    }

    fun onPeerReady(ip: String, attemptId: String): List<LinkEffect> {
        if (!snapshot.open) return emptyList()
        if (!P2pJoinPolicy.acceptAttempt(snapshot.attemptId, attemptId)) return emptyList()
        val ready = P2pJoinPolicy.readyIp(groupFormed = true, isGroupOwner = false, ipv4 = ip)
            ?: return emptyList()
        if (snapshot.phase != LinkPhase.PeerJoining) return emptyList()
        snapshot = snapshot.copy(
            phase = LinkPhase.P2pReady,
            elapsedMs = elapsed(),
            direct = "已加入 $ready",
        )
        return enterRtcStarting()
    }

    fun onPeerFail(reason: String, attemptId: String): List<LinkEffect> {
        if (!snapshot.open) return emptyList()
        if (!P2pJoinPolicy.acceptAttempt(snapshot.attemptId, attemptId)) return emptyList()
        return fail(reason.ifBlank { "眼镜加入 Direct 失败" }, snapshot.phase)
    }

    fun onRtcReady(): List<LinkEffect> {
        if (snapshot.phase != LinkPhase.RtcStarting) return emptyList()
        snapshot = snapshot.copy(elapsedMs = elapsed(), rtc = "信令就绪，正在发 offer")
        return listOf(LinkEffect.StartOffer(snapshot.attemptId))
    }

    fun onFirstFrame(): List<LinkEffect> {
        if (snapshot.phase != LinkPhase.RtcStarting && snapshot.phase != LinkPhase.P2pReady) {
            return emptyList()
        }
        snapshot = snapshot.copy(
            phase = LinkPhase.Streaming,
            elapsedMs = elapsed(),
            rtc = "首帧已到",
        )
        return emptyList()
    }

    fun onStat(line: String): List<LinkEffect> {
        if (!snapshot.open || line.isBlank()) return emptyList()
        val wifi = when {
            line.contains("Wi-Fi 已开") -> "已开"
            line.contains("Wi-Fi 正在打开") -> "正在打开"
            line.contains("Wi-Fi 关") -> "关着，App 正在打开"
            else -> snapshot.glassWifi
        }
        val direct = if (line.contains("Direct") || line.contains("加入")) line.take(40) else snapshot.direct
        snapshot = snapshot.copy(elapsedMs = elapsed(), glassWifi = wifi, direct = direct)
        return emptyList()
    }

    fun updateLayers(
        cxr: String? = null,
        glassApp: String? = null,
        mic: String? = null,
        glassWifi: String? = null,
    ): LinkSnapshot {
        snapshot = snapshot.copy(
            elapsedMs = elapsed(),
            cxr = cxr ?: snapshot.cxr,
            glassApp = glassApp ?: snapshot.glassApp,
            mic = mic ?: snapshot.mic,
            glassWifi = glassWifi ?: snapshot.glassWifi,
        )
        return snapshot
    }

    fun onTimeout(): List<LinkEffect> {
        if (!snapshot.open) return emptyList()
        val phase = snapshot.phase
        val ms = timeoutMs(phase)
        if (ms <= 0) return emptyList()
        return fail("${phase.name} 超时 ${ms / 1000}s", phase)
    }

    fun failNow(reason: String): List<LinkEffect> {
        if (!snapshot.open) return emptyList()
        return fail(reason, snapshot.phase)
    }

    fun stop(): List<LinkEffect> {
        if (snapshot.phase == LinkPhase.Idle) return emptyList()
        val attempt = snapshot.attemptId
        val keepError = snapshot.lastError
        val keepFailed = snapshot.failedPhase
        snapshot = snapshot.copy(
            phase = LinkPhase.Stopping,
            elapsedMs = elapsed(),
            rtc = "正在停止",
        )
        snapshot = snapshot.copy(
            phase = LinkPhase.Idle,
            elapsedMs = elapsed(),
            lastError = keepError,
            failedPhase = keepFailed,
            direct = if (keepError.isBlank()) "已拆除" else snapshot.direct,
            rtc = if (keepError.isBlank()) "已关" else snapshot.rtc,
            attemptId = "",
        )
        lastOffer = null
        return listOf(LinkEffect.Cleanup(attempt))
    }

    fun timeoutMs(phase: LinkPhase = snapshot.phase): Long {
        return when (phase) {
            LinkPhase.GlassStarting -> 12_000
            LinkPhase.WifiEnabling -> 8_000
            LinkPhase.GroupCreating -> 20_000
            LinkPhase.PeerJoining -> 30_000
            LinkPhase.RtcStarting -> 12_000
            else -> 0
        }
    }

    fun tick(): LinkSnapshot {
        snapshot = snapshot.copy(elapsedMs = elapsed())
        return snapshot
    }

    private fun enterGlassStarting(): List<LinkEffect> {
        snapshot = snapshot.copy(
            phase = LinkPhase.GlassStarting,
            elapsedMs = elapsed(),
            glassApp = "正在启动",
        )
        return listOf(LinkEffect.StartGlass(snapshot.attemptId))
    }

    private fun enterGlassReady(): List<LinkEffect> {
        snapshot = snapshot.copy(
            phase = LinkPhase.GlassReady,
            elapsedMs = elapsed(),
            glassApp = "已就绪",
        )
        return enterWifiThenGroup()
    }

    private fun enterWifiThenGroup(): List<LinkEffect> {
        val attempt = snapshot.attemptId
        snapshot = snapshot.copy(
            phase = LinkPhase.WifiEnabling,
            elapsedMs = elapsed(),
            glassWifi = "正在打开",
        )
        snapshot = snapshot.copy(
            phase = LinkPhase.GroupCreating,
            elapsedMs = elapsed(),
            direct = "正在建组",
        )
        return listOf(LinkEffect.KeepWifi(attempt), LinkEffect.CreateGroup(attempt))
    }

    private fun enterRtcStarting(): List<LinkEffect> {
        snapshot = snapshot.copy(
            phase = LinkPhase.RtcStarting,
            elapsedMs = elapsed(),
            rtc = "正在开流",
        )
        return listOf(LinkEffect.StartRtc(snapshot.attemptId))
    }

    private fun fail(reason: String, phase: LinkPhase): List<LinkEffect> {
        val attempt = snapshot.attemptId
        snapshot = snapshot.copy(
            phase = LinkPhase.Failed,
            elapsedMs = elapsed(),
            lastError = reason,
            failedPhase = phase,
        )
        lastOffer = null
        return listOf(LinkEffect.Cleanup(attempt))
    }

    private fun elapsed(): Long {
        val start = snapshot.startedAtMs
        return if (start <= 0) 0 else (nowMs() - start).coerceAtLeast(0)
    }

    companion object {
        private val seq = AtomicLong(0)

        fun nextAttemptId(): String {
            return "a${nowCompact()}-${seq.incrementAndGet()}"
        }

        private fun nowCompact(): String = (System.currentTimeMillis() % 1_000_000).toString()
    }
}
