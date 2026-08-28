package com.glass.dining.shared.catalog

import com.glass.dining.shared.model.Deal
import com.glass.dining.shared.model.Store
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

interface StoreCatalog {
    fun stores(): List<Store>
    fun storeById(id: String): Store? = stores().firstOrNull { it.id == id }
    fun isEmpty(): Boolean = stores().isEmpty()
}

class MemoryStoreCatalog(
    initial: List<Store> = emptyList(),
) : StoreCatalog {
    @Volatile private var items: List<Store> = initial

    override fun stores(): List<Store> = items

    fun replace(next: List<Store>) {
        items = next
    }
}

object StoreCatalogIds {
    const val FILE_NAME = "glass-stores.json"
    const val AUTHORITY = "com.glass.dining.store.catalog"
    const val PATH = "stores"
    const val COLUMN_JSON = "json"
    const val PERMISSION = "com.glass.dining.STORE_CATALOG"
    const val CONTENT_URI = "content://$AUTHORITY/$PATH"
    const val LOCAL_SCENE = "local"
}

object StoreGeo {
    private const val EARTH_M = 6_371_000.0

    fun hasCoords(lat: Double, lng: Double): Boolean {
        return kotlin.math.abs(lat) > 1e-6 || kotlin.math.abs(lng) > 1e-6
    }

    fun hasCoords(store: Store): Boolean = hasCoords(store.lat, store.lng)

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun withDistance(store: Store, originLat: Double?, originLng: Double?): Store {
        if (originLat == null || originLng == null || !hasCoords(store)) {
            return store.copy(distanceMeters = 0)
        }
        return store.copy(
            distanceMeters = haversineMeters(originLat, originLng, store.lat, store.lng).toInt(),
        )
    }

    fun applyDistances(stores: List<Store>, originLat: Double?, originLng: Double?): List<Store> {
        return stores.map { withDistance(it, originLat, originLng) }
            .sortedBy { if (it.distanceMeters > 0) it.distanceMeters else Int.MAX_VALUE }
    }
}

object StoreCatalogJson {
    fun encode(stores: List<Store>): String {
        val arr = JSONArray()
        stores.forEach { arr.put(storeToJson(it)) }
        val root = JSONObject()
        root.put("version", 1)
        root.put("stores", arr)
        return root.toString()
    }

    fun decode(raw: String): List<Store> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root = JSONObject(if (trimmed.startsWith("[")) JSONObject().put("stores", JSONArray(trimmed)).toString() else trimmed)
        val arr = when {
            root.has("stores") -> root.optJSONArray("stores")
            else -> null
        } ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(jsonToStore(obj))
            }
        }
    }

    fun storeToJson(store: Store): JSONObject {
        return JSONObject().apply {
            put("id", store.id)
            put("name", store.name)
            put("shortName", store.shortName)
            put("category", store.category)
            put("rating", store.rating)
            put("reviewCount", store.reviewCount)
            put("avgPrice", store.avgPrice)
            put("openNow", store.openNow)
            put("hours", store.hours)
            put("phone", store.phone)
            put("address", store.address)
            put("waitTables", store.waitTables)
            put("waitMinutes", store.waitMinutes)
            put("hasPrivateRoom", store.hasPrivateRoom)
            put("lat", store.lat)
            put("lng", store.lng)
            put("floor", store.floor)
            put("source", store.source)
            put("catalogBacked", store.catalogBacked)
            if (store.businessArea.isNotBlank()) put("businessArea", store.businessArea)
            put("tags", JSONArray(store.tags))
            put("signatures", JSONArray(store.signatures))
            put("suitable", JSONArray(store.suitable))
            val deals = JSONArray()
            store.deals.forEach { deal ->
                val item = JSONObject()
                item.put("title", deal.title)
                item.put("price", deal.price)
                item.put("original", deal.original)
                deals.put(item)
            }
            put("deals", deals)
        }
    }

    fun jsonToStore(obj: JSONObject): Store {
        return Store(
            id = obj.optString("id"),
            name = obj.optString("name"),
            shortName = obj.optString("shortName").ifBlank { obj.optString("name") },
            category = obj.optString("category"),
            rating = obj.optDouble("rating"),
            reviewCount = obj.optInt("reviewCount"),
            avgPrice = obj.optInt("avgPrice"),
            distanceMeters = 0,
            openNow = obj.optBoolean("openNow", true),
            hours = obj.optString("hours"),
            phone = obj.optString("phone"),
            address = obj.optString("address"),
            waitTables = obj.optInt("waitTables"),
            waitMinutes = obj.optInt("waitMinutes"),
            tags = obj.optJSONArray("tags").toStringList(),
            deals = obj.optJSONArray("deals").toDeals(),
            signatures = obj.optJSONArray("signatures").toStringList(),
            suitable = obj.optJSONArray("suitable").toStringList(),
            hasPrivateRoom = obj.optBoolean("hasPrivateRoom"),
            answers = emptyMap(),
            lat = obj.optDouble("lat"),
            lng = obj.optDouble("lng"),
            floor = obj.optString("floor"),
            source = obj.optString("source").ifBlank { "catalog" },
            catalogBacked = obj.optBoolean("catalogBacked", obj.optString("source") != "amap"),
            businessArea = obj.optString("businessArea"),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val value = optString(i).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun JSONArray?.toDeals(): List<Deal> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val item = optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                add(
                    Deal(
                        title = title,
                        price = item.optInt("price"),
                        original = item.optInt("original"),
                    ),
                )
            }
        }
    }
}

object StoreFixtures {
    fun twoNearby(): List<Store> {
        return listOf(
            Store(
                id = "store_hotpot",
                name = "青田小馆火锅",
                shortName = "青田小馆",
                category = "火锅",
                rating = 4.6,
                reviewCount = 12,
                avgPrice = 98,
                distanceMeters = 0,
                openNow = true,
                hours = "11:00-22:00",
                phone = "",
                address = "",
                waitTables = 2,
                waitMinutes = 10,
                tags = listOf("火锅"),
                deals = listOf(Deal("双人餐", 198, 268)),
                signatures = listOf("番茄锅", "滑牛肉"),
                suitable = emptyList(),
                hasPrivateRoom = false,
                answers = emptyMap(),
                lat = 31.2304,
                lng = 121.4737,
            ),
            Store(
                id = "store_tea",
                name = "临河茶铺",
                shortName = "临河茶铺",
                category = "茶",
                rating = 4.4,
                reviewCount = 8,
                avgPrice = 32,
                distanceMeters = 0,
                openNow = true,
                hours = "10:00-21:00",
                phone = "",
                address = "",
                waitTables = 0,
                waitMinutes = 0,
                tags = listOf("茶"),
                deals = emptyList(),
                signatures = listOf("烤奶"),
                suitable = emptyList(),
                hasPrivateRoom = false,
                answers = emptyMap(),
                lat = 31.2310,
                lng = 121.4745,
            ),
        )
    }
}
