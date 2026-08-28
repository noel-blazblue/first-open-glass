package com.glass.dining.phone.tools

import com.glass.dining.phone.agent.PhoneWorld
import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.agent.EvidenceGate
import com.glass.dining.shared.agent.SceneObservation
import com.glass.dining.shared.catalog.StoreVision
import com.glass.dining.shared.vision.VisionIntent
import org.json.JSONObject

class VisionToolProvider(private val world: PhoneWorld) {
    fun register(registry: ToolRegistry) {
        registry.register(AgentToolCatalog.LOOK_AT_SCENE, ::look)
        registry.register(AgentToolCatalog.SCAN_COUPON) { world.capture(VisionIntent.SCAN_COUPON) }
    }

    private fun look(args: JSONObject): String {
        val purpose = args.optString("purpose").ifBlank { "observe" }
        return when (purpose) {
            "store" -> lookStore()
            "menu" -> world.capture(VisionIntent.READ_MENU)
            else -> {
                if (world.session.navigating) {
                    world.capture(VisionIntent.READ_SIGN, spatial = true)
                } else {
                    lookAtScene()
                }
            }
        }
    }

    private fun lookAtScene(): String {
        val captured = world.capture(VisionIntent.LOOK_STORE, bindStore = false)
        val json = try {
            JSONObject(captured)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("message", captured)
        }
        json.put("observation", true)
        json.put("is_store_fact", false)
        json.put("matched", false)
        if (!json.has("message")) {
            json.put("message", json.optString("vision").ifBlank { "我看了眼前，还不能确定。" })
        }
        if (!json.has("hint")) {
            json.put("hint", "这是这一眼的观察，不是已确认门店")
        }
        return json.toString()
    }

    private fun lookStore(): String {
        val captured = world.capture(VisionIntent.LOOK_STORE, bindStore = true)
        val json = try {
            JSONObject(captured)
        } catch (_: Exception) {
            return captured
        }
        if (json.optBoolean("matched", false)) return captured
        val seen = json.optString("store").ifBlank { json.optString("ocr") }
        val catalog = StoreVision.matchStore(world.session.catalog.stores(), seen)
        val unique = catalog != null
        val promote = EvidenceGate.decideBind(
            EvidenceGate.BindProposal(
                source = EvidenceGate.BindSource.OBSERVATION,
                placeId = catalog?.id.orEmpty(),
                name = seen,
                observation = SceneObservation(
                    visibleText = seen,
                    storeCandidate = seen,
                    stability = 2,
                ),
                uniqueCatalogName = if (unique) catalog!!.shortName else "",
                vlmName = seen,
                vlmConfidence = json.optDouble("confidence", 0.0).toFloat(),
                ocrContainsName = seen.isNotBlank() && json.optString("ocr").contains(seen.take(2)),
            ),
        )
        if (promote.accept && catalog != null) {
            val result = world.session.look(forceStoreId = catalog.id, visionHint = seen)
            if (result != null) {
                world.selectedThisTurn = true
                world.rememberVisionStore(result.store.id)
                world.publishMatch(result, "视觉认店")
                return DiningToolProvider.facts(result)
                    .put("matched", true)
                    .put("is_store_fact", true)
                    .toString()
            }
        }
        json.put("matched", false).put("is_store_fact", false)
            .put("hint", "这是观察，不是已确认门店")
        return json.toString()
    }
}
