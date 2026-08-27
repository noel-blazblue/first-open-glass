package com.glass.dining.shared.agent

import com.glass.dining.shared.vision.OcrBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePerceptionTest {
    @Test
    fun yellowBannerIsNotAStore() {
        val obs = ScenePerception.classify(320, 180, emptyList(), band = 0.86f)
        assertEquals(SceneObservation.SCENE_BANNER, obs.scene)
        assertFalse(ScenePerception.canPromoteStore(obs, vlmName = "巴奴", vlmConfidence = 0.9f))
        val stable = ScenePerception.stabilize(obs, obs.copy(stability = 1))
        assertEquals("横幅", ScenePerception.silentLabel(stable.copy(stability = 2)))
    }

    @Test
    fun billboardAndStreetSignStayObservations() {
        val ad = ScenePerception.classify(
            320,
            180,
            listOf(OcrBlock("限时促销", 10, 10, 200, 40)),
            band = 0.4f,
        )
        val street = ScenePerception.classify(
            320,
            180,
            listOf(OcrBlock("前方施工", 10, 10, 220, 50)),
            band = 0.3f,
        )
        assertEquals(SceneObservation.SCENE_BILLBOARD, ad.scene)
        assertEquals(SceneObservation.SCENE_STREET_SIGN, street.scene)
        assertFalse(ScenePerception.canPromoteStore(ad))
        assertFalse(ScenePerception.canPromoteStore(street))
    }

    @Test
    fun keyboardIsNotAStore() {
        val obs = ScenePerception.classify(
            320,
            180,
            listOf(OcrBlock("logitech", 12, 150, 80, 170)),
            band = 0.2f,
        )
        assertEquals(SceneObservation.SCENE_KEYBOARD, obs.scene)
        assertFalse(ScenePerception.canPromoteStore(obs))
        assertEquals("", ScenePerception.silentLabel(obs.copy(stability = 3)))
    }

    @Test
    fun ordinaryBuildingDoesNotPromote() {
        val obs = ScenePerception.classify(
            320,
            180,
            listOf(OcrBlock("办公楼", 40, 80, 120, 100)),
            band = 0.1f,
        )
        assertEquals(SceneObservation.SCENE_BUILDING, obs.scene)
        assertFalse(ScenePerception.canPromoteStore(obs, vlmName = "随便猜一家", vlmConfidence = 0.4f))
    }

    @Test
    fun uniqueOcrCanPromote() {
        val ocr = listOf(OcrBlock("青田小馆火锅", 40, 18, 260, 92))
        val first = ScenePerception.classify(320, 180, ocr, band = 0.7f)
        val second = ScenePerception.stabilize(first, ScenePerception.classify(320, 180, ocr, band = 0.72f, prev = first))
        assertTrue(second.scene == SceneObservation.SCENE_STOREFRONT || second.storeCandidate.isNotBlank())
        assertTrue(ScenePerception.canPromoteStore(second, uniqueCatalogName = "青田小馆"))
    }

    @Test
    fun userConfirmPromotes() {
        val obs = ScenePerception.classify(
            320,
            180,
            listOf(OcrBlock("巴奴", 40, 20, 180, 80)),
            band = 0.6f,
        )
        assertTrue(ScenePerception.canPromoteStore(obs, vlmName = "巴奴", userConfirmed = true))
    }

    @Test
    fun vlmNeedsVerifiableName() {
        val obs = ScenePerception.classify(
            320,
            180,
            listOf(OcrBlock("巴奴毛肚火锅", 40, 20, 240, 90)),
            band = 0.7f,
        )
        assertFalse(ScenePerception.canPromoteStore(obs, vlmName = "巴奴", vlmConfidence = 0.9f, ocrContainsName = false))
        assertTrue(ScenePerception.canPromoteStore(obs, vlmName = "巴奴", vlmConfidence = 0.9f, ocrContainsName = true))
    }
}
