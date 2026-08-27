package com.glass.dining.shared.vision

import com.glass.dining.shared.model.ScoredStore

enum class VisionIntent {
    LOOK_STORE,
    READ_SIGN,
    READ_MENU,
    CHECKOUT,
    SCAN_COUPON,
}

enum class VisionScene {
    STORE_SIGN,
    STREET_SIGN,
    MENU,
    QR_CODE,
    UNKNOWN,
}

enum class VisionDecision {
    TEXT_ONLY,
    UPLOAD_ROI,
    UPLOAD_FULL,
    RECAPTURE,
}

data class QualityReport(
    val ok: Boolean,
    val reason: String = "",
    val brightness: Float = 0f,
    val sharpness: Double = 0.0,
    val duplicate: Boolean = false,
    val visualGrid: IntArray = IntArray(0),
)

data class OcrBlock(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val confidence: Float = 0f,
)

data class BarcodeHit(
    val format: String,
    val payload: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val kind: String
        get() = VisionPolicy.classifyQr(payload)
    val safeType: String
        get() = when (kind) {
            "pay" -> VisionPolicy.qrPayBrand(payload)
            "table" -> "table"
            else -> "other"
        }
}

data class LocalExtract(
    val quality: QualityReport,
    val ocr: List<OcrBlock> = emptyList(),
    val barcodes: List<BarcodeHit> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
) {
    val ocrText: String
        get() = ocr.joinToString("\n") { it.text }.trim()
}

data class VisionOutcome(
    val intent: VisionIntent,
    val scene: VisionScene,
    val decision: VisionDecision,
    val reason: String,
    val ocrText: String = "",
    val ocr: List<OcrBlock> = emptyList(),
    val barcodeKind: String? = null,
    val barcodeType: String? = null,
    val merchantHint: String = "",
    val tableHint: String = "",
    val couponHint: String = "",
    val matchedStoreId: String? = null,
    val matchedStoreName: String? = null,
    val candidateNames: List<String> = emptyList(),
    val roi: OcrBlock? = null,
    val fastSpeak: String? = null,
) {
    val needsLlm: Boolean
        get() = decision == VisionDecision.UPLOAD_ROI || decision == VisionDecision.UPLOAD_FULL
    val needsRecapture: Boolean
        get() = decision == VisionDecision.RECAPTURE
}

object VisionPolicy {
    const val MIN_SHARPNESS = 18.0
    const val MIN_BRIGHTNESS = 18f
    const val MAX_BRIGHTNESS = 240f
    const val UNIQUE_SCORE = 6
    const val SIGN_MIN_CHARS = 4
    const val ROI_MIN_PX = 80

    fun decide(
        intent: VisionIntent,
        extract: LocalExtract,
        ranked: List<ScoredStore>,
        spatial: Boolean = false,
    ): VisionOutcome {
        val quality = extract.quality
        if (!quality.ok) {
            return VisionOutcome(
                intent = intent,
                scene = VisionScene.UNKNOWN,
                decision = VisionDecision.RECAPTURE,
                reason = quality.reason.ifBlank { "画质不够" },
                ocrText = extract.ocrText,
            )
        }
        val barcode = extract.barcodes.firstOrNull()
        if (barcode != null) {
            return qrOutcome(intent, extract, barcode)
        }
        val top = ranked.firstOrNull()
        val second = ranked.getOrNull(1)
        val unique = isUnique(top, second)
        val scene = sceneOf(intent, extract, unique)
        if (spatial) {
            return upload(intent, extract, ranked, scene, "用户问方位或空间关系", preferRoi = unique)
        }
        return when (intent) {
            VisionIntent.CHECKOUT -> VisionOutcome(
                intent = intent,
                scene = VisionScene.UNKNOWN,
                decision = VisionDecision.RECAPTURE,
                reason = "没扫到付款码，请对准二维码",
                ocrText = extract.ocrText,
                fastSpeak = "没扫到付款码，请对准二维码再试。",
            )
            VisionIntent.SCAN_COUPON -> scanCoupon(extract, ranked, scene)
            VisionIntent.LOOK_STORE -> lookStore(extract, ranked, unique, scene)
            VisionIntent.READ_SIGN -> readSign(extract, ranked, unique, scene)
            VisionIntent.READ_MENU -> readMenu(extract, ranked, scene)
        }
    }

    private fun lookStore(
        extract: LocalExtract,
        ranked: List<ScoredStore>,
        unique: Boolean,
        scene: VisionScene,
    ): VisionOutcome {
        val top = ranked.firstOrNull()
        if (unique && top != null) {
            return VisionOutcome(
                intent = VisionIntent.LOOK_STORE,
                scene = scene,
                decision = VisionDecision.TEXT_ONLY,
                reason = "ocr_unique_store",
                ocrText = extract.ocrText,
                ocr = extract.ocr,
                matchedStoreId = top.store.id,
                matchedStoreName = top.store.shortName,
                candidateNames = ranked.take(3).map { it.store.shortName },
                roi = roiOf(extract, top.key),
                fastSpeak = top.store.shortName,
            )
        }
        if (ranked.size >= 2) {
            return upload(VisionIntent.LOOK_STORE, extract, ranked, scene, "多家店分数接近", preferRoi = true)
        }
        if (extract.ocrText.length >= 2) {
            return upload(VisionIntent.LOOK_STORE, extract, ranked, scene, "店招有字但没唯一匹配", preferRoi = true)
        }
        return upload(VisionIntent.LOOK_STORE, extract, ranked, VisionScene.UNKNOWN, "店招无字或艺术字", preferRoi = false)
    }

    private fun readSign(
        extract: LocalExtract,
        ranked: List<ScoredStore>,
        unique: Boolean,
        scene: VisionScene,
    ): VisionOutcome {
        val top = ranked.firstOrNull()
        if (unique && top != null) {
            return VisionOutcome(
                intent = VisionIntent.READ_SIGN,
                scene = scene,
                decision = VisionDecision.TEXT_ONLY,
                reason = "sign_unique_store",
                ocrText = extract.ocrText,
                ocr = extract.ocr,
                matchedStoreId = top.store.id,
                matchedStoreName = top.store.shortName,
                candidateNames = ranked.take(3).map { it.store.shortName },
                roi = roiOf(extract, top.key),
                fastSpeak = extract.ocrText.take(40).ifBlank { top.store.shortName },
            )
        }
        if (extract.ocrText.replace("\\s".toRegex(), "").length >= SIGN_MIN_CHARS) {
            return VisionOutcome(
                intent = VisionIntent.READ_SIGN,
                scene = VisionScene.STREET_SIGN,
                decision = VisionDecision.TEXT_ONLY,
                reason = "sign_ocr_enough",
                ocrText = extract.ocrText,
                ocr = extract.ocr,
                candidateNames = ranked.take(3).map { it.store.shortName },
                fastSpeak = extract.ocrText.take(40),
            )
        }
        return upload(VisionIntent.READ_SIGN, extract, ranked, scene, "路牌字太少", preferRoi = false)
    }

    private fun readMenu(
        extract: LocalExtract,
        ranked: List<ScoredStore>,
        scene: VisionScene,
    ): VisionOutcome {
        if (menuLooksComplete(extract.ocrText)) {
            return VisionOutcome(
                intent = VisionIntent.READ_MENU,
                scene = VisionScene.MENU,
                decision = VisionDecision.TEXT_ONLY,
                reason = "menu_ocr_priced",
                ocrText = extract.ocrText,
                ocr = extract.ocr,
                matchedStoreId = ranked.firstOrNull()?.store?.id,
                matchedStoreName = ranked.firstOrNull()?.store?.shortName,
                candidateNames = ranked.take(3).map { it.store.shortName },
                fastSpeak = extract.ocrText.lineSequence().firstOrNull().orEmpty().take(20),
            )
        }
        return upload(VisionIntent.READ_MENU, extract, ranked, VisionScene.MENU, "菜单排版不完整", preferRoi = false)
    }

    private fun scanCoupon(
        extract: LocalExtract,
        ranked: List<ScoredStore>,
        scene: VisionScene,
    ): VisionOutcome {
        if (extract.ocrText.replace("\\s".toRegex(), "").length >= SIGN_MIN_CHARS) {
            return upload(VisionIntent.SCAN_COUPON, extract, ranked, scene, "券面有字但没扫到码", preferRoi = false)
        }
        return VisionOutcome(
            intent = VisionIntent.SCAN_COUPON,
            scene = VisionScene.UNKNOWN,
            decision = VisionDecision.RECAPTURE,
            reason = "没扫到券码，请对准券上的二维码",
            ocrText = extract.ocrText,
            fastSpeak = "没扫到券码，请对准再试。",
        )
    }

    private fun qrOutcome(intent: VisionIntent, extract: LocalExtract, barcode: BarcodeHit): VisionOutcome {
        val hint = merchantFromQr(barcode.payload)
        val table = tableFromQr(barcode.payload)
        val speak = when (barcode.kind) {
            "pay" -> "扫到${barcode.safeType}付款码"
            "table" -> "这是桌码，不是付款码"
            "coupon" -> "扫到团购券码"
            else -> "扫到二维码"
        }
        return VisionOutcome(
            intent = intent,
            scene = VisionScene.QR_CODE,
            decision = VisionDecision.TEXT_ONLY,
            reason = "qr_decoded_${barcode.kind}",
            ocrText = extract.ocrText,
            barcodeKind = barcode.kind,
            barcodeType = barcode.safeType,
            merchantHint = hint,
            tableHint = table,
            couponHint = couponFromQr(barcode.payload),
            fastSpeak = speak,
        )
    }

    private fun upload(
        intent: VisionIntent,
        extract: LocalExtract,
        ranked: List<ScoredStore>,
        scene: VisionScene,
        reason: String,
        preferRoi: Boolean,
    ): VisionOutcome {
        val roi = if (preferRoi) {
            roiOf(extract, ranked.firstOrNull()?.key.orEmpty()).takeIf { block ->
                block != null && (block.right - block.left) >= ROI_MIN_PX && (block.bottom - block.top) >= ROI_MIN_PX
            }
        } else {
            null
        }
        return VisionOutcome(
            intent = intent,
            scene = scene,
            decision = if (roi != null) VisionDecision.UPLOAD_ROI else VisionDecision.UPLOAD_FULL,
            reason = reason,
            ocrText = extract.ocrText,
            ocr = extract.ocr,
            matchedStoreId = ranked.firstOrNull()?.store?.id,
            matchedStoreName = ranked.firstOrNull()?.store?.shortName,
            candidateNames = ranked.take(3).map { it.store.shortName },
            roi = roi,
        )
    }

    private fun sceneOf(intent: VisionIntent, extract: LocalExtract, unique: Boolean): VisionScene {
        return when (intent) {
            VisionIntent.READ_MENU -> VisionScene.MENU
            VisionIntent.READ_SIGN -> if (unique) VisionScene.STORE_SIGN else VisionScene.STREET_SIGN
            VisionIntent.LOOK_STORE -> if (extract.ocrText.isNotBlank()) VisionScene.STORE_SIGN else VisionScene.UNKNOWN
            VisionIntent.CHECKOUT, VisionIntent.SCAN_COUPON -> VisionScene.QR_CODE
        }
    }

    private fun isUnique(top: ScoredStore?, second: ScoredStore?): Boolean {
        if (top == null || top.score < UNIQUE_SCORE) return false
        if (second == null) return true
        return top.score >= second.score + 4 || top.score >= (second.score * 14 / 10).coerceAtLeast(second.score + 1)
    }

    private fun roiOf(extract: LocalExtract, key: String): OcrBlock? {
        if (key.isBlank()) return extract.ocr.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
        return extract.ocr.firstOrNull { block ->
            block.text.contains(key) || key.contains(block.text.replace("\\s".toRegex(), ""))
        } ?: extract.ocr.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }
    }

    fun menuLooksComplete(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.length >= 2 }.toList()
        if (lines.size < 2) return false
        val priced = lines.count { line -> PRICE.containsMatchIn(line) }
        return priced >= 2
    }

    fun classifyQr(payload: String): String {
        val p = payload.lowercase()
        if (listOf("wxpay", "weixin://", "alipay", "qr.alipay", "mtpay://", "unionpay").any { p.contains(it) }) {
            return "pay"
        }
        if (listOf("table", "desk", "zhuohao", "mtcoupon://table").any { p.contains(it) }) {
            return "table"
        }
        if (listOf("mtcoupon", "coupon", "voucher", "dianping.com/coupon", "meituan.com/coupon").any { p.contains(it) }) {
            return "coupon"
        }
        return "other"
    }

    fun qrPayBrand(payload: String): String {
        val p = payload.lowercase()
        return when {
            p.contains("wxpay") || p.contains("weixin") -> "weixin"
            p.contains("alipay") -> "alipay"
            p.contains("unionpay") -> "unionpay"
            else -> "pay"
        }
    }

    fun merchantFromQr(payload: String): String {
        val match = STORE_IN_QR.find(payload) ?: return ""
        return match.groupValues[1]
    }

    fun tableFromQr(payload: String): String {
        val match = TABLE_IN_QR.find(payload) ?: return ""
        return match.groupValues[1]
    }

    fun couponFromQr(payload: String): String {
        val match = COUPON_IN_QR.find(payload) ?: return ""
        return match.groupValues[1]
    }

    private val PRICE = Regex("""[¥￥]\s*\d+|\d+(\.\d+)?\s*元""")
    private val STORE_IN_QR = Regex("""(?:(?:store|poi|merchant)=|mtpay://)([a-z0-9_]+)""", RegexOption.IGNORE_CASE)
    private val TABLE_IN_QR = Regex("""(?:table|desk|zhuohao)=([a-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val COUPON_IN_QR = Regex("""(?:coupon_id|coupon|voucher)=([a-z0-9_]+)""", RegexOption.IGNORE_CASE)
}

object ImageQuality {
    fun analyze(width: Int, height: Int, gray: ByteArray): QualityReport {
        if (width < 8 || height < 8 || gray.size < width * height) {
            return QualityReport(ok = false, reason = "照片太小")
        }
        var sum = 0L
        for (i in 0 until width * height) {
            sum += gray[i].toInt() and 0xff
        }
        val brightness = sum.toFloat() / (width * height)
        if (brightness < VisionPolicy.MIN_BRIGHTNESS) {
            return QualityReport(ok = false, reason = "画面太暗", brightness = brightness)
        }
        if (brightness > VisionPolicy.MAX_BRIGHTNESS) {
            return QualityReport(ok = false, reason = "画面过曝", brightness = brightness)
        }
        val sharpness = laplacianVariance(width, height, gray)
        if (sharpness < VisionPolicy.MIN_SHARPNESS) {
            return QualityReport(
                ok = false,
                reason = "画面模糊，请对准再拍",
                brightness = brightness,
                sharpness = sharpness,
            )
        }
        return QualityReport(ok = true, brightness = brightness, sharpness = sharpness)
    }

    fun fingerprint(width: Int, height: Int, gray: ByteArray): Long {
        val grid = visualGrid(width, height, gray)
        var hash = 0L
        for (v in grid) {
            hash = (hash * 33) xor v.toLong()
        }
        return hash
    }

    fun visualGrid(width: Int, height: Int, gray: ByteArray, size: Int = 16): IntArray {
        if (width <= 0 || height <= 0 || gray.size < width * height) return IntArray(0)
        val out = IntArray(size * size)
        for (y in 0 until size) {
            val sy = (y * height / size).coerceAtMost(height - 1)
            for (x in 0 until size) {
                val sx = (x * width / size).coerceAtMost(width - 1)
                out[y * size + x] = gray[sy * width + sx].toInt() and 0xff
            }
        }
        return out
    }

    fun downsampleGray(srcW: Int, srcH: Int, argb: IntArray, dstW: Int, dstH: Int): ByteArray {
        val gray = ByteArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = (y * srcH / dstH).coerceAtMost(srcH - 1)
            for (x in 0 until dstW) {
                val sx = (x * srcW / dstW).coerceAtMost(srcW - 1)
                val c = argb[sy * srcW + sx]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                gray[y * dstW + x] = ((r * 30 + g * 59 + b * 11) / 100).toByte()
            }
        }
        return gray
    }

    private fun laplacianVariance(width: Int, height: Int, gray: ByteArray): Double {
        val values = ArrayList<Double>((width - 2) * (height - 2))
        var sum = 0.0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val c = gray[y * width + x].toInt() and 0xff
                val n = gray[(y - 1) * width + x].toInt() and 0xff
                val s = gray[(y + 1) * width + x].toInt() and 0xff
                val w = gray[y * width + (x - 1)].toInt() and 0xff
                val e = gray[y * width + (x + 1)].toInt() and 0xff
                val lap = (n + s + w + e - 4 * c).toDouble()
                values.add(lap)
                sum += lap
            }
        }
        if (values.isEmpty()) return 0.0
        val mean = sum / values.size
        var varSum = 0.0
        for (v in values) {
            val d = v - mean
            varSum += d * d
        }
        return varSum / values.size
    }

    fun signBand(width: Int, height: Int, gray: ByteArray): Float {
        if (width < 8 || height < 8 || gray.size < width * height) return 0f
        val topH = (height * 0.7f).toInt().coerceAtLeast(3)
        val rowMean = FloatArray(topH)
        for (y in 0 until topH) {
            var sum = 0
            val row = y * width
            for (x in 0 until width) {
                sum += gray[row + x].toInt() and 0xff
            }
            rowMean[y] = sum.toFloat() / width
        }
        var best = 0f
        for (y in 1 until topH - 1) {
            val contrast = kotlin.math.abs(rowMean[y] - rowMean[y - 1]) +
                kotlin.math.abs(rowMean[y] - rowMean[y + 1])
            val bright = (rowMean[y] / 255f).coerceIn(0f, 1f)
            val local = (contrast / 90f).coerceIn(0f, 1f) * 0.55f + bright * 0.45f
            if (local > best) best = local
        }
        return best.coerceIn(0f, 1f)
    }
}
