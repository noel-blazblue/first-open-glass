package com.glass.dining.shared.vision

import com.glass.dining.shared.catalog.MemoryStoreCatalog
import com.glass.dining.shared.catalog.StoreFixtures
import com.glass.dining.shared.catalog.StoreGeo
import com.glass.dining.shared.engine.DiningMatcher
import com.glass.dining.shared.engine.DiningSession
import com.glass.dining.shared.engine.StoreVision
import com.glass.dining.shared.mock.MockCommerce
import com.glass.dining.shared.model.ActiveSkill
import com.glass.dining.shared.model.LookInput
import com.glass.dining.shared.model.ScoredStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPolicyTest {
    private val stores = StoreFixtures.twoNearby()
    private val catalog = MemoryStoreCatalog(stores)

    @Test
    fun qrAlwaysTextOnlyAndNeverUploads() {
        repeat(30) { index ->
            val payload = if (index % 2 == 0) {
                "weixin://wxpay/bizpayurl?store=store_hotpot&n=$index"
            } else {
                "https://qr.alipay.com/store_hotpot?n=$index"
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
        val names = listOf("青田小馆火锅", "临河茶铺")
        repeat(30) { index ->
            val name = names[index % names.size]
            val ranked = StoreVision.rankStores(stores, name)
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
    fun unmatchedOcrDoesNotPickAStore() {
        val matcher = DiningMatcher(catalog)
        assertNull(matcher.match(LookInput(visionHint = "完全不相干的招牌")))
        assertNull(StoreVision.matchStore(stores, "看不清"))
    }

    @Test
    fun emptyCatalogCannotRecommend() {
        val matcher = DiningMatcher(MemoryStoreCatalog())
        assertNull(matcher.recommend("附近火锅"))
        assertNull(matcher.match(LookInput(forceStoreId = "x")))
    }

    @Test
    fun recommendUsesEnteredStores() {
        val matcher = DiningMatcher(catalog)
        val result = matcher.recommend("附近火锅")
        assertNotNull(result)
        assertEquals("青田小馆", result!!.store.shortName)
    }

    @Test
    fun catalogStoresKeepCoords() {
        assertEquals(2, stores.size)
        assertEquals(31.2304, stores[0].lat, 0.0001)
        assertEquals(0, stores[0].distanceMeters)
        assertTrue(StoreGeo.hasCoords(stores[0]))
        val decoded = stores[0].copy(distanceMeters = 99)
        assertTrue(StoreGeo.hasCoords(decoded))
        assertEquals(0, StoreGeo.withDistance(decoded.copy(distanceMeters = 0), null, null).distanceMeters)
    }

    @Test
    fun navRequiresCoords() {
        val bare = stores[0].copy(lat = 0.0, lng = 0.0)
        assertFalse(StoreGeo.hasCoords(bare))
        val with = StoreGeo.withDistance(stores[0], 31.2300, 121.4730)
        assertTrue(with.distanceMeters > 0)
    }

    @Test
    fun banuAliasMatchesUnique() {
        val banu = stores[0].copy(
            id = "B0IAOZ0H2D",
            name = "巴奴毛肚火锅(姚家园店)",
            shortName = "巴奴毛肚火锅",
            tags = listOf("火锅", "巴奴"),
            signatures = listOf("巴奴", "毛肚火锅"),
        )
        val catalog = stores + banu
        for (hint in listOf("巴奴", "毛肚火锅", "巴奴 毛肚火锅", "巴奴毛肚火锅")) {
            val ranked = StoreVision.rankStores(catalog, hint)
            val outcome = VisionPolicy.decide(
                VisionIntent.LOOK_STORE,
                extract(ocr = hint),
                ranked,
            )
            assertEquals(hint, VisionDecision.TEXT_ONLY, outcome.decision)
            assertEquals(hint, "B0IAOZ0H2D", outcome.matchedStoreId)
            assertEquals(hint, "B0IAOZ0H2D", StoreVision.matchStore(catalog, hint)?.id)
        }
    }

    @Test
    fun uniqueStoreNameIsTextOnly() {
        val ranked = StoreVision.rankStores(stores, "青田小馆火锅朝阳店")
        val outcome = VisionPolicy.decide(
            VisionIntent.LOOK_STORE,
            extract(ocr = "青田小馆火锅"),
            ranked,
        )
        assertEquals(VisionDecision.TEXT_ONLY, outcome.decision)
        assertEquals("store_hotpot", outcome.matchedStoreId)
        assertFalse(outcome.needsLlm)
    }

    @Test
    fun ambiguousStoresUpload() {
        val ranked = listOf(
            ScoredStore(stores[0], 8, "馆"),
            ScoredStore(stores[1], 7, "铺"),
        )
        repeat(30) { index ->
            val outcome = VisionPolicy.decide(
                VisionIntent.LOOK_STORE,
                extract(ocr = "路边小店 $index"),
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
    fun couponQrIsTextOnlyAndNeverUploads() {
        val outcome = VisionPolicy.decide(
            VisionIntent.SCAN_COUPON,
            extract(barcode = BarcodeHit("QR", "https://meituan.com/coupon?coupon_id=store_hotpot_coupon_0&store=store_hotpot")),
            emptyList(),
        )
        assertEquals(VisionDecision.TEXT_ONLY, outcome.decision)
        assertEquals("coupon", outcome.barcodeKind)
        assertEquals("store_hotpot_coupon_0", outcome.couponHint)
        assertFalse(outcome.needsLlm)
    }

    @Test
    fun couponWithoutCodeRecaptures() {
        val outcome = VisionPolicy.decide(
            VisionIntent.SCAN_COUPON,
            extract(ocr = "模糊"),
            emptyList(),
        )
        assertEquals(VisionDecision.RECAPTURE, outcome.decision)
        assertTrue(outcome.needsRecapture)
    }

    @Test
    fun couponFaceWithTextUploads() {
        val outcome = VisionPolicy.decide(
            VisionIntent.SCAN_COUPON,
            extract(ocr = "88折团购券工作日可用"),
            emptyList(),
        )
        assertTrue(outcome.needsLlm)
        assertEquals(VisionIntent.SCAN_COUPON, outcome.intent)
    }

    @Test
    fun visionSkillMapsEverySceneTool() {
        assertEquals(VisionIntent.LOOK_STORE, VisionSkill.intentForTool("look_store"))
        assertEquals(VisionIntent.READ_SIGN, VisionSkill.intentForTool("read_sign"))
        assertEquals(VisionIntent.READ_MENU, VisionSkill.intentForTool("read_menu"))
        assertEquals(VisionIntent.SCAN_COUPON, VisionSkill.intentForTool("scan_coupon"))
        assertEquals(VisionIntent.CHECKOUT, VisionSkill.intentForTool("checkout"))
        assertEquals(5, VisionSkill.TOOLS.size)
        assertTrue(VisionSkill.TOOLS.all { VisionSkill.isVisionTool(it) })
        assertNull(VisionSkill.intentForTool("recommend"))
        assertNull(VisionSkill.intentForTool("start_nav"))
    }

    @Test
    fun sameFrameRoutesAllVisionIntents() {
        val ranked = StoreVision.rankStores(stores, "青田小馆火锅")
        val frame = extract(ocr = "青田小馆火锅")
        val byTool = VisionSkill.TOOLS.associateWith { tool ->
            VisionPolicy.decide(VisionSkill.intentForTool(tool)!!, frame, ranked)
        }
        assertEquals(VisionDecision.TEXT_ONLY, byTool.getValue(VisionSkill.LOOK_STORE).decision)
        assertEquals(VisionDecision.TEXT_ONLY, byTool.getValue(VisionSkill.READ_SIGN).decision)
        assertTrue(byTool.getValue(VisionSkill.READ_MENU).needsLlm)
        assertTrue(byTool.getValue(VisionSkill.SCAN_COUPON).needsLlm)
        assertEquals(VisionDecision.RECAPTURE, byTool.getValue(VisionSkill.CHECKOUT).decision)
        byTool.forEach { (tool, outcome) ->
            assertEquals(tool, VisionSkill.TOOLS.first { VisionSkill.intentForTool(it) == outcome.intent })
        }
    }

    @Test
    fun keyframeWaitsBetweenSamples() {
        assertTrue(KeyframePolicy.allowSample(1_000L, 0L))
        assertFalse(KeyframePolicy.allowSample(1_200L, 1_000L))
        assertTrue(KeyframePolicy.allowSample(1_000L + KeyframePolicy.MIN_GAP_MS, 1_000L))
        assertEquals(500L, KeyframePolicy.waitMs(1_200L, 1_000L))
    }

    @Test
    fun tableQrIsNotPayment() {
        val outcome = VisionPolicy.decide(
            VisionIntent.CHECKOUT,
            extract(barcode = BarcodeHit("QR", "https://meituan.com/table?poi=store_hotpot&table=12")),
            emptyList(),
        )
        assertEquals("table", outcome.barcodeKind)
        assertEquals(VisionDecision.TEXT_ONLY, outcome.decision)
        assertTrue(outcome.fastSpeak.orEmpty().contains("桌码"))
    }

    @Test
    fun clearLookDropsCurrentStore() {
        val session = DiningSession(catalog)
        session.look(forceStoreId = "store_hotpot")
        assertNotNull(session.currentStore)
        assertEquals(ActiveSkill.BROWSE, session.activeSkill)
        session.clearLook()
        assertNull(session.currentStore)
        assertEquals(ActiveSkill.NONE, session.activeSkill)
    }

    @Test
    fun mockPayAndCouponNeedConfirm() {
        val session = DiningSession(catalog)
        session.look(forceStoreId = "store_hotpot")
        val coupons = session.listCoupons()
        assertTrue(coupons.isNotEmpty())
        val prepared = session.prepareCoupon(coupons.first().id)
        assertEquals(coupons.first().id, prepared?.id)
        val receipt = session.redeemCoupon()
        assertTrue(receipt.ok)
        assertTrue(receipt.sequenceId.startsWith("mock-"))

        val order = MockCommerce.payFromQr(
            "weixin://wxpay/bizpayurl?store=store_hotpot",
            session.currentStore,
            session.catalog.stores(),
        )
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
