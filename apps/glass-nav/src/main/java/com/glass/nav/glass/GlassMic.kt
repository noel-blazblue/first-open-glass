package com.glass.nav.glass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.glass.dining.shared.nav.NavProtocol
import java.util.concurrent.atomic.AtomicBoolean

class GlassMic(private val context: Context) {
    private val running = AtomicBoolean(false)
    @Volatile var opened: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    var onPcm: ((ByteArray) -> Unit)? = null

    fun start(): String {
        if (running.get()) return "眼镜麦已开"
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            lastError = "没有麦克风权限"
            return lastError!!
        }
        val minBuf = AudioRecord.getMinBufferSize(
            NavProtocol.PCM_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            lastError = "无法创建录音缓冲"
            return lastError!!
        }
        val chunk = (NavProtocol.PCM_SAMPLE_RATE / 10) * 2
        val bufferSize = maxOf(minBuf, chunk * 2)
        lastError = null
        running.set(true)
        Thread({ captureLoop(bufferSize, chunk) }, "glass-mic").start()
        return "正在打开眼镜麦"
    }

    fun stop() {
        running.set(false)
        opened = false
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop(bufferSize: Int, chunk: Int) {
        var recorder: AudioRecord? = null
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                NavProtocol.PCM_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                lastError = "眼镜麦初始化失败"
                Log.w(TAG, lastError!!)
                return
            }
            recorder.startRecording()
            opened = true
            Log.i(TAG, "mic started chunk=$chunk")
            val buf = ByteArray(chunk)
            while (running.get()) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                val payload = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                onPcm?.invoke(payload)
            }
        } catch (error: Exception) {
            lastError = error.message ?: "眼镜麦失败"
            Log.w(TAG, "mic failed", error)
        } finally {
            opened = false
            running.set(false)
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            Log.i(TAG, "mic stopped")
        }
    }

    companion object {
        private const val TAG = "GlassNav"
    }
}
