package com.glass.dining.glass.spatial

import android.util.Log
import com.glass.dining.shared.nav.NavPose
import com.glass.dining.shared.nav.NavProtocol
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class RtcPoseSender {
    @Volatile var open: Boolean = false
        private set
    private var channel: DataChannel? = null
    private val sent = AtomicInteger(0)

    fun attach(next: DataChannel) {
        release()
        channel = next
        next.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                val state = next.state()
                open = state == DataChannel.State.OPEN
                Log.i(TAG, "rtc pose $state")
            }
            override fun onMessage(buffer: DataChannel.Buffer?) = Unit
        })
        open = next.state() == DataChannel.State.OPEN
        Log.i(TAG, "rtc pose attached label=${next.label()} state=${next.state()}")
    }

    fun send(pose: NavPose): Boolean {
        val dc = channel ?: return false
        if (dc.state() != DataChannel.State.OPEN) {
            open = false
            return false
        }
        val json = NavProtocol.poseJson(pose)
        val payload = DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8)), false)
        val ok = try {
            dc.send(payload)
        } catch (error: Exception) {
            Log.w(TAG, "rtc pose send ${error.message}")
            false
        }
        if (ok) {
            val n = sent.incrementAndGet()
            if (n == 1 || n % 20 == 0) {
                Log.i(TAG, "rtc pose n=$n yaw=${"%.1f".format(pose.yaw)} track=${pose.tracking}")
            }
        }
        return ok
    }

    fun release() {
        open = false
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

    companion object {
        private const val TAG = "GlassRtc"
    }
}
