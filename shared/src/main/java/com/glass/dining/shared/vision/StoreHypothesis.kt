package com.glass.dining.shared.vision

data class HypothesisScore(
    val score: Float,
    val reason: String,
    val centerX: Float = 0f,
    val centerY: Float = 0f,
)

/**
 * 端侧「像不像店」：用 OCR 框几何 + 汉字占比 + 门头亮带，不要求认出店名。
 */
object StoreHypothesis {
    const val THRESHOLD = 0.45f
    const val CONFIRM_HITS = 2
    const val DISMISS_HITS = 2
    const val STABLE_FRAC = 0.18f
    const val COOLDOWN_MS = 15_000L
    const val GPS_BOOST_METERS = 400.0
    const val HAN_TRUST = 0.15f

    fun largeBlocks(ocr: List<OcrBlock>, width: Int, height: Int): List<OcrBlock> {
        if (width <= 0 || height <= 0) return emptyList()
        val minArea = width * height * 0.015f
        val minH = height * 0.08f
        return ocr.filter { block ->
            val w = (block.right - block.left).coerceAtLeast(0)
            val h = (block.bottom - block.top).coerceAtLeast(0)
            w * h >= minArea || h >= minH
        }
    }

    fun score(
        width: Int,
        height: Int,
        ocr: List<OcrBlock>,
        band: Float,
        prevCenter: Pair<Float, Float>?,
        gpsBoost: Boolean = false,
    ): HypothesisScore {
        val large = largeBlocks(ocr, width, height)
        val han = hanScore(large)
        val signLike = if (han >= HAN_TRUST) large else emptyList()
        val size = sizeScore(signLike, width, height)
        val bandClamped = band.coerceIn(0f, 1f)
        val stable = stableScore(signLike, width, height, prevCenter)
        val total = if (signLike.isEmpty()) {
            bandClamped * 0.75f + stable * 0.25f
        } else {
            size * 0.30f + han * 0.25f + bandClamped * 0.30f + stable * 0.15f
        }.let { raw ->
            if (gpsBoost) (raw + 0.10f).coerceAtMost(1f) else raw.coerceIn(0f, 1f)
        }
        val center = centerOf(signLike, width, height)
        val reason =
            "size=${fmt(size)} han=${fmt(han)} band=${fmt(bandClamped)} stable=${fmt(stable)} n=${signLike.size}/${large.size}"
        return HypothesisScore(total, reason, center.first, center.second)
    }

    private fun sizeScore(large: List<OcrBlock>, width: Int, height: Int): Float {
        val image = (width * height).toFloat().coerceAtLeast(1f)
        val best = large.maxByOrNull { area(it) } ?: return 0f
        val ratio = (area(best) / image).coerceIn(0f, 0.25f) / 0.12f
        val upper = best.top < height * 0.70f
        return (ratio.coerceIn(0f, 1f) * if (upper) 1f else 0.4f)
    }

    private fun hanScore(large: List<OcrBlock>): Float {
        val text = large.joinToString("") { it.text }
        val letters = text.count { it.isLetter() }
        if (letters <= 0) return 0f
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return (han.toFloat() / letters).coerceIn(0f, 1f)
    }

    private fun stableScore(
        large: List<OcrBlock>,
        width: Int,
        height: Int,
        prevCenter: Pair<Float, Float>?,
    ): Float {
        if (prevCenter == null) return 0.5f
        val now = centerOf(large, width, height)
        val dx = now.first - prevCenter.first
        val dy = now.second - prevCenter.second
        val diag = kotlin.math.hypot(width.toDouble(), height.toDouble()).toFloat().coerceAtLeast(1f)
        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat() / diag
        return if (dist <= STABLE_FRAC) 1f else (1f - dist).coerceIn(0f, 0.4f)
    }

    private fun centerOf(large: List<OcrBlock>, width: Int, height: Int): Pair<Float, Float> {
        val best = large.maxByOrNull { area(it) }
        if (best == null) {
            return width / 2f to height * 0.35f
        }
        return (best.left + best.right) / 2f to (best.top + best.bottom) / 2f
    }

    private fun area(block: OcrBlock): Int {
        return (block.right - block.left).coerceAtLeast(0) * (block.bottom - block.top).coerceAtLeast(0)
    }

    private fun fmt(value: Float): String = "%.2f".format(value)
}
