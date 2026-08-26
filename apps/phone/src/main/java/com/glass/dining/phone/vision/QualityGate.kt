package com.glass.dining.phone.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.glass.dining.shared.vision.ImageQuality
import com.glass.dining.shared.vision.QualityReport
import java.util.concurrent.atomic.AtomicLong

object QualityGate {
    private const val SAMPLE_W = 160
    private const val SAMPLE_H = 120
    private const val DUP_WINDOW_MS = 2_000L
    private val lastHash = AtomicLong(Long.MIN_VALUE)
    private val lastAt = AtomicLong(0)

    fun inspect(jpeg: ByteArray): Pair<Bitmap?, QualityReport> {
        if (jpeg.isEmpty()) {
            return null to QualityReport(ok = false, reason = "眼镜回了空图")
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return null to QualityReport(ok = false, reason = "不是可显示的照片")
        val gray = toGray(bitmap, SAMPLE_W, SAMPLE_H)
        val report = ImageQuality.analyze(SAMPLE_W, SAMPLE_H, gray)
        val hash = ImageQuality.fingerprint(SAMPLE_W, SAMPLE_H, gray)
        val now = System.currentTimeMillis()
        val prevHash = lastHash.get()
        val prevAt = lastAt.get()
        val duplicate = prevHash == hash && now - prevAt < DUP_WINDOW_MS
        lastHash.set(hash)
        lastAt.set(now)
        if (duplicate) {
            return bitmap to QualityReport(
                ok = false,
                reason = "画面没变，请换个角度",
                brightness = report.brightness,
                sharpness = report.sharpness,
                duplicate = true,
            )
        }
        return bitmap to report
    }

    fun reset() {
        lastHash.set(Long.MIN_VALUE)
        lastAt.set(0)
    }

    private fun toGray(bitmap: Bitmap, dstW: Int, dstH: Int): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        val pixels = IntArray(dstW * dstH)
        scaled.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        return ImageQuality.downsampleGray(dstW, dstH, pixels, dstW, dstH)
    }
}
