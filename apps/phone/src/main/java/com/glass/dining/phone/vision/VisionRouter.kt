package com.glass.dining.phone.vision

import android.graphics.Bitmap
import android.util.Log
import com.glass.dining.shared.engine.StoreVision
import com.glass.dining.shared.model.Scene
import com.glass.dining.shared.vision.LocalExtract
import com.glass.dining.shared.vision.VisionDecision
import com.glass.dining.shared.vision.VisionIntent
import com.glass.dining.shared.vision.VisionOutcome
import com.glass.dining.shared.vision.VisionPolicy

object VisionRouter {
    private const val TAG = "GlassDiningPhone"

    fun analyze(
        jpeg: ByteArray,
        intent: VisionIntent,
        scene: Scene,
        spatial: Boolean = false,
    ): Pair<Bitmap?, VisionOutcome> {
        val start = System.currentTimeMillis()
        val (bitmap, quality) = QualityGate.inspect(jpeg)
        if (bitmap == null || !quality.ok) {
            val outcome = VisionPolicy.decide(
                intent,
                LocalExtract(quality = quality),
                emptyList(),
                spatial,
            )
            log(outcome, start)
            return bitmap to outcome
        }
        val barcodes = if (intent == VisionIntent.CHECKOUT || intent == VisionIntent.LOOK_STORE) {
            LocalVision.barcodes(bitmap)
        } else {
            emptyList()
        }
        val ocr = if (barcodes.isEmpty()) LocalVision.ocr(bitmap) else emptyList()
        val extract = LocalExtract(
            quality = quality,
            ocr = ocr,
            barcodes = barcodes,
            width = bitmap.width,
            height = bitmap.height,
        )
        val ranked = StoreVision.rankStores(scene, extract.ocrText)
        val outcome = VisionPolicy.decide(intent, extract, ranked, spatial)
        log(outcome, start)
        return bitmap to outcome
    }

    fun bytesForLlm(jpeg: ByteArray, outcome: VisionOutcome): ByteArray {
        return when (outcome.decision) {
            VisionDecision.UPLOAD_ROI -> LocalVision.crop(jpeg, outcome.roi)
            VisionDecision.UPLOAD_FULL -> LocalVision.compress(jpeg)
            else -> ByteArray(0)
        }
    }

    private fun log(outcome: VisionOutcome, startedAt: Long) {
        val elapsed = System.currentTimeMillis() - startedAt
        Log.i(
            TAG,
            "vision decision=${outcome.decision} scene=${outcome.scene} reason=${outcome.reason} " +
                "ocrChars=${outcome.ocrText.length} hasQr=${outcome.barcodeKind != null} " +
                "store=${outcome.matchedStoreId} ${elapsed}ms",
        )
    }
}
