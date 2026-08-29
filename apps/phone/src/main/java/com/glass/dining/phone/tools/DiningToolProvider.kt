package com.glass.dining.phone.tools

import com.glass.dining.phone.agent.PhoneWorld
import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.catalog.StoreVision
import com.glass.dining.shared.place.PlaceFacts
import com.glass.dining.shared.place.PlaceLookup
import com.glass.dining.shared.place.PlaceLookupResult
import com.glass.dining.shared.place.PlaceResolver
import com.glass.dining.shared.session.DismissReason
import org.json.JSONArray
import org.json.JSONObject

class DiningToolProvider(private val world: PhoneWorld) {
    fun register(registry: ToolRegistry) {
        registry.register(AgentToolCatalog.RECOMMEND, ::recommend)
        registry.register(AgentToolCatalog.SELECT_STORE, ::select)
        registry.register(AgentToolCatalog.GET_PLACE_DETAILS, ::getPlaceDetails)
    }

    private fun recommend(args: JSONObject): String {
        world.reloadCatalogWithGps()
        if (world.session.catalog.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "empty_catalog")
                .put("hint", "search_nearby_places")
                .put("message", "附近还没有录入的店")
                .toString()
        }
        if (world.session.navigating) world.dismiss(DismissReason.REPLACE)
        val query = args.optString("query").ifBlank { "附近好吃的" }
        val text = if (args.optBoolean("avoid_queue", false)) "$query 不要排队" else query
        val result = world.session.recommend(text)
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "empty_catalog")
                .put("hint", "search_nearby_places")
                .put("message", "录入的店里没有符合条件的")
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
        if (world.session.navigating) world.dismiss(DismissReason.REPLACE)
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
            else -> world.session.commitViewing()
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

    private fun getPlaceDetails(args: JSONObject): String {
        val looked = PlaceLookup.find(
            placeId = args.optString("place_id"),
            name = args.optString("name"),
            index = args.optInt("index", 0),
            catalog = world.session.catalog.stores(),
            nearby = world.latestPlaces(),
            candidates = world.session.candidates,
            viewing = world.session.viewingStore?.let { PlaceResolver.fromStore(it) },
        )
        return when (looked) {
            PlaceLookupResult.NeedPointer -> JSONObject()
                .put("ok", false)
                .put("error", "need_which_place")
                .put("message", "请指出是哪一家：店名、place_id 或第几家")
                .toString()
            is PlaceLookupResult.Missing -> JSONObject()
                .put("ok", false)
                .put("error", "not_found")
                .put("message", looked.message)
                .put("hint", "search_nearby_places")
                .toString()
            is PlaceLookupResult.Ambiguous -> {
                val arr = JSONArray()
                looked.places.forEach { arr.put(PlaceFacts.write(JSONObject(), it)) }
                JSONObject()
                    .put("ok", true)
                    .put("unique", false)
                    .put("places", arr)
                    .put("message", "有多家，问的是哪家？")
                    .toString()
            }
            is PlaceLookupResult.Found -> {
                val merged = PlaceResolver.merge(world.session.catalog.stores(), listOf(looked.place) + world.latestPlaces())
                    .firstOrNull { it.id == looked.place.id || it.name == looked.place.name }
                    ?: looked.place
                world.session.viewPlace(merged)
                world.publishStore(merged, PlaceFacts.spokenSummary(PlaceResolver.toStore(merged)), "get_place_details")
                PlaceFacts.write(JSONObject().put("ok", true).put("bound", false), merged)
                    .put("message", PlaceFacts.spokenSummary(PlaceResolver.toStore(merged)))
                    .toString()
            }
        }
    }

    companion object {
        fun facts(result: com.glass.dining.shared.model.MatchResult): JSONObject {
            return PlaceFacts.write(JSONObject().put("ok", true), result.store)
                .put("candidates", result.candidates.joinToString("、") { it.shortName })
        }
    }
}
