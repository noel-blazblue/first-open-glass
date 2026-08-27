package com.glass.dining.phone.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import org.webrtc.VideoFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object FrameJpeg {
    fun encode(frame: VideoFrame, quality: Int = 72): ByteArray? {
        return try {
            val rotation = frame.rotation
            val i420 = frame.buffer.toI420() ?: return null
            try {
                val width = i420.width and 1.inv()
                val height = i420.height and 1.inv()
                if (width < 16 || height < 16) return null
                val nv21 = i420ToNv21(
                    y = i420.dataY,
                    strideY = i420.strideY,
                    u = i420.dataU,
                    strideU = i420.strideU,
                    v = i420.dataV,
                    strideV = i420.strideV,
                    width = width,
                    height = height,
                )
                val out = ByteArrayOutputStream()
                val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                if (!yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)) return null
                val jpeg = out.toByteArray()
                if (rotation % 360 == 0) jpeg else rotateJpeg(jpeg, rotation, quality)
            } finally {
                i420.release()
            }
        } catch (error: Exception) {
            android.util.Log.w("FrameJpeg", "encode failed: ${error.message}")
            null
        }
    }

    private fun i420ToNv21(
        y: ByteBuffer,
        strideY: Int,
        u: ByteBuffer,
        strideU: Int,
        v: ByteBuffer,
        strideV: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val nv21 = ByteArray(width * height * 3 / 2)
        copyPlane(y, strideY, width, height, nv21, 0, width)
        val chromaH = height / 2
        val chromaW = width / 2
        val uPlane = u.duplicate()
        val vPlane = v.duplicate()
        var dst = width * height
        for (row in 0 until chromaH) {
            val uRow = row * strideU
            val vRow = row * strideV
            for (col in 0 until chromaW) {
                nv21[dst++] = vPlane.get(vRow + col)
                nv21[dst++] = uPlane.get(uRow + col)
            }
        }
        return nv21
    }

    private fun copyPlane(
        src: ByteBuffer,
        stride: Int,
        width: Int,
        height: Int,
        dst: ByteArray,
        dstOffset: Int,
        dstStride: Int,
    ) {
        val buffer = src.duplicate()
        var offset = dstOffset
        for (row in 0 until height) {
            buffer.position(row * stride)
            buffer.get(dst, offset, width)
            offset += dstStride
        }
    }

    private fun rotateJpeg(jpeg: ByteArray, rotation: Int, quality: Int): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (rotated !== bitmap) rotated.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
        return out.toByteArray()
    }
}
