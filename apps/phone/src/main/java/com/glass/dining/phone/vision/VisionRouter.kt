package com.glass.dining.phone.vision

import android.graphics.Bitmap
import android.util.Log
import com.glass.dining.shared.engine.StoreVision
import com.glass.dining.shared.model.Store
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
        stores: List<Store>,
        spatial: Boolean = false,
        skipDuplicate: Boolean = false,
    ): Pair<Bitmap?, VisionOutcome> {
        val start = System.currentTimeMillis()
        val (bitmap, extract) = extract(jpeg, skipDuplicate)
        val outcome = decide(intent, extract, stores, spatial)
        log(outcome, start)
        return bitmap to outcome
    }

    fun extract(jpeg: ByteArray, skipDuplicate: Boolean = false): Pair<Bitmap?, LocalExtract> {
        val (bitmap, quality) = QualityGate.inspect(jpeg, skipDuplicate = skipDuplicate)
        if (bitmap == null || !quality.ok) {
            return bitmap to LocalExtract(quality = quality)
        }
        val barcodes = LocalVision.barcodes(bitmap)
        val ocr = if (barcodes.isEmpty()) LocalVision.ocr(bitmap) else emptyList()
        return bitmap to LocalExtract(
            quality = quality,
            ocr = ocr,
            barcodes = barcodes,
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    fun decide(
        intent: VisionIntent,
        extract: LocalExtract,
        stores: List<Store>,
        spatial: Boolean = false,
    ): VisionOutcome {
        val ranked = StoreVision.rankStores(stores, extract.ocrText)
        return VisionPolicy.decide(intent, extract, ranked, spatial)
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
