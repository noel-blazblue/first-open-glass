package com.glass.dining.phone.vision

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.glass.dining.phone.PhoneRtc
import com.glass.dining.shared.vision.KeyframePolicy
import com.glass.dining.shared.vision.LocalExtract

data class StreamFrame(
    val jpeg: ByteArray,
    val bitmap: Bitmap?,
    val extract: LocalExtract,
)

/**
 * 视频会话里的稀疏抽帧 + 端侧提取（画质 / OCR / 码）。
 * 不在后台打多模态。工具按 intent 再走 VisionPolicy。
 */
object StreamVision {
    @Volatile var onSample: ((StreamFrame) -> Unit)? = null

    @Volatile private var running = false
    private val lock = Any()
    private var frame: StreamFrame? = null
    private var grabbedAt: Long = 0
    private val worker = HandlerThread("stream-vision").apply { start() }
    private val handler = Handler(worker.looper)
    private var samples = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (PhoneRtc.hasFreshFrame()) {
                val bytes = PhoneRtc.grabJpeg(1_200)
                if (bytes != null && bytes.isNotEmpty()) {
                    val (bitmap, extract) = VisionRouter.extract(bytes, skipDuplicate = true)
                    synchronized(lock) {
                        frame = StreamFrame(bytes, bitmap, extract)
                        grabbedAt = SystemClock.elapsedRealtime()
                    }
                    samples += 1
                    if (samples == 1 || samples % 8 == 0 || extract.ocrText.isNotBlank()) {
                        Log.i(
                            TAG,
                            "keyframe n=$samples bytes=${bytes.size} ocr=${extract.ocrText.length} " +
                                "qr=${extract.barcodes.isNotEmpty()} ok=${extract.quality.ok} " +
                                "text=${extract.ocrText.replace("\n", " ").take(40)}",
                        )
                    }
                    onSample?.invoke(StreamFrame(bytes, bitmap, extract))
                } else {
                    Log.w(TAG, "keyframe encode empty")
                }
            }
            if (running) {
                handler.postDelayed(this, KeyframePolicy.SAMPLE_GAP_MS)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        samples = 0
        handler.removeCallbacks(tick)
        handler.post(tick)
        Log.i(TAG, "sampler start")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        synchronized(lock) {
            frame = null
            grabbedAt = 0
        }
        Log.i(TAG, "sampler stop")
    }

    fun latestJpeg(maxAgeMs: Long = KeyframePolicy.FRESH_FRAME_MS): ByteArray? {
        return latestFrame(maxAgeMs)?.jpeg
    }

    fun latestFrame(maxAgeMs: Long = KeyframePolicy.FRESH_FRAME_MS): StreamFrame? {
        synchronized(lock) {
            val held = frame ?: return null
            if (grabbedAt <= 0L) return null
            if (SystemClock.elapsedRealtime() - grabbedAt > maxAgeMs) return null
            return held
        }
    }

    private const val TAG = "StreamVision"
}
