package com.glass.dining.phone

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.nav.NavHint
import com.glass.dining.shared.nav.NavProtocol
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IAudioStreamCbk
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import java.util.concurrent.atomic.AtomicLong

object CxrLinkHost {
    const val TAG = "GlassDiningPhone"

    var sharedLink: CXRLink? = null
        private set

    @Volatile var cxrConnected: Boolean = false
        private set
    @Volatile var glassBtConnected: Boolean = false
        private set
    val hudOpened: Boolean
        get() = glassApp.opened
    val glassReady: Boolean
        get() = glassApp.ready
    @Volatile var capturingAudio: Boolean = false
        private set
    @Volatile var closeGlassWhenReady: Boolean = false
    var phoneWantsGlass: Boolean
        get() = glassApp.phoneWantsGlass
        set(value) {
            glassApp.phoneWantsGlass = value
        }
    private var app: Application? = null

    var onStatus: ((String) -> Unit)? = null
    var onAudioPcm: ((ByteArray) -> Unit)? = null
    var onHudOpened: (() -> Unit)? = null
    var onGlassReady: (() -> Unit)? = null
    var onGlassLost: (() -> Unit)? = null
    var onFrame: ((ByteArray) -> Unit)? = null
    var onPose: ((com.glass.dining.shared.nav.NavPose) -> Unit)? = null
    var onRtc: ((String, String?) -> Unit)? = null
    val pcmPackets: Int
        get() = audioPackets

    private const val AUDIO_CODEC_PCM = 1
    @Volatile private var wantAudio: Boolean = false
    @Volatile private var audioPackets: Int = 0

    private val main = Handler(Looper.getMainLooper())
    private val glassApp = GlassAppSession(main)
    @Volatile private var connecting: Boolean = false
    @Volatile private var lastCard: HudCard = HudCard.idle()
    @Volatile private var photoWaiter: ((ByteArray?, String?) -> Unit)? = null
    private val hudSeq = AtomicLong(0)

    init {
        glassApp.onStatus = { onStatus?.invoke(it) }
        glassApp.onOpened = {
            showCard(lastCard)
            if (wantAudio) startGlassMic()
            onHudOpened?.invoke()
        }
        glassApp.onReady = { onGlassReady?.invoke() }
        glassApp.onLost = { onGlassLost?.invoke() }
    }

    private val photoTimeout = Runnable {
        val waiter = photoWaiter
        photoWaiter = null
        waiter?.invoke(null, "眼镜拍照超时")
    }

    private val linkCbk = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrConnected = connected
            Log.i(TAG, "onCXRLConnected=$connected")
            publishLink()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            glassBtConnected = connected
            Log.i(TAG, "onGlassBtConnected=$connected")
            publishLink()
        }

        override fun onGlassDeviceInfo(deviceInfo: GlassInfo) {
            Log.i(
                TAG,
                "glass info name=${deviceInfo.deviceName} battery=${deviceInfo.batteryLevel} ver=${deviceInfo.systemVersion}",
            )
        }

        override fun onGlassWearingStatus(wearing: Boolean) {
            Log.i(TAG, "onGlassWearingStatus=$wearing")
            glassApp.onWearing(wearing)
        }

        override fun onGlassAiAssistStart() {
            Log.i(TAG, "onGlassAiAssistStart")
        }

        override fun onGlassAiAssistStop() {
            Log.i(TAG, "onGlassAiAssistStop")
            glassApp.onAiAssistStop()
        }

        override fun onGlassAiInterrupt(interrupted: Boolean) {
            Log.i(TAG, "onGlassAiInterrupt=$interrupted")
        }
    }

    private val audioCbk = object : IAudioStreamCbk {
        override fun onAudioReceived(data: ByteArray?, offset: Int, length: Int) {
            if (!capturingAudio) return
            val pcm = slicePcm(data, offset, length) ?: return
            audioPackets += 1
            if (audioPackets <= 3 || audioPackets % 50 == 0) {
                Log.i(TAG, "onAudioReceived n=$audioPackets bytes=${pcm.size} offset=$offset length=$length")
            }
            onAudioPcm?.invoke(pcm)
        }

        override fun onAudioError(code: Int, msg: String?) {
            capturingAudio = false
            Log.w(TAG, "onAudioError code=$code msg=$msg")
            onStatus?.invoke("眼镜麦失败: $code ${msg.orEmpty()}")
        }

        override fun onAudioStreamStateChanged(started: Boolean) {
            capturingAudio = started
            Log.i(TAG, "onAudioStreamStateChanged=$started")
            if (started) {
                onStatus?.invoke("正在听眼镜麦克风")
            }
        }
    }

    private val cmdCbk = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (payload == null || payload.isEmpty()) return
            val caps = try {
                Caps.fromBytes(payload)
            } catch (error: Exception) {
                Log.w(TAG, "parse caps failed key=$key", error)
                return
            }
            val cmd = readString(caps, 0) ?: return
            Log.i(TAG, "up key=$key cmd=$cmd size=${caps.size()}")
            when (cmd) {
                NavProtocol.CMD_READY -> {
                    main.post { glassApp.onCmdReady() }
                }
                NavProtocol.CMD_PCM -> {
                    val pcm = readBinary(caps, 1)
                    if (pcm != null && pcm.isNotEmpty()) {
                        audioPackets += 1
                        capturingAudio = true
                        if (audioPackets <= 3 || audioPackets % 50 == 0) {
                            Log.i(TAG, "pcm n=$audioPackets bytes=${pcm.size}")
                        }
                        onAudioPcm?.invoke(pcm)
                    }
                }
                NavProtocol.CMD_FRAME -> {
                    val jpeg = readBinary(caps, 1)
                    val waiter = photoWaiter
                    if (waiter != null) {
                        photoWaiter = null
                        main.removeCallbacks(photoTimeout)
                        main.post { waiter.invoke(jpeg, if (jpeg == null || jpeg.isEmpty()) "眼镜回了空图" else null) }
                        sendCmd(NavProtocol.CMD_FRAME_OK)
                    } else if (jpeg != null && jpeg.isNotEmpty()) {
                        main.post { onFrame?.invoke(jpeg) }
                    } else {
                        sendCmd(NavProtocol.CMD_FRAME_OK)
                    }
                }
                NavProtocol.CMD_POSE -> {
                    val parsed = NavProtocol.parsePose(readString(caps, 1))
                        ?: readFloat(caps, 1)?.let { com.glass.dining.shared.nav.NavPose(yaw = it) }
                    if (parsed != null) main.post { onPose?.invoke(parsed) }
                }
                NavProtocol.CMD_ERROR -> {
                    val msg = readString(caps, 1).orEmpty()
                    main.post { onStatus?.invoke("眼镜: $msg") }
                }
                NavProtocol.CMD_RTC_READY,
                NavProtocol.CMD_RTC_SDP,
                NavProtocol.CMD_RTC_ICE,
                NavProtocol.CMD_RTC_STAT,
                NavProtocol.CMD_P2P_READY,
                NavProtocol.CMD_P2P_FAIL,
                -> {
                    val payload = readString(caps, 1)
                    main.post { onRtc?.invoke(cmd, payload) }
                }
            }
        }
    }

    fun connect(app: Application, token: String): String {
        if (token.isBlank()) return "token 为空，无法连接"
        this.app = app
        if (cxrConnected && glassBtConnected && sharedLink != null) {
            main.post { onLinkReady() }
            return "CXR 已连接"
        }
        if (connecting && sharedLink != null) {
            return "正在连接眼镜…"
        }
        connecting = true
        glassApp.reset()
        cxrConnected = false
        glassBtConnected = false
        capturingAudio = false
        try {
            sharedLink?.stopAudioStream()
        } catch (_: Exception) {
        }
        try {
            sharedLink?.disconnect()
        } catch (error: Exception) {
            Log.w(TAG, "disconnect previous link failed", error)
        }
        val link = CXRLink(app).apply {
            val configured = configCXRSession(
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    NavProtocol.GLASS_PACKAGE,
                ),
            )
            if (!configured) {
                Log.w(TAG, "configCXRSession CUSTOMAPP returned false")
            }
            setCXRLinkCbk(linkCbk)
            setCXRCustomCmdCbk(cmdCbk)
            setCXRAudioCbk(audioCbk)
        }
        sharedLink = link
        (app as? PhoneDiningApp)?.sharedLink = link
        val started = try {
            link.connect(token)
        } catch (error: Exception) {
            connecting = false
            Log.w(TAG, "CXRLink.connect failed", error)
            return "connect 异常: ${error.message}"
        }
        Log.i(TAG, "CXRLink.connect CUSTOMAPP started=$started pkg=${NavProtocol.GLASS_PACKAGE}")
        return if (started) {
            "正在通过乐奇连接眼镜…"
        } else {
            connecting = false
            "connect 请求未发出，请确认乐奇已连上眼镜"
        }
    }

    fun canTakePhoto(): Boolean {
        return sharedLink != null && cxrConnected && glassBtConnected
    }

    fun startGlassMic(): String {
        wantAudio = true
        if (!linkReady()) {
            return "眼镜未连上，无法收声"
        }
        if (!hudOpened) {
            return "镜片到餐页还没打开，打开后会自动收声"
        }
        if (!capturingAudio) {
            audioPackets = 0
        }
        sendCmd(NavProtocol.CMD_MIC_ON)
        Log.i(TAG, "mic.on sent packets=$audioPackets")
        return "已打开眼镜麦克风"
    }

    fun stopGlassMic() {
        wantAudio = false
        capturingAudio = false
        sendCmd(NavProtocol.CMD_MIC_OFF)
        try {
            sharedLink?.stopAudioStream()
        } catch (_: Exception) {
        }
        Log.i(TAG, "mic.off sent")
    }

    fun takePhoto(onResult: (ByteArray?, String?) -> Unit) {
        main.post {
            if (!linkReady()) {
                onResult(null, "CXR 未连接")
                return@post
            }
            if (!hudOpened) {
                onResult(null, "镜片到餐页还没打开")
                return@post
            }
            photoWaiter = onResult
            main.removeCallbacks(photoTimeout)
            main.postDelayed(photoTimeout, 12_000)
            sendCmd(NavProtocol.CMD_CAMERA_SNAP)
            Log.i(TAG, "camera.snap issued")
        }
    }

    fun showCard(card: HudCard) {
        val clipped = card.clipped()
        lastCard = clipped
        val seq = hudSeq.incrementAndGet()
        main.post {
            if (seq != hudSeq.get()) return@post
            if (!linkReady()) {
                Log.i(TAG, "showCard skipped, CXR not ready title=${clipped.title}")
                return@post
            }
            if (!hudOpened) {
                ensureGlassApp("hud")
                return@post
            }
            sendCmd(
                NavProtocol.CMD_HUD,
                NavProtocol.cardJson(
                    clipped.title,
                    clipped.meta,
                    clipped.wait,
                    clipped.extra,
                    clipped.skill,
                    clipped.layout,
                    clipped.speech,
                    clipped.turn,
                    clipped.meters,
                    clipped.remaining,
                    clipped.mode,
                    clipped.headingDeg,
                    clipped.elevationDeg,
                    clipped.stage,
                    clipped.sessionId,
                    clipped.tracking,
                    clipped.waypoints,
                    clipped.pose,
                ),
            )
            Log.i(TAG, "hud.card layout=${clipped.layout} skill=${clipped.skill} title=${clipped.title}")
        }
    }

    fun startNav(hint: NavHint) {
        sendCmd(NavProtocol.CMD_NAV_START, NavProtocol.hintJson(hint))
    }

    fun updateHint(hint: NavHint) {
        sendCmd(NavProtocol.CMD_NAV_UPDATE, NavProtocol.hintJson(hint))
    }

    fun stopNav() {
        sendCmd(NavProtocol.CMD_NAV_STOP)
    }

    fun ackFrame() {
        sendCmd(NavProtocol.CMD_FRAME_OK)
    }

    fun sendRtc(cmd: String, json: String? = null) {
        sendCmd(cmd, json)
    }

    private fun onLinkReady() {
        if (closeGlassWhenReady) {
            closeGlassWhenReady = false
            phoneWantsGlass = false
            stopGlassApp()
            return
        }
        ensureGlassApp("link")
    }

    fun ensureGlassApp(reason: String) {
        glassApp.ensure(reason)
    }

    fun stopGlassApp() {
        glassApp.stopGlassApp()
    }

    fun sendHelloAndWifi() {
        sendCmd(NavProtocol.CMD_HELLO)
        sendCmd(NavProtocol.CMD_WIFI_KEEP)
    }

    fun linkReady(): Boolean = cxrConnected && glassBtConnected && sharedLink != null

    private fun sendCmd(cmd: String, json: String? = null) {
        val link = sharedLink ?: return
        if (!linkReady()) {
            Log.i(TAG, "sendCmd skipped, link not ready cmd=$cmd")
            return
        }
        val caps = Caps()
        caps.write(cmd)
        if (!json.isNullOrBlank()) {
            caps.write(json)
        }
        val code = try {
            link.sendCustomCmd(NavProtocol.CHANNEL_DOWN, caps)
        } catch (error: Exception) {
            Log.w(TAG, "sendCustomCmd failed cmd=$cmd", error)
            null
        }
        Log.i(TAG, "sendCustomCmd cmd=$cmd code=$code bytes=${json?.length ?: 0}")
    }

    private fun slicePcm(data: ByteArray?, offset: Int, length: Int): ByteArray? {
        if (data == null || data.isEmpty()) return null
        val start = offset
        val end = offset + length
        return if (start in 0 until data.size && length > 0 && end in (start + 1)..data.size) {
            data.copyOfRange(start, end)
        } else {
            data.copyOf()
        }
    }

    private fun publishLink() {
        val status = when {
            cxrConnected && glassBtConnected -> {
                connecting = false
                if (closeGlassWhenReady) {
                    closeGlassWhenReady = false
                    phoneWantsGlass = false
                    main.post { stopGlassApp() }
                } else {
                    main.post { onLinkReady() }
                }
                "CXR 与眼镜蓝牙已就绪"
            }
            cxrConnected -> "CXR 已连，等待眼镜蓝牙"
            glassBtConnected -> "眼镜蓝牙已连，等待 CXR"
            else -> "CXR 未连接"
        }
        Log.i(TAG, status)
        onStatus?.invoke(status)
    }

    private fun readString(caps: Caps, index: Int): String? {
        if (caps.size() <= index) return null
        return try {
            caps.at(index).getString()
        } catch (_: Exception) {
            null
        }
    }

    private fun readFloat(caps: Caps, index: Int): Float? {
        if (caps.size() <= index) return null
        return try {
            caps.at(index).getFloat()
        } catch (_: Exception) {
            null
        }
    }

    private fun readBinary(caps: Caps, index: Int): ByteArray? {
        if (caps.size() <= index) return null
        return try {
            val bin = caps.at(index).getBinary() ?: return null
            val end = bin.offset + bin.length
            if (bin.data == null || bin.offset < 0 || end > bin.data.size) return null
            bin.data.copyOfRange(bin.offset, end)
        } catch (error: Exception) {
            Log.w(TAG, "read binary failed", error)
            null
        }
    }
}
