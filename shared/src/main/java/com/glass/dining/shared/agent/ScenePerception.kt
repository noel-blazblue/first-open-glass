package com.glass.dining.shared.agent

import com.glass.dining.shared.vision.OcrBlock
import com.glass.dining.shared.vision.StoreHypothesis

/**
 * 开放场景观察：亮带/大字只负责挑 ROI，不代表门店事实。
 */
object ScenePerception {
    private val STREET = listOf("施工", "注意", "限高", "禁止", "道路", "减速", "警示")
    private val ADS = listOf("优惠", "促销", "打折", "广告", "特价")
    private val KEYBOARD = listOf("logitech", "keyboard", "esc", "ctrl", "shift")
    private val STORE_HINT = listOf("店", "火锅", "餐厅", "饭", "面", "茶", "咖啡", "烧烤")

    fun classify(
        width: Int,
        height: Int,
        ocr: List<OcrBlock>,
        band: Float,
        prev: SceneObservation? = null,
    ): SceneObservation {
        val text = ocr.joinToString(" ") { it.text }.trim()
        val keyframe = StoreHypothesis.score(width, height, ocr, band, prevCenter(prev))
        val storeLike = StoreHypothesis.storeLikelihood(width, height, ocr, band)
        val han = ocr.any { block ->
            block.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        }
        val scene = when {
            KEYBOARD.any { text.contains(it, ignoreCase = true) } && storeLike < 0.35f ->
                SceneObservation.SCENE_KEYBOARD
            STREET.any { text.contains(it) } && STORE_HINT.none { text.contains(it) } ->
                SceneObservation.SCENE_STREET_SIGN
            ADS.any { text.contains(it) } && STORE_HINT.none { text.contains(it) } ->
                SceneObservation.SCENE_BILLBOARD
            band >= 0.55f && storeLike < 0.35f && !han ->
                SceneObservation.SCENE_BANNER
            band >= 0.55f && storeLike < 0.35f ->
                SceneObservation.SCENE_SIGNAGE
            storeLike >= 0.50f ->
                SceneObservation.SCENE_STOREFRONT
            text.isNotBlank() ->
                SceneObservation.SCENE_BUILDING
            band >= 0.35f ->
                SceneObservation.SCENE_SIGNAGE
            else -> SceneObservation.SCENE_UNKNOWN
        }
        val candidate = storeCandidate(ocr, storeLike)
        val evidence = buildList {
            if (band >= 0.35f) add("band=${"%.2f".format(band)}")
            if (text.isNotBlank()) add("ocr")
            if (storeLike >= 0.35f) add("sign-like")
            add(keyframe.reason)
        }
        val confidence = when (scene) {
            SceneObservation.SCENE_STOREFRONT -> storeLike
            SceneObservation.SCENE_BANNER, SceneObservation.SCENE_SIGNAGE -> band.coerceIn(0f, 1f)
            SceneObservation.SCENE_STREET_SIGN, SceneObservation.SCENE_BILLBOARD -> 0.62f
            else -> (storeLike * 0.4f + band * 0.2f).coerceIn(0f, 0.5f)
        }
        val same = prev != null && prev.scene == scene &&
            prev.storeCandidate == candidate
        val stability = if (same) (prev?.stability ?: 0) + 1 else 1
        return SceneObservation(
            scene = scene,
            entities = entitiesOf(scene, candidate, text),
            visibleText = text.take(80),
            salientRegions = if (band >= 0.35f) listOf("upper-band") else emptyList(),
            confidence = confidence,
            evidence = evidence.take(4),
            stability = stability,
            storeCandidate = candidate,
            isStoreFact = false,
        )
    }

        fun stabilize(prev: SceneObservation?, next: SceneObservation): SceneObservation {
        val merged = if (prev != null && prev.scene == next.scene) {
            next.copy(stability = prev.stability + 1)
        } else {
            next.copy(stability = 1)
        }
        return merged.copy(isStoreFact = false)
    }

    fun canPromoteStore(
        observation: SceneObservation,
        uniqueCatalogName: String = "",
        vlmName: String = "",
        vlmConfidence: Float = 0f,
        userConfirmed: Boolean = false,
        ocrContainsName: Boolean = false,
    ): Boolean {
        if (userConfirmed && (vlmName.isNotBlank() || uniqueCatalogName.isNotBlank())) return true
        if (observation.scene == SceneObservation.SCENE_BANNER) return false
        if (observation.scene == SceneObservation.SCENE_BILLBOARD) return false
        if (observation.scene == SceneObservation.SCENE_KEYBOARD) return false
        if (observation.scene == SceneObservation.SCENE_STREET_SIGN) return false
        if (uniqueCatalogName.isNotBlank() && observation.stability >= 2) return true
        val name = vlmName.trim()
        if (name.isNotBlank() && vlmConfidence >= 0.75f && (ocrContainsName || uniqueCatalogName.isNotBlank())) {
            return true
        }
        return false
    }

    fun silentLabel(observation: SceneObservation): String {
        if (observation.stability < 2) return ""
        if (observation.isStoreFact) return observation.storeCandidate.take(8)
        return observation.label
    }

    private fun storeCandidate(ocr: List<OcrBlock>, storeLike: Float): String {
        if (storeLike < 0.45f) return ""
        val large = ocr.filter { it.text.length >= 2 }
            .maxByOrNull { it.text.count { ch -> Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN } }
        return large?.text.orEmpty().take(12)
    }

    private fun entitiesOf(scene: String, candidate: String, text: String): List<String> {
        return buildList {
            add(scene)
            if (candidate.isNotBlank()) add(candidate)
            if (text.isNotBlank()) add("text")
        }
    }

    private fun prevCenter(prev: SceneObservation?): Pair<Float, Float>? {
        if (prev == null) return null
        return 0.5f to 0.35f
    }
}
