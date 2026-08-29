package com.glass.dining.glass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.glass.dining.shared.link.MediaWire

class GlassMic(private val context: Context) {
    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private var worker: Thread? = null

    @Volatile var opened: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    var onPcm: ((ByteArray) -> Unit)? = null

    fun start(): String {
        synchronized(lock) {
            if (running.get()) return "眼镜麦已开"
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                lastError = "没有麦克风权限"
                return lastError!!
            }
            val minBuf = AudioRecord.getMinBufferSize(
                MediaWire.PCM_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                lastError = "无法创建录音缓冲"
                return lastError!!
            }
            val chunk = (MediaWire.PCM_SAMPLE_RATE / 10) * 2
            val bufferSize = maxOf(minBuf, chunk * 2)
            lastError = null
            val gen = generation.incrementAndGet()
            running.set(true)
            worker = Thread({ captureLoop(bufferSize, chunk, gen) }, "glass-mic")
            worker!!.start()
            return "正在打开眼镜麦"
        }
    }

    fun stop() {
        val toJoin: Thread?
        synchronized(lock) {
            running.set(false)
            generation.incrementAndGet()
            toJoin = worker
            worker = null
            opened = false
        }
        val current = Thread.currentThread()
        if (toJoin != null && toJoin != current) {
            try {
                toJoin.join(400)
            } catch (_: InterruptedException) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop(bufferSize: Int, chunk: Int, gen: Int) {
        var recorder: AudioRecord? = null
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                MediaWire.PCM_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                lastError = "眼镜麦初始化失败"
                Log.w(TAG, lastError!!)
                return
            }
            if (!alive(gen)) return
            recorder.startRecording()
            opened = true
            Log.i(TAG, "mic started chunk=$chunk gen=$gen")
            val buf = ByteArray(chunk)
            while (alive(gen)) {
                val n = recorder.read(buf, 0, buf.size)
                if (!alive(gen)) break
                if (n <= 0) continue
                val payload = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                onPcm?.invoke(payload)
            }
        } catch (error: Exception) {
            lastError = error.message ?: "眼镜麦失败"
            Log.w(TAG, "mic failed", error)
        } finally {
            opened = false
            if (generation.get() == gen) {
                running.set(false)
            }
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            Log.i(TAG, "mic stopped gen=$gen")
        }
    }

    private fun alive(gen: Int): Boolean {
        return running.get() && generation.get() == gen
    }

    companion object {
        private const val TAG = "GlassApp"
    }
}
