package com.glass.dining.phone

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.glass.dining.phone.vision.FrameJpeg
import com.glass.dining.phone.vision.StreamVision
import com.glass.dining.shared.nav.NavProtocol
import com.glass.dining.shared.vision.KeyframePolicy
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机侧 WebRTC 探针：收眼镜 H.264，信令走 CXR。
 */
object PhoneRtc {
    @Volatile var active: Boolean = false
        private set

    var onSignal: ((String, String?) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val sdpParts = NavProtocol.SdpAssembler()
    private val pendingIce = CopyOnWriteArrayList<IceCandidate>()
    private val rtcThread = HandlerThread("phone-rtc").apply { start() }
    private val rtc = Handler(rtcThread.looper)
    private val frames = AtomicInteger(0)

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var remoteTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    @Volatile private var pendingRenderer: SurfaceViewRenderer? = null
    private var remoteSet: Boolean = false
    @Volatile private var initialized: Boolean = false
    private val sink = ProxySink()
    private val latestLock = Any()
    private var latestFrame: VideoFrame? = null
    @Volatile private var lastFrameAt: Long = 0

    fun prepare(context: Context) {
        val done = java.util.concurrent.CountDownLatch(1)
        rtc.post {
            try {
                ensureFactory(context.applicationContext)
            } catch (error: Exception) {
                Log.w(TAG, "prepare failed", error)
                status("WebRTC 初始化失败: ${error.message}")
            } finally {
                done.countDown()
            }
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun startOffer() {
        rtc.post { startOfferOnRtc() }
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

    fun attachRenderer(view: SurfaceViewRenderer) {
        pendingRenderer = view
        main.post { bindRenderer() }
    }

    fun hasFreshFrame(maxAgeMs: Long = KeyframePolicy.FRESH_FRAME_MS): Boolean {
        if (!active) return false
        val at = lastFrameAt
        return at > 0L && SystemClock.elapsedRealtime() - at <= maxAgeMs
    }

    fun grabJpeg(timeoutMs: Long = 2_500): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val result = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
            val done = java.util.concurrent.CountDownLatch(1)
            rtc.post {
                try {
                    val snap = snapshotFrame()
                    if (snap != null) {
                        try {
                            result.set(FrameJpeg.encode(snap))
                        } finally {
                            snap.release()
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
            if (!done.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                continue
            }
            val jpeg = result.get()
            if (jpeg != null && jpeg.isNotEmpty()) return jpeg
            try {
                Thread.sleep(80)
            } catch (_: InterruptedException) {
                return null
            }
        }
        return null
    }

    fun detachRenderer(view: SurfaceViewRenderer? = null) {
        main.post {
            if (view != null && renderer !== view && pendingRenderer !== view) return@post
            sink.target = null
            try {
                renderer?.release()
            } catch (_: Exception) {
            }
            renderer = null
            pendingRenderer = null
        }
    }

    private fun ensureFactory(context: Context) {
        if (initialized && factory != null && egl != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val root = EglBase.create()
        egl = root
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(root.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(root.eglBaseContext))
            .createPeerConnectionFactory()
        initialized = true
        Log.i(TAG, "factory ready")
        main.post { bindRenderer() }
    }

    private fun bindRenderer() {
        val view = pendingRenderer ?: return
        val root = egl ?: return
        if (renderer === view) {
            sink.target = view
            return
        }
        try {
            renderer?.release()
        } catch (_: Exception) {
        }
        view.setEnableHardwareScaler(true)
        view.setMirror(false)
        view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        view.init(root.eglBaseContext, null)
        renderer = view
        sink.target = view
    }

    private fun startOfferOnRtc() {
        if (active && pc != null) {
            createOffer(pc!!)
            return
        }
        try {
            val peerFactory = factory ?: throw IllegalStateException("WebRTC 还没初始化")
            val peer = peerFactory.createPeerConnection(rtcConfig(), observer)
                ?: throw IllegalStateException("createPeerConnection 失败")
            peer.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            pc = peer
            active = true
            frames.set(0)
            createOffer(peer)
            status("已发 offer，等眼镜 answer / ICE")
        } catch (error: Exception) {
            Log.w(TAG, "offer failed", error)
            status("视频流 offer 失败: ${error.message}")
            stopOnRtc()
        }
    }

    private fun stopOnRtc() {
        active = false
        remoteSet = false
        pendingIce.clear()
        StreamVision.stop()
        releaseLatestFrame()
        try {
            remoteTrack?.removeSink(sink)
        } catch (_: Exception) {
        }
        remoteTrack = null
        try {
            pc?.close()
        } catch (_: Exception) {
        }
        pc = null
        Log.i(TAG, "rtc stopped")
    }

    private fun createOffer(peer: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peer.createOffer(object : EmptySdp() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peer.setLocalDescription(object : EmptySdp() {
                    override fun onSetSuccess() = sendSdp(desc)
                    override fun onSetFailure(error: String?) {
                        status("手机 setLocal 失败: $error")
                    }
                }, desc)
            }
            override fun onCreateFailure(error: String?) {
                status("手机 createOffer 失败: $error")
            }
        }, constraints)
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
                status("已收到眼镜 $type")
            }
            override fun onSetFailure(error: String?) {
                status("手机 setRemote 失败: $error")
            }
        }, SessionDescription(kind, sdp))
    }

    private fun sendSdp(desc: SessionDescription) {
        NavProtocol.rtcSdpChunks(desc.type.canonicalForm(), desc.description).forEach { chunk ->
            onSignal?.invoke(NavProtocol.CMD_RTC_SDP, chunk)
        }
        Log.i(TAG, "sent ${desc.type} bytes=${desc.description.length}")
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
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
            val extra = when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> "画面应开始出。帧=${frames.get()}"
                PeerConnection.IceConnectionState.FAILED ->
                    "ICE 失败。Direct 没通，RTP 过不来。"
                PeerConnection.IceConnectionState.DISCONNECTED -> "ICE 断开"
                else -> "ice=$name"
            }
            status(extra)
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.i(TAG, "gather ${state?.name}")
        }
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            onSignal?.invoke(
                NavProtocol.CMD_RTC_ICE,
                NavProtocol.rtcIceJson(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp),
            )
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) {
            stream?.videoTracks?.firstOrNull()?.let { bindTrack(it) }
        }
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            (receiver?.track() as? VideoTrack)?.let { bindTrack(it) }
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            (transceiver?.receiver?.track() as? VideoTrack)?.let { bindTrack(it) }
        }
    }

    private fun bindTrack(track: VideoTrack) {
        if (remoteTrack === track) return
        remoteTrack?.removeSink(sink)
        remoteTrack = track
        track.setEnabled(true)
        track.addSink(sink)
        status("已接到眼镜视频轨")
    }

    private fun status(line: String) {
        main.post { onStatus?.invoke(line) }
    }

    private class ProxySink : VideoSink {
        @Volatile var target: VideoSink? = null
        override fun onFrame(frame: VideoFrame) {
            val n = frames.incrementAndGet()
            holdLatest(frame)
            if (n == 1) {
                StreamVision.start()
            }
            if (n == 1 || n % 40 == 0) {
                Log.i(TAG, "frame n=$n ${frame.buffer.width}x${frame.buffer.height}")
                status("已收到 ${frame.buffer.width}x${frame.buffer.height} 第${n}帧")
            }
            target?.onFrame(frame)
        }
    }

    private fun holdLatest(frame: VideoFrame) {
        frame.retain()
        val old = synchronized(latestLock) {
            val prev = latestFrame
            latestFrame = frame
            lastFrameAt = SystemClock.elapsedRealtime()
            prev
        }
        old?.release()
    }

    private fun snapshotFrame(): VideoFrame? {
        synchronized(latestLock) {
            val held = latestFrame ?: return null
            held.retain()
            return held
        }
    }

    private fun releaseLatestFrame() {
        synchronized(latestLock) {
            latestFrame?.release()
            latestFrame = null
            lastFrameAt = 0
        }
    }

    private open class EmptySdp : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private const val TAG = "PhoneRtc"
}
