package com.glass.nav.glass

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glass.dining.shared.nav.HudLines
import com.glass.dining.shared.nav.NavHint
import com.glass.dining.shared.nav.NavProtocol
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

    class NavBridge(
    private val onCard: (HudLines) -> Unit,
    private val onHint: (NavHint) -> Unit,
    private val onCamera: (Boolean) -> Unit,
    private val onSnap: () -> Unit,
    private val onMic: (Boolean) -> Unit,
    private val onCalibrate: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFrameAck: () -> Unit,
    private val onRtc: (String, String?) -> Unit,
    private val onPhoneGone: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val bridge = CXRServiceBridge()
    @Volatile private var sendingFrame = false

    private val statusListener = object : CXRServiceBridge.StatusListener {
        override fun onConnected(name: String?, extra: String?, type: Int) {
            Log.i(TAG, "cxr-s connected name=$name extra=$extra type=$type")
            main.post { onStatus("手机已连接") }
            sendReady()
        }

        override fun onDisconnected() {
            Log.i(TAG, "cxr-s disconnected")
            main.post {
                onStatus("手机断开")
                onPhoneGone()
            }
        }

        override fun onConnecting(name: String?, extra: String?, type: Int) {
            Log.i(TAG, "cxr-s connecting name=$name")
        }

        override fun onARTCStatus(health: Float, reset: Boolean) = Unit

        override fun onRokidAccountChanged(account: String?) = Unit

        override fun onAudioNoise(noise: Float) = Unit
    }

    private val navCallback = object : CXRServiceBridge.MsgCallback {
        override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
            val cmd = readString(args, 0) ?: return
            Log.i(TAG, "recv $name cmd=$cmd")
            when (cmd) {
                NavProtocol.CMD_HELLO -> sendReady()
                NavProtocol.CMD_HUD -> {
                    val card = NavProtocol.parseCard(readString(args, 1))
                    main.post { onCard(card) }
                }
                NavProtocol.CMD_CAMERA_ON -> main.post { onCamera(true) }
                NavProtocol.CMD_NAV_START -> {
                    val hint = NavProtocol.parseHint(readString(args, 1))
                    main.post {
                        if (hint.text.isNotBlank() || hint.storeName.isNotBlank()) {
                            onHint(hint)
                        }
                    }
                }
                NavProtocol.CMD_NAV_UPDATE -> {
                    val hint = NavProtocol.parseHint(readString(args, 1))
                    main.post { onHint(hint) }
                }
                NavProtocol.CMD_CAMERA_OFF, NavProtocol.CMD_NAV_STOP -> {
                    main.post { onCamera(false) }
                }
                NavProtocol.CMD_CAMERA_SNAP -> main.post { onSnap() }
                NavProtocol.CMD_MIC_ON -> main.post { onMic(true) }
                NavProtocol.CMD_MIC_OFF -> main.post { onMic(false) }
                NavProtocol.CMD_CALIBRATE -> main.post { onCalibrate() }
                NavProtocol.CMD_FRAME_OK -> {
                    sendingFrame = false
                    main.post { onFrameAck() }
                }
                NavProtocol.CMD_RTC_START,
                NavProtocol.CMD_RTC_STOP,
                NavProtocol.CMD_RTC_SDP,
                NavProtocol.CMD_RTC_ICE,
                NavProtocol.CMD_P2P_OFFER,
                NavProtocol.CMD_P2P_STOP,
                NavProtocol.CMD_WIFI_KEEP,
                -> main.post { onRtc(cmd, readString(args, 1)) }
            }
        }
    }

    fun start() {
        bridge.setStatusListener(statusListener)
        val code = bridge.subscribe(NavProtocol.CHANNEL_DOWN, navCallback)
        Log.i(TAG, "subscribe ${NavProtocol.CHANNEL_DOWN} code=$code")
        sendReady()
    }

    fun sendReady() {
        val caps = Caps()
        caps.write(NavProtocol.CMD_READY)
        val result = bridge.sendMessage(NavProtocol.CHANNEL_UP, caps)
        Log.i(TAG, "send ready result=$result")
    }

    fun sendRtc(cmd: String, json: String? = null) {
        val caps = Caps()
        caps.write(cmd)
        if (!json.isNullOrBlank()) {
            caps.write(json)
        }
        val result = bridge.sendMessage(NavProtocol.CHANNEL_UP, caps)
        if (cmd != NavProtocol.CMD_RTC_ICE) {
            Log.i(TAG, "send rtc $cmd code=$result bytes=${json?.length ?: 0}")
        }
    }

    fun sendError(message: String) {
        val caps = Caps()
        caps.write(NavProtocol.CMD_ERROR)
        caps.write(message)
        bridge.sendMessage(NavProtocol.CHANNEL_UP, caps)
    }

    fun sendPose(pose: com.glass.dining.shared.nav.NavPose) {
        val caps = Caps()
        caps.write(NavProtocol.CMD_POSE)
        caps.write(NavProtocol.poseJson(pose))
        bridge.sendMessage(NavProtocol.CHANNEL_UP, caps)
    }

    fun sendPcm(pcm: ByteArray): Boolean {
        val caps = Caps()
        caps.write(NavProtocol.CMD_PCM)
        caps.write(pcm)
        val result = bridge.sendMessage(NavProtocol.CHANNEL_UP, caps)
        if (result != 0) {
            Log.w(TAG, "send pcm failed code=$result bytes=${pcm.size}")
            return false
        }
        return true
    }

    fun trySendFrame(jpeg: ByteArray): Boolean {
        if (sendingFrame) return false
        sendingFrame = true
        val caps = Caps()
        caps.write(NavProtocol.CMD_FRAME)
        caps.write(jpeg)
        val result = bridge.sendMessage(NavProtocol.CHANNEL_UP, caps)
        if (result != 0) {
            sendingFrame = false
            Log.w(TAG, "send frame failed code=$result bytes=${jpeg.size}")
            return false
        }
        Log.i(TAG, "send frame bytes=${jpeg.size}")
        return true
    }

    fun markFrameIdle() {
        sendingFrame = false
    }

    private fun readString(args: Caps?, index: Int): String? {
        if (args == null || args.size() <= index) return null
        return try {
            args.at(index).getString()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "GlassNav"
    }
}
