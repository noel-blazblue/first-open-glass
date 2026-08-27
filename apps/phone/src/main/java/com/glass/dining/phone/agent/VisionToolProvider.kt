package com.glass.dining.phone.agent

import android.os.SystemClock
import com.glass.dining.phone.vision.StreamVision
import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.agent.EnvironmentStore
import com.glass.dining.shared.agent.FloorQueryPolicy
import com.glass.dining.shared.agent.SceneObservation
import com.glass.dining.shared.agent.ScenePerception
import com.glass.dining.shared.engine.StoreVision
import com.glass.dining.shared.vision.VisionIntent
import org.json.JSONObject

class VisionToolProvider(private val world: PhoneWorld) {
    fun register(registry: ToolRegistry) {
        registry.register(AgentToolCatalog.LOOK_AT_SCENE, ::lookAtScene)
        registry.register(AgentToolCatalog.LOOK_STORE, ::lookStore)
        registry.register(AgentToolCatalog.READ_SIGN) { world.capture(VisionIntent.READ_SIGN, spatial = true) }
        registry.register(AgentToolCatalog.READ_MENU) { world.capture(VisionIntent.READ_MENU) }
        registry.register(AgentToolCatalog.SCAN_COUPON) { world.capture(VisionIntent.SCAN_COUPON) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun lookAtScene(args: JSONObject): String {
        val env = EnvironmentStore.snapshot()
        val now = SystemClock.elapsedRealtime()
        val reason = args.optString("reason")
        val askingFloor = FloorQueryPolicy.isFloorAsk(world.question) ||
            FloorQueryPolicy.isFloorAsk(reason) ||
            reason.contains("floor")
        if (askingFloor) {
            FloorQueryPolicy.resolve(env)?.let { answer ->
                return JSONObject()
                    .put("ok", true)
                    .put("observation", true)
                    .put("environment", true)
                    .put("floor", answer.floor)
                    .put("floor_source", answer.source)
                    .put("is_store_fact", false)
                    .put("message", answer.speak)
                    .put("hint", "楼层来自用户确认或近期楼层标识，不是当前画面猜测")
                    .toString()
            }
            EnvironmentWatcher.requestFresh(StreamVision.latestFrame(8_000))
        } else if (env.stale(now) || EnvironmentWatcher.shouldRefresh()) {
            EnvironmentWatcher.requestFresh(StreamVision.latestFrame(8_000))
        }
        if (env.hasUsable) {
            return JSONObject()
                .put("ok", true)
                .put("observation", true)
                .put("environment", true)
                .put("floor", env.usableFloor)
                .put("is_store_fact", false)
                .put("message", env.currentBrief.ifBlank { "眼前环境已有记录，不是门店事实。" })
                .put("hint", "环境记忆不是门店事实")
                .toString()
        }
        val obs = world.observation
        if (obs != null && obs.stability >= 1 && obs.scene != "unknown") {
            return JSONObject()
                .put("ok", true)
                .put("observation", true)
                .put("scene", obs.scene)
                .put("visible_text", obs.visibleText)
                .put("confidence", obs.confidence.toDouble())
                .put("store_candidate", obs.storeCandidate)
                .put("is_store_fact", obs.isStoreFact)
                .put("label", obs.label)
                .put("message", speakObservation(obs.scene, obs.visibleText, obs.storeCandidate, obs.isStoreFact))
                .put("hint", "观察不是事实，不要绑定门店")
                .toString()
        }
        val captured = world.capture(VisionIntent.LOOK_STORE, bindStore = false)
        val json = try {
            JSONObject(captured)
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("message", captured)
        }
        json.put("observation", true)
        json.put("is_store_fact", json.optBoolean("matched", false))
        if (!json.has("message")) {
            json.put("message", json.optString("vision").ifBlank { "我看了眼前，还不能确定。" })
        }
        return json.toString()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun lookStore(args: JSONObject): String {
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
        val promote = ScenePerception.canPromoteStore(
            observation = world.observation ?: SceneObservation(
                scene = "storefront",
                visibleText = seen,
                storeCandidate = seen,
                stability = 2,
            ),
            uniqueCatalogName = if (unique) catalog!!.shortName else "",
            vlmName = seen,
            vlmConfidence = json.optDouble("confidence", 0.0).toFloat(),
            ocrContainsName = seen.isNotBlank() && json.optString("ocr").contains(seen.take(2)),
        )
        if (promote && catalog != null) {
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

    private fun speakObservation(scene: String, text: String, candidate: String, fact: Boolean): String {
        if (fact && candidate.isNotBlank()) return "眼前是$candidate。"
        val seen = text.take(16).ifBlank { candidate }
        return when (scene) {
            "banner" -> "这是一条横幅或标识，还不能确定是店。"
            "billboard" -> "这更像广告牌。"
            "street_sign" -> "这是路牌或警示。"
            "keyboard" -> "画面里主要是键盘或屏幕，不像店招。"
            "building" -> if (seen.isBlank()) "这是普通建筑，我还认不出名字。" else "画面上有「$seen」。"
            else -> if (seen.isBlank()) "眼前有标识，还不能确定是哪家。" else "画面上像是「$seen」，还不能确定就是这家店。"
        }
    }
}
