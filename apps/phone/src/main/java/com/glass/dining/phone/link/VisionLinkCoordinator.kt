package com.glass.dining.phone.link

import android.app.Application
import android.os.Handler
import android.util.Log
import com.glass.dining.phone.CxrLinkHost
import com.glass.dining.phone.PhoneP2p
import com.glass.dining.phone.PhoneRtc
import com.glass.dining.shared.link.LinkEffect
import com.glass.dining.shared.link.LinkPhase
import com.glass.dining.shared.link.LinkSnapshot
import com.glass.dining.shared.link.P2pJoinPolicy
import com.glass.dining.shared.link.VisionLinkMachine
import com.glass.dining.shared.link.GlassLink
import com.glass.dining.shared.link.MediaWire

data class LinkUiState(
    val phase: String = LinkPhase.Idle.name,
    val attemptId: String = "",
    val elapsedMs: Long = 0,
    val lastError: String = "",
    val failedPhase: String = "",
    val cxr: String = "未连接",
    val glassApp: String = "未启动",
    val mic: String = "关",
    val glassWifi: String = "未知",
    val direct: String = "未建组",
    val rtc: String = "未开",
) {
    val open: Boolean
        get() = phase != LinkPhase.Idle.name &&
            phase != LinkPhase.Failed.name &&
            phase != LinkPhase.Stopping.name

    val summary: String
        get() {
            if (lastError.isNotBlank() && (phase == LinkPhase.Failed.name || phase == LinkPhase.Idle.name)) {
                return lastError
            }
            return when (phase) {
                LinkPhase.Idle.name -> "连接空闲"
                LinkPhase.Streaming.name -> "画面已通"
                else -> "$phase · ${direct.ifBlank { rtc }}"
            }
        }
}

class VisionLinkCoordinator(
    private val app: Application,
    private val main: Handler,
    private val onSnapshot: (LinkUiState) -> Unit,
    private val onStreaming: () -> Unit = {},
    private val onFailed: (String) -> Unit = {},
) {
    private val machine = VisionLinkMachine()
    private var loggedPhase: LinkPhase = LinkPhase.Idle
    private var resendCount: Int = 0

    private val timeout = Runnable {
        apply(machine.onTimeout())
    }

    private val resend = object : Runnable {
        override fun run() {
            if (machine.snapshot.phase != LinkPhase.PeerJoining) return
            resendCount += 1
            if (resendCount > MAX_RESEND) return
            apply(machine.resendOffer())
            main.postDelayed(this, RESEND_MS)
        }
    }

    val snapshot: LinkSnapshot
        get() = machine.snapshot

    val lastError: String
        get() = machine.snapshot.lastError

    val open: Boolean
        get() = machine.snapshot.open

    fun begin() {
        apply(machine.begin(CxrLinkHost.linkReady(), CxrLinkHost.glassReady))
    }

    fun stop() {
        apply(machine.stop())
    }

    fun onGlassReady() {
        apply(machine.onGlassReady())
    }

    fun onGlassLost() {
        apply(machine.onGlassLost())
    }

    fun onRtc(cmd: String, json: String?) {
        when (cmd) {
            GlassLink.CMD_P2P_READY -> {
                val ip = MediaWire.parseP2pReady(json)
                val attempt = MediaWire.parseP2pAttemptId(json)
                if (!P2pJoinPolicy.acceptAttempt(machine.snapshot.attemptId, attempt)) return
                val ready = P2pJoinPolicy.readyIp(true, false, ip)
                if (ready == null) {
                    apply(machine.failNow("眼镜 Direct 没有 192.168.49.x 地址"))
                } else {
                    apply(machine.onPeerReady(ready, attempt))
                }
            }
            GlassLink.CMD_P2P_FAIL -> {
                val parsed = MediaWire.parseP2pFail(json)
                apply(machine.onPeerFail(parsed.first, parsed.second))
            }
            GlassLink.CMD_RTC_READY -> apply(machine.onRtcReady())
            GlassLink.CMD_RTC_SDP -> PhoneRtc.onRemoteSdp(json)
            GlassLink.CMD_RTC_ICE -> PhoneRtc.onRemoteIce(json)
            GlassLink.CMD_RTC_STAT -> apply(machine.onStat(json.orEmpty()))
        }
    }

    fun onRtcStatus(line: String) {
        if (PhoneRtc.hasFreshFrame()) {
            apply(machine.onFirstFrame())
            return
        }
        if (line.contains("ICE 失败")) {
            apply(machine.failNow(line))
        } else {
            apply(machine.onStat(line))
        }
    }

    fun refreshLayers() {
        machine.updateLayers(
            cxr = cxrLine(),
            glassApp = if (CxrLinkHost.glassReady) "已就绪" else machine.snapshot.glassApp,
            mic = micLine(),
        )
        publish()
    }

    private fun apply(effects: List<LinkEffect>) {
        val before = machine.snapshot.phase
        effects.forEach { dispatch(it) }
        val after = machine.snapshot.phase
        if (after != loggedPhase) {
            Log.i(TAG, "link ${loggedPhase.name} -> ${after.name} attempt=${machine.snapshot.attemptId}")
            loggedPhase = after
        }
        armTimeout(before, after)
        if (after == LinkPhase.PeerJoining && before != LinkPhase.PeerJoining) {
            resendCount = 0
            main.removeCallbacks(resend)
            main.postDelayed(resend, RESEND_MS)
        }
        if (after != LinkPhase.PeerJoining) {
            main.removeCallbacks(resend)
        }
        if (after == LinkPhase.Streaming && before != LinkPhase.Streaming) {
            onStreaming()
        }
        if (after == LinkPhase.Failed && before != LinkPhase.Failed) {
            onFailed(machine.snapshot.lastError)
        }
        publish()
    }

    private fun dispatch(effect: LinkEffect) {
        when (effect) {
            LinkEffect.None -> Unit
            is LinkEffect.StartGlass -> {
                Log.i(TAG, "phase GlassStarting attempt=${effect.attemptId}")
                CxrLinkHost.ensureGlassApp("link")
            }
            is LinkEffect.KeepWifi -> CxrLinkHost.sendRtc(GlassLink.CMD_WIFI_KEEP)
            is LinkEffect.CreateGroup -> startGroup(effect.attemptId)
            is LinkEffect.SendOffer -> {
                Log.i(TAG, "send p2p.offer attempt=${effect.offer.attemptId} ssid=${effect.offer.ssid}")
                CxrLinkHost.sendRtc(GlassLink.CMD_P2P_OFFER, MediaWire.p2pOfferJson(effect.offer))
            }
            is LinkEffect.StartRtc -> CxrLinkHost.sendRtc(GlassLink.CMD_RTC_START)
            is LinkEffect.StartOffer -> PhoneRtc.startOffer()
            is LinkEffect.Cleanup -> cleanup()
        }
    }

    private fun startGroup(attemptId: String) {
        Thread({
            PhoneRtc.prepare(app)
            main.post {
                PhoneP2p.start(app, attemptId) { offer ->
                    if (offer == null) {
                        apply(machine.failNow(PhoneP2p.lastError.ifBlank { "手机没建起 Direct 组" }))
                    } else {
                        apply(machine.onGroupReady(offer.copy(attemptId = attemptId)))
                    }
                }
            }
        }, "rtc-prepare").start()
    }

    private fun cleanup() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(resend)
        PhoneRtc.stop()
        PhoneP2p.stop()
        CxrLinkHost.sendRtc(GlassLink.CMD_RTC_STOP)
        CxrLinkHost.sendRtc(GlassLink.CMD_P2P_STOP)
    }

    private fun armTimeout(before: LinkPhase, after: LinkPhase) {
        if (before == after && after != LinkPhase.Idle) return
        main.removeCallbacks(timeout)
        val wait = machine.timeoutMs(after)
        if (wait > 0 && machine.snapshot.open) {
            main.postDelayed(timeout, wait)
        }
    }

    private fun publish() {
        machine.tick()
        val snap = machine.snapshot
        onSnapshot(
            LinkUiState(
                phase = snap.phase.name,
                attemptId = snap.attemptId,
                elapsedMs = snap.elapsedMs,
                lastError = snap.lastError,
                failedPhase = snap.failedPhase?.name.orEmpty(),
                cxr = cxrLine(),
                glassApp = snap.glassApp,
                mic = micLine(),
                glassWifi = snap.glassWifi,
                direct = snap.direct,
                rtc = snap.rtc,
            ),
        )
    }

    private fun cxrLine(): String {
        return when {
            CxrLinkHost.linkReady() -> "已就绪"
            CxrLinkHost.cxrConnected -> "已连，等待眼镜蓝牙"
            CxrLinkHost.glassBtConnected -> "蓝牙已连，等待 CXR"
            else -> "未连接"
        }
    }

    private fun micLine(): String {
        return when {
            CxrLinkHost.capturingAudio -> "收声中 n=${CxrLinkHost.pcmPackets}"
            CxrLinkHost.hudOpened -> "已开页，未收声"
            else -> "关"
        }
    }

    companion object {
        private const val TAG = "GlassDiningPhone"
        private const val MAX_RESEND = 3
        private const val RESEND_MS = 8_000L
    }
}
