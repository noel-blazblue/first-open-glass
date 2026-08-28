package com.glass.dining.phone.agent

import android.util.Log
import com.glass.dining.phone.nav.AmapPlaceSearch
import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.place.DestinationResolve
import com.glass.dining.shared.place.PlaceFacts
import com.glass.dining.shared.place.PlaceProfile
import com.glass.dining.shared.place.PlaceResolver
import com.glass.dining.shared.engine.StoreVision
import com.glass.dining.shared.model.ActiveSkill
import org.json.JSONArray
import org.json.JSONObject

class NavigationToolProvider(private val world: PhoneWorld) {
    fun register(registry: ToolRegistry) {
        registry.register(AgentToolCatalog.SEARCH_NEARBY, ::searchNearby)
        registry.register(AgentToolCatalog.RESOLVE_DEST, ::resolve)
        registry.register(AgentToolCatalog.START_NAV, ::startNav)
        registry.register(AgentToolCatalog.STOP_NAV) { stopNav() }
    }

    private fun searchNearby(args: JSONObject): String {
        val keyword = args.optString("keyword").ifBlank { return error("no_query", "请说要找的地点") }
        val radius = args.optInt("radius", 3_000).coerceIn(200, 10_000)
        if (!world.hasGpsPermission()) {
            return error("need_location", "没有定位权限，请在系统设置打开精确位置")
        }
        val origin = world.gps() ?: world.awaitGps(8_000)
        Log.i(
            TAG,
            "searchNearby keyword=$keyword gps=${origin != null} amap=${world.amapKey().isNotBlank()}",
        )
        if (origin == null) {
            return error("need_location", "暂时没拿到定位，请到开阔处再试，或告诉我在哪个区域。")
        }
        if (world.amapKey().isBlank()) {
            return error("no_key", "没配高德密钥，在 ai.env 写 AMAP_WEB_KEY", retryable = false)
        }
        val result = AmapPlaceSearch.around(origin, keyword, world.amapKey(), radius)
        if (!result.ok) {
            return error(result.error.ifBlank { "amap_error" }, result.message.ifBlank { "高德搜索失败" })
        }
        val merged = PlaceResolver.merge(world.session.catalog.stores(), result.places)
        world.rememberPlaces(merged)
        val arr = JSONArray()
        merged.take(8).forEach { place ->
            arr.put(placeJson(place))
        }
        return JSONObject()
            .put("ok", true)
            .put("keyword", keyword)
            .put("count", merged.size)
            .put("places", arr)
            .put("message", summarizePlaces(merged, keyword))
            .put("note", "公开地点没有排队和优惠")
            .toString()
    }

    private fun resolve(args: JSONObject): String {
        val name = args.optString("name").ifBlank { args.optString("place_id") }
        if (name.isBlank()) return error("no_query", "请说目的地")
        val sessionPlace = world.session.currentStore?.let { PlaceResolver.fromStore(it) }
        val cached = world.latestPlaces()
        Log.i(
            TAG,
            "resolve dest=$name last=${world.gps() != null} perm=${world.hasGpsPermission()} " +
                "amap=${world.amapKey().isNotBlank()} cached=${cached.size}",
        )
        val first = PlaceResolver.resolve(
            query = name,
            sessionPlace = sessionPlace,
            catalog = world.session.catalog.stores(),
            nearby = cached,
            hasLocation = world.gps() != null || world.hasGpsPermission(),
            searchAttempted = cached.isNotEmpty(),
        )
        if (first is DestinationResolve.NotFound && first.reason == "need_search") {
            val searched = JSONObject(searchNearby(JSONObject().put("keyword", name)))
            if (!searched.optBoolean("ok")) return searched.toString()
            val again = PlaceResolver.resolve(
                query = name,
                sessionPlace = sessionPlace,
                catalog = world.session.catalog.stores(),
                nearby = world.latestPlaces(),
                hasLocation = world.gps() != null,
                searchAttempted = true,
            )
            return resolveJson(again)
        }
        return resolveJson(first)
    }

    private fun startNav(args: JSONObject): String {
        val named = args.optString("name")
        val placeId = args.optString("place_id").ifBlank { args.optString("store_id") }
        val index = args.optInt("index", 0)
        if (placeId.isNotBlank() || named.isNotBlank() || index in 1..9) {
            val picked = pickPlace(placeId, named, index) ?: run {
                val resolved = JSONObject(resolve(JSONObject().put("name", named.ifBlank { placeId })))
                if (!resolved.optBoolean("unique")) return resolved.toString()
                world.latestPlaces().firstOrNull { it.id == resolved.optString("place_id") }
                    ?: return resolved.toString()
            }
            world.bindPlace(picked)
            world.selectedThisTurn = true
        }
        val store = world.session.currentStore
        if (store == null) {
            return error("no_store", "还没确定目的地。可以说地点名，我先搜索附近。")
        }
        if (world.recommendedThisTurn && !world.selectedThisTurn) {
            return error("no_store", "先问去哪家，选定后才能导航")
        }
        val spoken = args.optString("current_floor").ifBlank { world.currentFloor }.ifBlank { world.spokenFloor }
        val failed = world.startNav(spoken)
        if (failed != null) {
            return error("nav_failed", failed)
        }
        return JSONObject()
            .put("ok", true)
            .put("store", store.name)
            .put("shortName", store.shortName)
            .put("source", store.source)
            .put("message", "开始前往${store.shortName}")
            .toString()
    }

    private fun stopNav(): String {
        val was = world.session.activeSkill == ActiveSkill.NAV
        world.stopNav(silent = true)
        return JSONObject()
            .put("ok", true)
            .put("stopped", was)
            .put("message", if (was) "导航停了" else "当时没在导航")
            .toString()
    }

    private fun pickPlace(placeId: String, name: String, index: Int): PlaceProfile? {
        if (placeId.isNotBlank()) {
            world.latestPlaces().firstOrNull { it.id == placeId || it.storeId == placeId }?.let { return it }
            world.session.catalog.storeById(placeId)?.let { return PlaceResolver.fromStore(it) }
        }
        if (index in 1..9) {
            world.latestPlaces().getOrNull(index - 1)?.let { return it }
            world.session.candidates.getOrNull(index - 1)?.let { return PlaceResolver.fromStore(it) }
        }
        if (name.isNotBlank()) {
            world.latestPlaces().firstOrNull { it.name.contains(name) }?.let { return it }
            StoreVision.matchStore(world.session.catalog.stores(), name)?.let { return PlaceResolver.fromStore(it) }
        }
        return world.session.currentStore?.let { PlaceResolver.fromStore(it) }
    }

    private fun resolveJson(resolved: DestinationResolve): String {
        return when (resolved) {
            is DestinationResolve.Unique -> {
                world.rememberPlaces(listOf(resolved.place))
                placeJson(resolved.place)
                    .put("ok", true)
                    .put("unique", true)
                    .put("place_id", resolved.place.id)
                    .put("message", "找到${resolved.place.name}")
                    .toString()
            }
            is DestinationResolve.Ambiguous -> {
                world.rememberPlaces(resolved.candidates)
                val arr = JSONArray()
                resolved.candidates.forEach { arr.put(placeJson(it)) }
                JSONObject()
                    .put("ok", true)
                    .put("unique", false)
                    .put("need_disambiguation", true)
                    .put("places", arr)
                    .put("message", "有多家${resolved.candidates.joinToString("、") { it.name + distance(it) }}，去哪家？")
                    .toString()
            }
            is DestinationResolve.NeedLocation -> error("need_location", "需要定位才能找${resolved.query}。请打开定位或告诉我区域。")
            is DestinationResolve.NotFound -> error(resolved.reason, resolved.reason.let { reason ->
                if (reason == "need_search") "还没搜附近，需要先搜索公开地点" else "附近没有找到${resolved.query}"
            })
        }
    }

    private fun placeJson(place: PlaceProfile): JSONObject {
        return PlaceFacts.write(JSONObject(), place)
    }

    private fun summarizePlaces(places: List<PlaceProfile>, keyword: String): String {
        if (places.isEmpty()) return "附近没有找到$keyword"
        val first = places.first()
        val dist = distance(first)
        return if (places.size == 1) {
            "找到${first.name}$dist"
        } else {
            "附近有${places.take(3).joinToString("、") { it.name + distance(it) }}"
        }
    }

    private fun distance(place: PlaceProfile): String {
        return if (place.distanceMeters > 0) "约${place.distanceMeters}米" else ""
    }

    private fun error(code: String, message: String, retryable: Boolean = code == "amap_error"): String {
        return JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("message", message)
            .put("retryable", retryable)
            .toString()
    }

    companion object {
        private const val TAG = "GlassDiningPhone"
    }
}
