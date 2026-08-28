package com.glass.dining.shared.place

import org.json.JSONObject

data class StoreHudPayload(
    val place: PlaceProfile,
    val caption: String = "",
)

object PlaceWire {
    fun encode(place: PlaceProfile, caption: String = ""): String {
        val obj = PlaceFacts.write(JSONObject(), place)
        if (caption.isNotBlank()) obj.put("caption", caption)
        return obj.toString()
    }

    fun decode(raw: String?): StoreHudPayload? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val name = obj.optString("name").trim()
            if (name.isBlank()) return null
            val catalog = obj.optBoolean("catalog_backed")
            val source = when {
                catalog -> PlaceRef.SOURCE_CATALOG
                else -> obj.optString("source").ifBlank { PlaceRef.SOURCE_AMAP }
            }
            val category = obj.optString("category")
            val ref = PlaceRef(
                id = obj.optString("place_id").ifBlank { obj.optString("id") }.ifBlank { name },
                name = name,
                address = obj.optString("address"),
                lat = obj.optDouble("lat", 0.0),
                lng = obj.optDouble("lng", 0.0),
                distanceMeters = obj.optInt("distance_meters"),
                source = source,
                storeId = obj.optString("store_id"),
                floor = obj.optString("floor"),
                category = category,
            )
            val place = PlaceProfile(
                ref = ref,
                rating = obj.optDouble("rating", 0.0),
                avgCost = obj.optInt("avg_cost"),
                tel = obj.optString("tel"),
                hoursToday = obj.optString("hours_today"),
                hoursWeek = obj.optString("hours_week"),
                businessArea = obj.optString("business_area"),
                tag = obj.optString("tag"),
                keytag = obj.optString("keytag").ifBlank { category },
                alias = obj.optString("alias"),
                photoUrl = obj.optString("photo_url"),
                waitMinutes = if (catalog) obj.optInt("wait_minutes") else 0,
                dealLine = if (catalog) obj.optString("deal_line") else "",
            )
            StoreHudPayload(place, obj.optString("caption"))
        } catch (_: Exception) {
            null
        }
    }
}
