package com.glass.dining.phone.vision

import android.graphics.Bitmap
import android.util.Log
import com.glass.dining.shared.model.Store
import com.glass.dining.shared.vision.KeyframePolicy
import com.glass.dining.shared.vision.VisionDecision
import com.glass.dining.shared.vision.VisionIntent
import com.glass.dining.shared.vision.VisionOutcome
import com.glass.dining.shared.vision.VisionScene

data class VisionObserve(
    val jpeg: ByteArray,
    val bitmap: Bitmap?,
    val outcome: VisionOutcome,
    val source: String,
)

/**
 * 到餐 Agent 的通用视觉能力：会话抽帧 → 端侧路由 → 用户主动看则上视觉模型。
 * 认店、路牌、菜单、券、支付都走这里。后台环境检测不走这里。
 */
object SceneVision {
    private const val TAG = "GlassDiningPhone"

    fun observe(
        intent: VisionIntent,
        stores: List<Store>,
        spatial: Boolean = false,
        source: String,
        useCache: Boolean = true,
        grab: (attempt: Int) -> Pair<ByteArray?, String?>,
    ): VisionObserve {
        if (useCache) {
            StreamVision.latestFrame()?.let { cached ->
                val outcome = VisionRouter.decide(intent, cached.extract, stores, spatial)
                Log.i(
                    TAG,
                    "vision cache intent=$intent decision=${outcome.decision} scene=${outcome.scene} " +
                        "ocr=${cached.extract.ocrText.length} qr=${cached.extract.barcodes.isNotEmpty()}",
                )
                if (!outcome.needsRecapture) {
                    return VisionObserve(cached.jpeg, cached.bitmap, outcome, source)
                }
            }
        }
        var lastJpeg = ByteArray(0)
        var lastBitmap: Bitmap? = null
        var lastOutcome = VisionOutcome(
            intent = intent,
            scene = VisionScene.UNKNOWN,
            decision = VisionDecision.RECAPTURE,
            reason = "没拿到画面",
        )
        var lastAt = 0L
        repeat(KeyframePolicy.MAX_RETRIES) { attempt ->
            val wait = KeyframePolicy.waitMs(System.currentTimeMillis(), lastAt)
            if (wait > 0L) {
                Thread.sleep(wait)
            }
            val (jpeg, error) = grab(attempt)
            lastAt = System.currentTimeMillis()
            if (jpeg == null) {
                lastOutcome = lastOutcome.copy(reason = error ?: "没拿到画面")
                return@repeat
            }
            lastJpeg = jpeg
            val (bitmap, outcome) = VisionRouter.analyze(
                jpeg = jpeg,
                intent = intent,
                stores = stores,
                spatial = spatial,
                skipDuplicate = attempt > 0,
            )
            lastBitmap = bitmap
            lastOutcome = outcome
            if (!outcome.needsRecapture) {
                return VisionObserve(jpeg, bitmap, outcome, source)
            }
        }
        return VisionObserve(lastJpeg, lastBitmap, lastOutcome, source)
    }
}
