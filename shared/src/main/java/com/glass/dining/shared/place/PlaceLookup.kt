package com.glass.dining.shared.place

import com.glass.dining.shared.catalog.StoreVision
import com.glass.dining.shared.model.Store

/** 按模型给出的指向查找地点，不绑定、不猜第一家。 */
object PlaceLookup {
    fun find(
        placeId: String = "",
        name: String = "",
        index: Int = 0,
        catalog: List<Store>,
        nearby: List<PlaceProfile>,
        candidates: List<Store> = emptyList(),
    ): PlaceLookupResult {
        if (placeId.isBlank() && name.isBlank() && index !in 1..9) {
            return PlaceLookupResult.NeedPointer
        }
        if (placeId.isNotBlank()) {
            nearby.firstOrNull { it.id == placeId || it.storeId == placeId }?.let {
                return PlaceLookupResult.Found(it)
            }
            catalog.firstOrNull { it.id == placeId }?.let {
                return PlaceLookupResult.Found(PlaceResolver.fromStore(it))
            }
        }
        if (index in 1..9) {
            nearby.getOrNull(index - 1)?.let { return PlaceLookupResult.Found(it) }
            candidates.getOrNull(index - 1)?.let { return PlaceLookupResult.Found(PlaceResolver.fromStore(it)) }
            return PlaceLookupResult.Missing("没有第${index}家")
        }
        if (name.isNotBlank()) {
            nearby.firstOrNull { it.name.contains(name) }?.let { return PlaceLookupResult.Found(it) }
            StoreVision.matchStore(catalog, name)?.let { return PlaceLookupResult.Found(PlaceResolver.fromStore(it)) }
            val hits = nearby.filter { it.name.contains(name) }
            if (hits.size > 1) return PlaceLookupResult.Ambiguous(hits.take(5))
        }
        return PlaceLookupResult.Missing("没找到$name".ifBlank { "没找到这家" })
    }
}

sealed class PlaceLookupResult {
    data class Found(val place: PlaceProfile) : PlaceLookupResult()
    data class Ambiguous(val places: List<PlaceProfile>) : PlaceLookupResult()
    data class Missing(val message: String) : PlaceLookupResult()
    data object NeedPointer : PlaceLookupResult()
}
