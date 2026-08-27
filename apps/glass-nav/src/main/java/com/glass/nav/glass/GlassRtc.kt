package com.glass.nav.glass

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.glass.dining.shared.nav.NavPose
import com.glass.dining.shared.nav.NavProtocol
import com.glass.nav.glass.spatial.CameraFrameHub
import com.glass.nav.glass.spatial.HubVideoCapturer
import com.glass.nav.glass.spatial.RtcPoseSender
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 眼镜侧 WebRTC 探针：Camera2 → H.264 → 对端。
 * 信令走 CXR Caps，媒体不走 CMD_FRAME。
 */
object GlassRtc {
    @Volatile var active: Boolean = false
        private set

    private val sdpParts = NavProtocol.SdpAssembler()
    private val pendingIce = CopyOnWriteArrayList<IceCandidate>()
    private val rtcThread = HandlerThread("glass-rtc").apply { start() }
    private val rtc = Handler(rtcThread.looper)

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: VideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var send: ((String, String?) -> Unit)? = null
    private var remoteSet: Boolean = false
    @Volatile private var initialized: Boolean = false
    private val poseSender = RtcPoseSender()

    val poseOpen: Boolean
        get() = poseSender.open

    fun start(context: Context, hub: CameraFrameHub, send: (String, String?) -> Unit) {
        this.send = send
        rtc.post { startOnRtc(context.applicationContext, hub) }
    }

    fun stop() {
        rtc.post { stopOnRtc() }
    }

    fun onRemoteSdp(raw: String?) {
        rtc.post {
            val assembled = sdpParts.push(raw) ?: return@post
            applyRemoteSdp(assembled.first, assembled.second)
        }
    }

    fun onRemoteIce(raw: String?) {
        rtc.post {
            val parsed = NavProtocol.parseRtcIce(raw) ?: return@post
            val ice = IceCandidate(parsed.first, parsed.second, parsed.third)
            val peer = pc
            if (peer != null && remoteSet) {
                peer.addIceCandidate(ice)
            } else {
                pendingIce.add(ice)
            }
        }
    }

    fun sendPose(pose: NavPose): Boolean {
        if (!poseSender.open) return false
        rtc.post { poseSender.send(pose) }
        return true
    }

    private fun startOnRtc(context: Context, hub: CameraFrameHub) {
        if (active) {
            emit(NavProtocol.CMD_RTC_READY, null)
            return
        }
        try {
            ensureFactory(context)
            val peerFactory = factory ?: throw IllegalStateException("PeerConnectionFactory 为空")
            val peer = peerFactory.createPeerConnection(rtcConfig(), observer)
                ?: throw IllegalStateException("createPeerConnection 失败")
            pc = peer
            startCamera(context, peerFactory, peer, hub)
            active = true
            emit(NavProtocol.CMD_RTC_READY, null)
            emit(NavProtocol.CMD_RTC_STAT, """{"ice":"new","side":"glass"}""")
            Log.i(TAG, "rtc started")
        } catch (error: Exception) {
            Log.w(TAG, "rtc start failed", error)
            emit(NavProtocol.CMD_ERROR, "视频流启动失败: ${error.message}")
            stopOnRtc()
        }
    }

    private fun stopOnRtc() {
        active = false
        remoteSet = false
        pendingIce.clear()
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }
        try {
            capturer?.dispose()
        } catch (_: Exception) {
        }
        capturer = null
        track?.dispose()
        track = null
        source?.dispose()
        source = null
        helper?.dispose()
        helper = null
        poseSender.release()
        try {
            pc?.close()
        } catch (_: Exception) {
        }
        pc = null
        Log.i(TAG, "rtc stopped")
    }

    private fun ensureFactory(context: Context) {
        if (initialized && factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val root = EglBase.create()
        egl = root
        val encoder = DefaultVideoEncoderFactory(root.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(root.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setOptions(p2pRtcOptions())
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        initialized = true
    }

    private fun startCamera(
        context: Context,
        peerFactory: PeerConnectionFactory,
        peer: PeerConnection,
        hub: CameraFrameHub,
    ) {
        val nextCapturer = HubVideoCapturer(hub)
        val texture = SurfaceTextureHelper.create("glass-rtc-tex", egl!!.eglBaseContext)
        val videoSource = peerFactory.createVideoSource(false)
        nextCapturer.initialize(texture, context, videoSource.capturerObserver)
        videoSource.adaptOutputFormat(NavProtocol.RTC_WIDTH, NavProtocol.RTC_HEIGHT, NavProtocol.RTC_FPS)
        nextCapturer.startCapture(NavProtocol.RTC_WIDTH, NavProtocol.RTC_HEIGHT, NavProtocol.RTC_FPS)
        val videoTrack = peerFactory.createVideoTrack("glass-v", videoSource)
        videoTrack.setEnabled(true)
        peer.addTrack(videoTrack, listOf("glass"))
        peer.senders.firstOrNull { it.track()?.kind() == "video" }?.let { sender ->
            val params = sender.parameters
            params.encodings.firstOrNull()?.let { encoding ->
                encoding.maxBitrateBps = NavProtocol.RTC_MAX_BITRATE
                encoding.minBitrateBps = NavProtocol.RTC_MIN_BITRATE
                encoding.maxFramerate = NavProtocol.RTC_FPS
                encoding.scaleResolutionDownBy = 1.0
            }
            sender.parameters = params
        }
        capturer = nextCapturer
        helper = texture
        source = videoSource
        track = videoTrack
        Log.i(TAG, "hub camera ${NavProtocol.RTC_WIDTH}x${NavProtocol.RTC_HEIGHT}@${NavProtocol.RTC_FPS}")
    }

    private fun applyRemoteSdp(type: String, sdp: String) {
        val peer = pc ?: return
        val kind = if (type.equals("offer", true)) {
            SessionDescription.Type.OFFER
        } else {
            SessionDescription.Type.ANSWER
        }
        peer.setRemoteDescription(object : EmptySdp() {
            override fun onSetSuccess() {
                remoteSet = true
                pendingIce.forEach { peer.addIceCandidate(it) }
                pendingIce.clear()
                if (kind == SessionDescription.Type.OFFER) {
                    createAnswer(peer)
                }
            }
            override fun onSetFailure(error: String?) {
                emit(NavProtocol.CMD_ERROR, "眼镜 setRemote 失败: $error")
            }
        }, SessionDescription(kind, sdp))
    }

    private fun createAnswer(peer: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peer.createAnswer(object : EmptySdp() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peer.setLocalDescription(object : EmptySdp() {
                    override fun onSetSuccess() = sendSdp(desc)
                    override fun onSetFailure(error: String?) {
                        emit(NavProtocol.CMD_ERROR, "眼镜 setLocal 失败: $error")
                    }
                }, desc)
            }
            override fun onCreateFailure(error: String?) {
                emit(NavProtocol.CMD_ERROR, "眼镜 createAnswer 失败: $error")
            }
        }, constraints)
    }

    private fun sendSdp(desc: SessionDescription) {
        NavProtocol.rtcSdpChunks(desc.type.canonicalForm(), desc.description).forEach { chunk ->
            emit(NavProtocol.CMD_RTC_SDP, chunk)
        }
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
    }

    private fun p2pRtcOptions(): PeerConnectionFactory.Options {
        return PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = true
        }
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        return listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun.qq.com:3478",
            "stun:stun.miwifi.com:3478",
        ).map { uri ->
            PeerConnection.IceServer.builder(uri).createIceServer()
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            val name = state?.name ?: return
            Log.i(TAG, "ice $name")
            emit(NavProtocol.CMD_RTC_STAT, """{"ice":"$name","side":"glass"}""")
            if (state == PeerConnection.IceConnectionState.FAILED) {
                emit(
                    NavProtocol.CMD_ERROR,
                    "ICE 失败，Direct 没通，RTP 过不来。",
                )
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.i(TAG, "gather ${state?.name}")
        }
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            Log.i(TAG, "local ice ${candidate.sdp}")
            emit(
                NavProtocol.CMD_RTC_ICE,
                NavProtocol.rtcIceJson(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp),
            )
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) {
            val next = channel ?: return
            if (next.label() != NavProtocol.DC_POSE) return
            poseSender.attach(next)
        }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        override fun onTrack(transceiver: RtpTransceiver?) = Unit
    }

    private fun emit(cmd: String, json: String?) {
        send?.invoke(cmd, json)
    }

    private open class EmptySdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private const val TAG = "GlassRtc"
}
