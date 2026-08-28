package com.glass.dining.shared.place

import com.glass.dining.shared.model.Store
import org.json.JSONObject

object PlaceFacts {
    data class CardLines(
        val title: String,
        val meta: String = "",
        val wait: String = "",
        val extra: String = "",
    )

    fun hoursOf(place: PlaceProfile): String = place.hours

    fun contextFacts(place: PlaceProfile): String {
        return entries(place).joinToString("；") { "${it.first}=${it.second}" }
    }

    fun listLabel(place: PlaceProfile): String {
        val bits = buildList {
            add(place.name)
            if (place.rating > 0) add("${trimRating(place.rating)}分")
            if (place.avgCost > 0) add("人均${place.avgCost}")
        }
        return bits.joinToString(" ")
    }

    fun spokenSummary(store: Store): String {
        val bits = buildList {
            add(store.shortName.ifBlank { store.name })
            if (store.rating > 0) add("评分${trimRating(store.rating)}")
            if (store.avgPrice > 0) add("人均大约${store.avgPrice}元")
            if (store.hours.isNotBlank()) add("营业时间${store.hours}")
            if (store.phone.isNotBlank()) add("电话${store.phone}")
            val where = listOf(store.businessArea, store.floor, store.address)
                .filter { it.isNotBlank() }
                .joinToString("")
            if (where.isNotBlank()) add("在$where")
            if (store.distanceMeters > 0) add("大约${store.distanceMeters}米")
            if (store.tags.isNotEmpty()) add(store.tags.joinToString("、"))
        }
        return bits.joinToString("，") + "。"
    }

    fun write(obj: JSONObject, place: PlaceProfile): JSONObject {
        obj.put("place_id", place.id)
        obj.put("name", place.name)
        obj.put("address", place.address)
        obj.put("distance_meters", place.distanceMeters)
        obj.put("source", place.source)
        obj.put("catalog_backed", place.catalogBacked)
        obj.put("lat", place.lat)
        obj.put("lng", place.lng)
        putIf(obj, "floor", place.floor)
        putIf(obj, "category", place.keytag.ifBlank { place.category })
        putIf(obj, "business_area", place.businessArea)
        putIf(obj, "tel", place.tel)
        putIf(obj, "hours_today", place.hoursToday)
        putIf(obj, "hours_week", place.hoursWeek)
        putIf(obj, "tag", place.tag)
        putIf(obj, "alias", place.alias)
        if (place.rating > 0) obj.put("rating", place.rating)
        if (place.avgCost > 0) obj.put("avg_cost", place.avgCost)
        return obj
    }

    fun write(obj: JSONObject, store: Store): JSONObject {
        obj.put("store_id", store.id)
        obj.put("place_id", store.id)
        obj.put("store", store.name)
        obj.put("shortName", store.shortName)
        obj.put("address", store.address)
        obj.put("source", store.source)
        obj.put("catalog_backed", store.catalogBacked)
        putIf(obj, "floor", store.floor)
        putIf(obj, "category", store.category)
        putIf(obj, "business_area", store.businessArea)
        putIf(obj, "phone", store.phone)
        putIf(obj, "hours", store.hours)
        if (store.rating > 0) obj.put("rating", store.rating)
        if (store.avgPrice > 0) obj.put("avgPrice", store.avgPrice)
        if (store.distanceMeters > 0) obj.put("distance_meters", store.distanceMeters)
        if (store.tags.isNotEmpty()) obj.put("tags", store.tags.joinToString("、"))
        if (store.catalogBacked) {
            obj.put("waitMinutes", store.waitMinutes)
            obj.put("openNow", store.openNow)
            if (store.signatures.isNotEmpty()) obj.put("signatures", store.signatures.joinToString("、"))
            if (store.deals.isNotEmpty()) {
                obj.put("deals", store.deals.joinToString("；") { "${it.title}现价${it.price}" })
            }
        }
        return obj
    }

    fun listCard(store: Store, extra: String? = null): CardLines {
        val lines = pack(
            store.shortName.ifBlank { store.name },
            distanceCategory(store),
            scoreCost(store),
            extra.orEmpty().ifBlank { areaFloor(store) }.ifBlank { store.address },
        )
        return CardLines(lines[0], lines[1], lines[2], lines[3])
    }

    fun detailCard(store: Store): CardLines {
        val lines = pack(
            store.shortName.ifBlank { store.name },
            scoreCost(store),
            hoursLine(store.hours),
            areaFloor(store).ifBlank { store.address }.ifBlank { distanceOnly(store) },
        )
        return CardLines(lines[0], lines[1], lines[2], lines[3])
    }

    private fun entries(place: PlaceProfile): List<Pair<String, String>> {
        return buildList {
            if (place.rating > 0) add("评分" to trimRating(place.rating))
            if (place.avgCost > 0) add("人均" to place.avgCost.toString())
            place.hours.takeIf { it.isNotBlank() }?.let { add("营业时间" to it) }
            putPair("电话", place.tel)
            putPair("楼层", place.floor)
            putPair("商圈", place.businessArea)
            putPair("地址", place.address)
            putPair("品类", place.keytag.ifBlank { place.category })
            putPair("特色", place.tag)
            if (place.distanceMeters > 0) add("距离米" to place.distanceMeters.toString())
        }
    }

    private fun MutableList<Pair<String, String>>.putPair(label: String, value: String) {
        if (value.isNotBlank()) add(label to value)
    }

    private fun pack(vararg candidates: String): List<String> {
        val kept = candidates.map { it.trim() }.filter { it.isNotBlank() }.take(4)
        return (kept + List(4) { "" }).take(4)
    }

    private fun scoreCost(store: Store): String {
        return buildList {
            if (store.rating > 0) add("${trimRating(store.rating)}分")
            if (store.avgPrice > 0) add("人均${store.avgPrice}")
        }.joinToString(" · ")
    }

    private fun distanceCategory(store: Store): String {
        val dist = distanceOnly(store)
        val kind = store.category
        return when {
            dist.isNotBlank() && kind.isNotBlank() -> "$dist · $kind"
            else -> dist.ifBlank { kind }
        }
    }

    private fun distanceOnly(store: Store): String {
        return if (store.distanceMeters > 0) "约${store.distanceMeters}米" else ""
    }

    private fun hoursLine(hours: String): String {
        if (hours.isBlank()) return ""
        if (hours.startsWith("今日") || hours.contains("周")) return hours
        return "今日 $hours"
    }

    private fun areaFloor(store: Store): String {
        return listOf(store.businessArea, store.floor)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun trimRating(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }

    private fun putIf(obj: JSONObject, key: String, value: String) {
        if (value.isNotBlank()) obj.put(key, value)
    }
}
