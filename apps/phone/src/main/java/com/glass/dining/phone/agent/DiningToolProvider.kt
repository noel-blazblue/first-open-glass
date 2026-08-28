package com.glass.dining.phone.agent

import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.place.PlaceFacts
import com.glass.dining.shared.place.PlaceResolver
import com.glass.dining.shared.engine.StoreVision
import com.glass.dining.shared.model.ActiveSkill
import org.json.JSONObject

class DiningToolProvider(private val world: PhoneWorld) {
    fun register(registry: ToolRegistry) {
        registry.register(AgentToolCatalog.RECOMMEND, ::recommend)
        registry.register(AgentToolCatalog.SELECT_STORE, ::select)
        registry.register(AgentToolCatalog.ASK_STORE, ::askStore)
    }

    private fun recommend(args: JSONObject): String {
        world.reloadCatalogWithGps()
        if (world.session.catalog.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "empty_catalog")
                .put("hint", "search_nearby_places")
                .put("message", "本地餐饮目录是空的，去搜附近公开地点")
                .toString()
        }
        if (world.session.activeSkill == ActiveSkill.NAV) {
            world.stopNav(silent = true)
        }
        val query = args.optString("query").ifBlank { "附近好吃的" }
        val text = if (args.optBoolean("avoid_queue", false)) "$query 不要排队" else query
        val result = world.session.recommend(text)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "empty_catalog")
                .put("hint", "search_nearby_places")
                .put("message", "本地目录没有合适的店，去搜附近公开地点")
                .toString()
        world.recommendedThisTurn = true
        world.rememberSpokenStore()
        val others = result.candidates.drop(1).joinToString("、") { it.shortName }
        val caption = if (others.isBlank()) "" else "还有$others"
        val cached = world.latestPlaces().firstOrNull { it.id == result.store.id || it.storeId == result.store.id }
        world.publishStore(cached ?: PlaceResolver.fromStore(result.store), result.tts, "recommend", caption)
        return facts(result).put("message", result.tts).toString()
    }

    private fun select(args: JSONObject): String {
        if (world.session.activeSkill == ActiveSkill.NAV) {
            world.stopNav(silent = true)
        }
        val storeId = args.optString("store_id").ifBlank { args.optString("place_id") }
        val name = args.optString("name")
        val index = args.optInt("index", 0)
        val result = when {
            storeId.isNotBlank() -> {
                world.session.select(storeId)
                    ?: world.latestPlaces().firstOrNull { it.id == storeId || it.storeId == storeId }
                        ?.let { world.bindPlace(it) }
            }
            index in 1..9 -> {
                val picked = world.session.candidates.getOrNull(index - 1)
                    ?: world.latestPlaces().getOrNull(index - 1)?.let { return finishSelect(world.bindPlace(it)) }
                    ?: return JSONObject().put("ok", false).put("error", "没有第${index}家").toString()
                world.session.select(picked.id)
            }
            name.isNotBlank() -> {
                val fromCand = world.session.candidates.firstOrNull {
                    it.shortName.contains(name) || it.name.contains(name)
                }
                val catalog = StoreVision.matchStore(world.session.catalog.stores(), name)
                val poi = world.latestPlaces().firstOrNull { it.name.contains(name) }
                when {
                    fromCand != null -> world.session.select(fromCand.id)
                    catalog != null -> world.session.select(catalog.id)
                    poi != null -> world.bindPlace(poi)
                    else -> return JSONObject()
                        .put("ok", false)
                        .put("error", "没找到$name")
                        .put("hint", "search_nearby_places")
                        .toString()
                }
            }
            else -> world.session.lastMatch
                ?: return JSONObject().put("ok", false).put("error", "还没有候选地点").toString()
        } ?: return JSONObject().put("ok", false).put("error", "没找到这家").toString()
        return finishSelect(result)
    }

    private fun finishSelect(result: com.glass.dining.shared.model.MatchResult): String {
        world.selectedThisTurn = true
        world.rememberSpokenStore()
        world.publishMatch(result, "选定")
        return facts(result).put("message", "已选定${result.store.shortName}，这是当前查看门店，不是用户所在位置").toString()
    }

    private fun askStore(args: JSONObject): String {
        val store = world.session.currentStore
            ?: return JSONObject().put("ok", false).put("error", "还没有确认的地点").toString()
        val question = args.optString("question").ifBlank { world.question }
        val result = world.session.ask(question)
        val json = PlaceFacts.write(JSONObject().put("ok", true), store)
            .put("question", question)
        val message = if (store.catalogBacked) result.answer else PlaceFacts.spokenSummary(store)
        return json.put("message", message).toString()
    }

    companion object {
        fun facts(result: com.glass.dining.shared.model.MatchResult): JSONObject {
            return PlaceFacts.write(JSONObject().put("ok", true), result.store)
                .put("candidates", result.candidates.joinToString("、") { it.shortName })
        }
    }
}
