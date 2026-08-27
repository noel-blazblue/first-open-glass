package com.glass.dining.phone.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.glass.dining.shared.vision.BarcodeHit
import com.glass.dining.shared.vision.ImageQuality
import com.glass.dining.shared.vision.OcrBlock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object LocalVision {
    private const val TAG = "GlassDiningPhone"
    private val textClient by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val barcodeClient by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_AZTEC,
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    fun ocr(bitmap: Bitmap): List<OcrBlock> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(textClient.process(image), 2, TimeUnit.SECONDS)
            result.textBlocks.map { block ->
                val box = block.boundingBox ?: Rect()
                OcrBlock(
                    text = block.text.replace("\n", " ").trim(),
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    confidence = 0.8f,
                )
            }.filter { it.text.isNotBlank() }
        } catch (error: Exception) {
            Log.w(TAG, "ocr failed: ${error.message}")
            emptyList()
        }
    }

    fun barcodes(bitmap: Bitmap): List<BarcodeHit> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(barcodeClient.process(image), 2, TimeUnit.SECONDS)
            result.mapNotNull { code ->
                val raw = code.rawValue?.trim().orEmpty()
                if (raw.isBlank()) return@mapNotNull null
                val box = code.boundingBox ?: Rect()
                BarcodeHit(
                    format = code.format.toString(),
                    payload = raw,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "barcode failed: ${error.message}")
            emptyList()
        }
    }

    fun crop(jpeg: ByteArray, block: OcrBlock?, quality: Int = 55): ByteArray {
        if (block == null) return jpeg
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val w = block.right - block.left
        val h = block.bottom - block.top
        if (w < 80 || h < 80) return jpeg
        val padX = (w * 0.15f).toInt().coerceAtLeast(8)
        val padY = (h * 0.15f).toInt().coerceAtLeast(8)
        val left = (block.left - padX).coerceAtLeast(0)
        val top = (block.top - padY).coerceAtLeast(0)
        val width = (w + padX * 2).coerceAtMost(bitmap.width - left)
        val height = (h + padY * 2).coerceAtMost(bitmap.height - top)
        if (width < 80 || height < 80) return jpeg
        return try {
            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (cropped !== bitmap) cropped.recycle()
            out.toByteArray()
        } catch (error: Exception) {
            Log.w(TAG, "crop failed: ${error.message}")
            jpeg
        }
    }

    fun compress(jpeg: ByteArray, quality: Int = 45): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    fun signBand(bitmap: Bitmap): Float {
        val dstW = 80
        val dstH = 60
        val scaled = Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
        val pixels = IntArray(dstW * dstH)
        scaled.getPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        val gray = ImageQuality.downsampleGray(dstW, dstH, pixels, dstW, dstH)
        return ImageQuality.signBand(dstW, dstH, gray)
    }

    fun subjectJpeg(jpeg: ByteArray, quality: Int = 70): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        return try {
            val left = (bitmap.width * 0.15f).toInt().coerceAtLeast(0)
            val top = (bitmap.height * 0.08f).toInt().coerceAtLeast(0)
            val cropW = (bitmap.width * 0.70f).toInt().coerceAtLeast(80)
                .coerceAtMost((bitmap.width - left).coerceAtLeast(0))
            val cropH = (bitmap.height * 0.67f).toInt().coerceAtLeast(80)
                .coerceAtMost((bitmap.height - top).coerceAtLeast(0))
            if (cropW < 80 || cropH < 80) {
                return jpeg
            }
            val cropped = Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (cropped !== bitmap) {
                cropped.recycle()
            }
            out.toByteArray()
        } catch (error: Exception) {
            Log.w(TAG, "subject crop failed: ${error.message}")
            jpeg
        } finally {
            bitmap.recycle()
        }
    }
}
