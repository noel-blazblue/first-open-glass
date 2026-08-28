package com.glass.dining.shared.place

import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.Store

sealed class DestinationResolve {
    data class Unique(val place: PlaceProfile) : DestinationResolve()
    data class Ambiguous(val candidates: List<PlaceProfile>) : DestinationResolve()
    data class NotFound(val query: String, val reason: String) : DestinationResolve()
    data class NeedLocation(val query: String) : DestinationResolve()
}

object PlaceResolver {
    fun fromStore(store: Store): PlaceProfile {
        val ref = PlaceRef(
            id = store.id,
            name = store.shortName.ifBlank { store.name },
            address = store.address,
            lat = store.lat,
            lng = store.lng,
            distanceMeters = store.distanceMeters,
            source = if (store.catalogBacked) PlaceRef.SOURCE_CATALOG else store.source,
            storeId = store.id,
            floor = store.floor,
            category = store.category,
        )
        return PlaceProfile(
            ref = ref,
            rating = store.rating,
            avgCost = store.avgPrice,
            tel = store.phone,
            hoursToday = store.hours,
            businessArea = store.businessArea,
            tag = store.tags.joinToString(","),
            keytag = store.category,
        )
    }

    fun toStore(place: PlaceRef): Store = toStore(PlaceProfile(place))

    fun toStore(place: PlaceProfile): Store {
        val catalog = place.catalogBacked
        val tags = place.tag.split(",", "、").map { it.trim() }.filter { it.isNotBlank() }
        return Store(
            id = place.storeId.ifBlank { place.id },
            name = place.name,
            shortName = place.name.take(16),
            category = place.keytag.ifBlank { place.category },
            rating = place.rating,
            reviewCount = 0,
            avgPrice = place.avgCost,
            distanceMeters = place.distanceMeters,
            openNow = catalog,
            hours = place.hours,
            phone = place.tel,
            address = place.address,
            waitTables = 0,
            waitMinutes = 0,
            tags = tags,
            deals = emptyList(),
            signatures = emptyList(),
            suitable = emptyList(),
            hasPrivateRoom = false,
            answers = emptyMap(),
            lat = place.lat,
            lng = place.lng,
            floor = place.floor,
            source = place.source,
            catalogBacked = catalog,
            businessArea = place.businessArea,
        )
    }

    fun matchResult(place: PlaceProfile, others: List<PlaceProfile> = emptyList()): MatchResult {
        val store = toStore(place)
        val candidates = (listOf(place) + others).distinctBy { it.id }.map { toStore(it) }
        val dist = if (place.distanceMeters > 0) "大约${place.distanceMeters}米。" else ""
        val tts = if (place.catalogBacked) {
            store.shortName
        } else {
            "${place.name}。${place.address}$dist".trim()
        }
        return MatchResult(store, candidates, tts, 0.8f, place.source)
    }

    fun merge(catalog: List<Store>, pois: List<PlaceProfile>): List<PlaceProfile> {
        val local = catalog.map { fromStore(it) }
        val used = mutableSetOf<String>()
        val out = ArrayList<PlaceProfile>()
        for (store in local) {
            val poi = pois.firstOrNull { samePlace(store, it) }
            out.add(overlay(store, poi))
            if (poi != null) used.add(poi.id)
        }
        for (poi in pois) {
            if (poi.id in used) continue
            if (out.any { samePlace(it, poi) }) continue
            out.add(poi.copy(ref = poi.ref.copy(source = PlaceRef.SOURCE_AMAP)))
        }
        return out.sortedBy { if (it.distanceMeters > 0) it.distanceMeters else Int.MAX_VALUE }
    }

    fun resolve(
        query: String,
        sessionPlace: PlaceProfile?,
        catalog: List<Store>,
        nearby: List<PlaceProfile>,
        hasLocation: Boolean = true,
        searchAttempted: Boolean = false,
        searchError: String = "",
    ): DestinationResolve {
        val q = query.trim()
        if (q.isBlank()) return DestinationResolve.NotFound(q, "没有目的地名称")
        sessionPlace?.let { current ->
            if (nameHit(q, current.name)) return DestinationResolve.Unique(current)
        }
        val localHits = catalog.filter { store ->
            nameHit(q, store.name) || nameHit(q, store.shortName)
        }.map { fromStore(it) }
        if (localHits.size == 1) {
            val only = overlay(localHits.first(), nearby.firstOrNull { samePlace(localHits.first(), it) })
            return DestinationResolve.Unique(only)
        }
        if (localHits.size > 1) {
            return DestinationResolve.Ambiguous(localHits.take(5))
        }
        val poiHits = nearby.filter { nameHit(q, it.name) }.ifEmpty { nearby }
        if (poiHits.size == 1) return DestinationResolve.Unique(poiHits.first())
        if (poiHits.size > 1) return DestinationResolve.Ambiguous(poiHits.take(5))
        if (!searchAttempted) return DestinationResolve.NotFound(q, "need_search")
        if (!hasLocation && nearby.isEmpty()) return DestinationResolve.NeedLocation(q)
        if (searchError.isNotBlank()) return DestinationResolve.NotFound(q, searchError)
        return DestinationResolve.NotFound(q, "附近没有找到$q")
    }

    private fun overlay(local: PlaceProfile, poi: PlaceProfile?): PlaceProfile {
        if (poi == null) return local
        val lat = if (local.hasCoords) local.lat else poi.lat
        val lng = if (local.hasCoords) local.lng else poi.lng
        val address = local.address.ifBlank { poi.address }
        val distance = if (local.distanceMeters > 0) local.distanceMeters else poi.distanceMeters
        val ref = local.ref.copy(
            lat = lat,
            lng = lng,
            address = address,
            distanceMeters = distance,
            source = PlaceRef.SOURCE_CATALOG,
            floor = local.floor.ifBlank { poi.floor },
            category = local.category.ifBlank { poi.category },
        )
        return local.copy(
            ref = ref,
            rating = if (local.rating > 0) local.rating else poi.rating,
            avgCost = if (local.avgCost > 0) local.avgCost else poi.avgCost,
            tel = local.tel.ifBlank { poi.tel },
            hoursToday = local.hoursToday.ifBlank { poi.hoursToday },
            hoursWeek = local.hoursWeek.ifBlank { poi.hoursWeek },
            businessArea = local.businessArea.ifBlank { poi.businessArea },
            tag = local.tag.ifBlank { poi.tag },
            keytag = local.keytag.ifBlank { poi.keytag },
            alias = local.alias.ifBlank { poi.alias },
        )
    }

    private fun samePlace(a: PlaceProfile, b: PlaceProfile): Boolean {
        val ca = compact(a.name)
        val cb = compact(b.name)
        if (ca.length < 2 || cb.length < 2) return false
        return ca.contains(cb) || cb.contains(ca)
    }

    private fun nameHit(query: String, name: String): Boolean {
        val q = compact(query)
        val n = compact(name)
        if (q.length < 2 || n.length < 2) return false
        return n.contains(q) || q.contains(n)
    }

    private fun compact(value: String): String {
        return value.replace(Regex("[\\s\\p{Punct}的了店（）()]"), "")
    }
}
