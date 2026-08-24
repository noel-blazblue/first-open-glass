package com.glass.dining.phone

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glass.dining.shared.hud.HudCard
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IAudioStreamCbk
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.rokid.cxr.link.callbacks.IImageStreamCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import org.json.JSONArray
import org.json.JSONObject

object CxrLinkHost {
    const val TAG = "GlassDiningPhone"

    var sharedLink: CXRLink? = null
        private set

    @Volatile var cxrConnected: Boolean = false
        private set
    @Volatile var glassBtConnected: Boolean = false
        private set
    @Volatile var hudOpened: Boolean = false
        private set
    @Volatile var capturingAudio: Boolean = false
        private set

    var onStatus: ((String) -> Unit)? = null
    var onAudioPcm: ((ByteArray) -> Unit)? = null

    private const val AUDIO_CODEC_PCM = 1
    @Volatile private var wantAudio: Boolean = false
    @Volatile private var audioPackets: Int = 0

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var connecting: Boolean = false
    @Volatile private var lastCard: HudCard = HudCard.idle()
    @Volatile private var photoWaiter: ((ByteArray?, String?) -> Unit)? = null

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
        }

        override fun onGlassAiAssistStart() {
            Log.i(TAG, "onGlassAiAssistStart")
        }

        override fun onGlassAiAssistStop() {
            Log.i(TAG, "onGlassAiAssistStop")
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

    private val imageCbk = object : IImageStreamCbk {
        override fun onImageReceived(image: ByteArray?) {
            main.removeCallbacks(photoTimeout)
            val waiter = photoWaiter
            photoWaiter = null
            if (image == null || image.isEmpty()) {
                Log.w(TAG, "onImageReceived empty")
                main.post { waiter?.invoke(null, "眼镜回了空图") }
                return
            }
            Log.i(TAG, "onImageReceived bytes=${image.size}")
            main.post { waiter?.invoke(image, null) }
        }

        override fun onImageError(code: Int, msg: String?) {
            main.removeCallbacks(photoTimeout)
            val waiter = photoWaiter
            photoWaiter = null
            Log.w(TAG, "onImageError code=$code msg=$msg")
            main.post { waiter?.invoke(null, "眼镜拍照失败: $code ${msg.orEmpty()}") }
        }
    }

    private val viewCbk = object : ICustomViewCbk {
        override fun onCustomViewOpened() {
            hudOpened = true
            Log.i(TAG, "onCustomViewOpened")
            onStatus?.invoke("镜片 HUD 已打开")
            showCard(lastCard)
            if (wantAudio) {
                startGlassMic()
            }
        }

        override fun onCustomViewUpdated() {
            Log.i(TAG, "onCustomViewUpdated")
        }

        override fun onCustomViewClosed() {
            hudOpened = false
            Log.i(TAG, "onCustomViewClosed")
        }

        override fun onCustomViewIconsSent() {
            Log.i(TAG, "onCustomViewIconsSent")
        }

        override fun onCustomViewError(code: Int, msg: String?) {
            hudOpened = false
            Log.w(TAG, "onCustomViewError code=$code msg=$msg")
            onStatus?.invoke("HUD 失败: $code ${msg.orEmpty()}")
        }
    }

    fun connect(app: Application, token: String): String {
        if (token.isBlank()) return "token 为空，无法连接"
        if (cxrConnected && glassBtConnected && sharedLink != null) {
            return "CXR 已连接"
        }
        if (connecting && sharedLink != null) {
            return "正在连接眼镜…"
        }
        connecting = true
        hudOpened = false
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
            val configured = configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMVIEW))
            if (!configured) {
                Log.w(TAG, "configCXRSession returned false")
            }
            setCXRLinkCbk(linkCbk)
            setCXRCustomViewCbk(viewCbk)
            setCXRImageCbk(imageCbk)
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
        Log.i(TAG, "CXRLink.connect started=$started")
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
        audioPackets = 0
        val link = sharedLink ?: return "CXR 未连接"
        if (!cxrConnected || !glassBtConnected) {
            return "眼镜未连上，无法收声"
        }
        if (!hudOpened) {
            return "镜片 HUD 还没打开，打开后会自动收声"
        }
        if (!CxrAuth.hasMicrophone()) {
            return "乐奇未授权眼镜麦克风，请在弹出页勾选麦克风"
        }
        if (capturingAudio) {
            return "眼镜麦已在收声"
        }
        val ok = try {
            link.startAudioStream(AUDIO_CODEC_PCM)
        } catch (error: Exception) {
            Log.w(TAG, "startAudioStream failed", error)
            false
        }
        Log.i(TAG, "startAudioStream codec=$AUDIO_CODEC_PCM ok=$ok")
        return if (ok) {
            capturingAudio = true
            "已打开眼镜麦克风"
        } else {
            "startAudioStream 未发出，请确认乐奇已授权麦克风"
        }
    }

    fun stopGlassMic() {
        wantAudio = false
        capturingAudio = false
        val link = sharedLink ?: return
        val ok = try {
            link.stopAudioStream()
        } catch (error: Exception) {
            Log.w(TAG, "stopAudioStream failed", error)
            false
        }
        Log.i(TAG, "stopAudioStream ok=$ok")
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

    fun takePhoto(onResult: (ByteArray?, String?) -> Unit) {
        main.post {
            val link = sharedLink
            if (link == null || !cxrConnected || !glassBtConnected) {
                onResult(null, "CXR 未连接")
                return@post
            }
            photoWaiter = onResult
            main.removeCallbacks(photoTimeout)
            main.postDelayed(photoTimeout, 12_000)
            val ok = try {
                link.takePhoto(1280, 720, 80)
            } catch (error: Exception) {
                Log.w(TAG, "takePhoto failed", error)
                false
            }
            Log.i(TAG, "takePhoto issued=$ok")
            if (!ok) {
                main.removeCallbacks(photoTimeout)
                photoWaiter = null
                onResult(null, "takePhoto 未发出，请确认乐奇已授权相机")
            }
        }
    }

    fun showCard(card: HudCard) {
        lastCard = card.clipped()
        main.post {
            val link = sharedLink ?: return@post
            if (!cxrConnected || !glassBtConnected) {
                Log.i(TAG, "showCard skipped, CXR not ready title=${lastCard.title}")
                return@post
            }
            if (!hudOpened) {
                openHudIfReady()
                return@post
            }
            val ok = try {
                link.customViewUpdate(updateJson(lastCard))
            } catch (error: Exception) {
                Log.w(TAG, "customViewUpdate failed", error)
                false
            }
            Log.i(TAG, "customViewUpdate ok=$ok title=${lastCard.title} wait=${lastCard.wait} extra=${lastCard.extra}")
            if (!ok) {
                hudOpened = false
                openHudIfReady()
            }
        }
    }

    private fun publishLink() {
        val status = when {
            cxrConnected && glassBtConnected -> {
                connecting = false
                main.post { openHudIfReady() }
                "CXR 与眼镜蓝牙已就绪"
            }
            cxrConnected -> "CXR 已连，等待眼镜蓝牙"
            glassBtConnected -> "眼镜蓝牙已连，等待 CXR"
            else -> "CXR 未连接"
        }
        Log.i(TAG, status)
        onStatus?.invoke(status)
    }

    private fun openHudIfReady() {
        val link = sharedLink ?: return
        if (!cxrConnected || !glassBtConnected || hudOpened) return
        if (link.customViewIsOpen()) return
        val ok = try {
            link.customViewOpen(viewJson(lastCard))
        } catch (error: Exception) {
            Log.w(TAG, "customViewOpen failed", error)
            onStatus?.invoke("打开 HUD 异常: ${error.message}")
            return
        }
        Log.i(TAG, "customViewOpen ok=$ok")
        if (!ok) {
            onStatus?.invoke("眼镜已连接，打开 HUD 失败")
        }
    }

    private fun viewJson(card: HudCard): String {
        val clipped = card.clipped()
        return JSONObject()
            .put("type", "LinearLayout")
            .put(
                "props",
                JSONObject()
                    .put("id", "root")
                    .put("layout_width", "match_parent")
                    .put("layout_height", "match_parent")
                    .put("orientation", "vertical")
                    .put("gravity", "center")
                    .put("backgroundColor", "#FF000000"),
            )
            .put(
                "children",
                JSONArray()
                    .put(textView("title", clipped.title, "20sp", "bold"))
                    .put(textView("meta", clipped.meta, "16sp", "normal"))
                    .put(textView("wait", clipped.wait, "16sp", "normal"))
                    .put(textView("extra", clipped.extra, "16sp", "normal")),
            )
            .toString()
    }

    private fun updateJson(card: HudCard): String {
        val clipped = card.clipped()
        return JSONArray()
            .put(updateItem("title", clipped.title))
            .put(updateItem("meta", clipped.meta))
            .put(updateItem("wait", clipped.wait))
            .put(updateItem("extra", clipped.extra))
            .toString()
    }

    private fun textView(id: String, text: String, size: String, style: String): JSONObject {
        return JSONObject()
            .put("type", "TextView")
            .put(
                "props",
                JSONObject()
                    .put("id", id)
                    .put("layout_width", "wrap_content")
                    .put("layout_height", "wrap_content")
                    .put("text", text)
                    .put("textColor", "#00FF00")
                    .put("textSize", size)
                    .put("gravity", "center")
                    .put("textStyle", style),
            )
    }

    private fun updateItem(id: String, text: String): JSONObject {
        return JSONObject()
            .put("action", "update")
            .put("id", id)
            .put("props", JSONObject().put("text", text))
    }
}
