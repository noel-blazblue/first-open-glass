package com.glass.dining.shared.vision

import com.glass.dining.shared.mock.MockCatalog
import com.glass.dining.shared.mock.MockCommerce
import com.glass.dining.shared.engine.DiningSession
import com.glass.dining.shared.engine.StoreVision
import com.glass.dining.shared.model.ScoredStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPolicyTest {
    private val scene = MockCatalog.sceneById("sanlitun")

    @Test
    fun qrAlwaysTextOnlyAndNeverUploads() {
        repeat(30) { index ->
            val payload = if (index % 2 == 0) {
                "weixin://wxpay/bizpayurl?store=hdl_sanlitun&n=$index"
            } else {
                "https://qr.alipay.com/hdl_sanlitun?n=$index"
            }
            val outcome = VisionPolicy.decide(
                VisionIntent.CHECKOUT,
                extract(barcode = BarcodeHit("QR", payload)),
                emptyList(),
            )
            assertEquals("case $index", VisionDecision.TEXT_ONLY, outcome.decision)
            assertEquals(VisionScene.QR_CODE, outcome.scene)
            assertEquals("pay", outcome.barcodeKind)
            assertFalse(outcome.needsLlm)
        }
    }

    @Test
    fun uniqueStoreOcrIsTextOnly() {
        val names = listOf("海底捞火锅", "喜茶太古里", "巴奴毛肚", "西贝莜面村", "奈雪的茶", "麦当劳")
        repeat(30) { index ->
            val name = names[index % names.size]
            val ranked = StoreVision.rankStores(scene, name)
            val outcome = VisionPolicy.decide(
                VisionIntent.LOOK_STORE,
                extract(ocr = name),
                ranked,
            )
            assertEquals("case $index $name", VisionDecision.TEXT_ONLY, outcome.decision)
            assertFalse(outcome.needsLlm)
            assertTrue(outcome.matchedStoreId.orEmpty().isNotBlank())
        }
    }

    @Test
    fun ambiguousStoresUpload() {
        val ranked = listOf(
            ScoredStore(scene.stores[0], 8, "火锅"),
            ScoredStore(scene.stores[2], 7, "火锅"),
        )
        repeat(30) { index ->
            val outcome = VisionPolicy.decide(
                VisionIntent.LOOK_STORE,
                extract(ocr = "三里屯火锅店 $index"),
                ranked,
            )
            assertTrue("case $index ${outcome.decision}", outcome.needsLlm)
        }
    }

    @Test
    fun blurAndDarkRecapture() {
        repeat(30) { index ->
            val reason = if (index % 2 == 0) "画面模糊，请对准再拍" else "画面太暗"
            val outcome = VisionPolicy.decide(
                VisionIntent.LOOK_STORE,
                LocalExtract(quality = QualityReport(ok = false, reason = reason)),
                emptyList(),
            )
            assertEquals(VisionDecision.RECAPTURE, outcome.decision)
            assertTrue(outcome.needsRecapture)
        }
    }

    @Test
    fun menuWithPricesIsTextOnly() {
        repeat(30) { index ->
            val text = "番茄锅¥${68 + index}\n滑牛肉¥${48 + index}\n米饭 3元"
            val outcome = VisionPolicy.decide(
                VisionIntent.READ_MENU,
                extract(ocr = text),
                emptyList(),
            )
            assertEquals("case $index", VisionDecision.TEXT_ONLY, outcome.decision)
            assertEquals(VisionScene.MENU, outcome.scene)
        }
    }

    @Test
    fun brokenMenuUploads() {
        repeat(30) { index ->
            val outcome = VisionPolicy.decide(
                VisionIntent.READ_MENU,
                extract(ocr = "今日特价 $index"),
                emptyList(),
            )
            assertTrue(outcome.needsLlm)
        }
    }

    @Test
    fun streetSignWithEnoughTextIsTextOnly() {
        repeat(30) { index ->
            val text = "工体北路步行入口$index"
            val outcome = VisionPolicy.decide(
                VisionIntent.READ_SIGN,
                extract(ocr = text),
                emptyList(),
            )
            assertEquals(VisionDecision.TEXT_ONLY, outcome.decision)
            assertFalse(outcome.needsLlm)
        }
    }

    @Test
    fun tableQrIsNotPayment() {
        val outcome = VisionPolicy.decide(
            VisionIntent.CHECKOUT,
            extract(barcode = BarcodeHit("QR", "https://meituan.com/table?poi=hdl_sanlitun&table=12")),
            emptyList(),
        )
        assertEquals("table", outcome.barcodeKind)
        assertEquals(VisionDecision.TEXT_ONLY, outcome.decision)
        assertTrue(outcome.fastSpeak.orEmpty().contains("桌码"))
    }

    @Test
    fun mockPayAndCouponNeedConfirm() {
        val session = DiningSession()
        session.look(forceStoreId = "hdl_sanlitun")
        val coupons = session.listCoupons()
        assertTrue(coupons.isNotEmpty())
        val prepared = session.prepareCoupon(coupons.first().id)
        assertEquals(coupons.first().id, prepared?.id)
        val receipt = session.redeemCoupon()
        assertTrue(receipt.ok)
        assertTrue(receipt.sequenceId.startsWith("mock-"))

        val order = MockCommerce.payFromQr("weixin://wxpay/bizpayurl?store=hdl_sanlitun", "hdl_sanlitun")
        checkNotNull(order)
        session.preparePay(order)
        val paid = session.confirmPay()
        assertTrue(paid?.confirmed == true)
        assertEquals(198, paid?.amount)
    }

    @Test
    fun sharpnessSeparatesBlurAndEdges() {
        val blur = ByteArray(160 * 120) { 80 }
        val sharp = ByteArray(160 * 120) { index ->
            val x = index % 160
            if (x % 4 == 0) 0 else 255.toByte()
        }
        val blurReport = ImageQuality.analyze(160, 120, blur)
        val sharpReport = ImageQuality.analyze(160, 120, sharp)
        assertFalse(blurReport.ok)
        assertTrue(sharpReport.ok)
        assertTrue(sharpReport.sharpness > blurReport.sharpness)
    }

    private fun extract(ocr: String = "", barcode: BarcodeHit? = null): LocalExtract {
        val blocks = if (ocr.isBlank()) {
            emptyList()
        } else {
            ocr.lineSequence().map { OcrBlock(it, 10, 10, 200, 80) }.toList()
        }
        return LocalExtract(
            quality = QualityReport(ok = true, brightness = 80f, sharpness = 40.0),
            ocr = blocks,
            barcodes = listOfNotNull(barcode),
            width = 640,
            height = 480,
        )
    }
}
