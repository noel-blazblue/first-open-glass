package com.glass.dining.shared.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreHypothesisTest {
    @Test
    fun largeHanSignScoresHigh() {
        val width = 320
        val height = 180
        val ocr = listOf(
            OcrBlock("巴奴毛肚火锅", left = 40, top = 18, right = 260, bottom = 92),
        )
        val first = StoreHypothesis.score(width, height, ocr, band = 0.72f, prevCenter = null)
        assertTrue(first.reason, first.score >= StoreHypothesis.THRESHOLD)
        val second = StoreHypothesis.score(
            width,
            height,
            ocr,
            band = 0.72f,
            prevCenter = first.centerX to first.centerY,
        )
        assertTrue(second.reason, second.score >= 0.70f)
        assertEquals(1, StoreHypothesis.largeBlocks(ocr, width, height).size)
    }

    @Test
    fun crumbLatinKeyboardScoresLow() {
        val width = 320
        val height = 180
        val ocr = listOf(
            OcrBlock("logitech", left = 12, top = 158, right = 52, bottom = 170),
            OcrBlock("Jade", left = 60, top = 160, right = 88, bottom = 172),
            OcrBlock("OOOO", left = 200, top = 164, right = 228, bottom = 174),
        )
        assertTrue(StoreHypothesis.largeBlocks(ocr, width, height).isEmpty())
        val scored = StoreHypothesis.score(width, height, ocr, band = 0.18f, prevCenter = null)
        assertTrue(scored.reason, scored.score < StoreHypothesis.THRESHOLD)
    }

    @Test
    fun largeLatinJunkDoesNotBlockSignBand() {
        val width = 960
        val height = 540
        val ocr = listOf(
            OcrBlock("logitech", left = 40, top = 420, right = 420, bottom = 520),
            OcrBlock("OOOO", left = 500, top = 430, right = 760, bottom = 510),
        )
        assertTrue(StoreHypothesis.largeBlocks(ocr, width, height).isNotEmpty())
        val first = StoreHypothesis.score(width, height, ocr, band = 0.74f, prevCenter = null)
        val second = StoreHypothesis.score(
            width,
            height,
            ocr,
            band = 0.75f,
            prevCenter = first.centerX to first.centerY,
        )
        assertTrue(first.reason, first.score >= StoreHypothesis.THRESHOLD)
        assertTrue(second.reason, second.score >= StoreHypothesis.THRESHOLD)
        val storeLike = StoreHypothesis.storeLikelihood(width, height, ocr, band = 0.75f)
        assertTrue("latin junk is not a store storeLike=$storeLike", storeLike < 0.35f)
    }

    @Test
    fun artSignWithoutOcrIsKeyframeNotStore() {
        val scored = StoreHypothesis.score(
            width = 320,
            height = 180,
            ocr = emptyList(),
            band = 0.82f,
            prevCenter = null,
        )
        assertTrue(scored.reason, scored.score >= StoreHypothesis.THRESHOLD)
        val storeLike = StoreHypothesis.storeLikelihood(320, 180, emptyList(), band = 0.82f)
        assertTrue("storeLike=$storeLike", storeLike < StoreHypothesis.STORE_THRESHOLD)
    }

    @Test
    fun signBandFindsBrightStripe() {
        val width = 80
        val height = 60
        val stripe = ByteArray(width * height) { 36 }
        for (y in 12..18) {
            for (x in 0 until width) {
                stripe[y * width + x] = 220.toByte()
            }
        }
        val dark = ByteArray(width * height) { 36 }
        assertTrue(ImageQuality.signBand(width, height, stripe) > 0.70f)
        assertTrue(ImageQuality.signBand(width, height, dark) < 0.20f)
    }
}
