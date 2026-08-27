package com.glass.dining.phone

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glass.dining.shared.nav.NavPose
import com.glass.dining.shared.nav.NavProtocol
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class RtcPoseChannel {
    var onPose: ((NavPose) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val received = AtomicInteger(0)
    private var channel: DataChannel? = null

    fun openOn(peer: PeerConnection): DataChannel {
        val init = DataChannel.Init().apply {
            ordered = false
            maxRetransmits = 0
        }
        val dc = peer.createDataChannel(NavProtocol.DC_POSE, init)
            ?: throw IllegalStateException("createDataChannel ${NavProtocol.DC_POSE} 失败")
        listen(dc)
        return dc
    }

    fun listen(next: DataChannel) {
        release()
        channel = next
        next.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                Log.i(TAG, "rtc pose ${next.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                val pose = parse(buffer) ?: return
                val n = received.incrementAndGet()
                if (n == 1 || n % 20 == 0) {
                    Log.i(TAG, "rtc pose n=$n yaw=${"%.1f".format(pose.yaw)}")
                }
                main.post { onPose?.invoke(pose) }
            }
        })
        Log.i(TAG, "rtc pose open label=${next.label()} state=${next.state()}")
    }

    fun release() {
        try {
            channel?.unregisterObserver()
        } catch (_: Exception) {
        }
        try {
            channel?.dispose()
        } catch (_: Exception) {
        }
        channel = null
    }

    private fun parse(buffer: DataChannel.Buffer?): NavPose? {
        val data = buffer?.data ?: return null
        val bytes = ByteArray(data.remaining())
        data.get(bytes)
        val raw = String(bytes, StandardCharsets.UTF_8)
        return NavProtocol.parsePose(raw)
    }

    companion object {
        private const val TAG = "PhoneRtc"
    }
}
