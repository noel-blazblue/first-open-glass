package com.glass.dining.shared.place

object AmapPlaceJson {
    fun parseAround(raw: String): AroundResult {
        val status = field(raw, "status")
        if (status != "1") {
            val info = field(raw, "info").ifBlank { field(raw, "infocode") }
            return AroundResult(ok = false, error = "amap_error", message = "公开地点搜索失败：$info")
        }
        val block = arrayBody(raw, "pois")
        val places = splitObjects(block).mapNotNull { item ->
            val name = field(item, "name").trim()
            if (name.isBlank()) return@mapNotNull null
            val location = field(item, "location")
            val parts = location.split(",")
            val lng = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val id = field(item, "id").ifBlank { "amap:$name:$lat:$lng" }
            val ref = PlaceRef(
                id = id,
                name = name,
                address = field(item, "address"),
                lat = lat,
                lng = lng,
                distanceMeters = field(item, "distance").toDoubleOrNull()?.toInt() ?: 0,
                source = PlaceRef.SOURCE_AMAP,
                floor = field(item, "truefloor").ifBlank { field(item, "floor") },
                category = field(item, "type"),
            )
            PlaceProfile(
                ref = ref,
                rating = field(item, "rating").toDoubleOrNull() ?: 0.0,
                avgCost = field(item, "cost").toDoubleOrNull()?.toInt() ?: 0,
                tel = field(item, "tel"),
                hoursToday = field(item, "opentime_today"),
                hoursWeek = field(item, "opentime_week"),
                businessArea = field(item, "business_area"),
                tag = field(item, "tag"),
                keytag = field(item, "keytag").ifBlank { field(item, "rectag") },
                alias = field(item, "alias"),
                photoUrl = firstPhotoUrl(item),
            )
        }
        return AroundResult(ok = true, places = places)
    }

    private fun firstPhotoUrl(item: String): String {
        val photos = arrayBody(item, "photos")
        val first = splitObjects(photos).firstOrNull() ?: return ""
        return field(first, "url")
    }

    private fun field(json: String, key: String): String {
        val quoted = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
        if (quoted != null) return quoted
        return Regex("\"$key\"\\s*:\\s*([^,}\\]]+)").find(json)?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun arrayBody(json: String, key: String): String {
        val startToken = "\"$key\""
        val keyAt = json.indexOf(startToken)
        if (keyAt < 0) return ""
        val bracket = json.indexOf('[', keyAt)
        if (bracket < 0) return ""
        var depth = 0
        for (i in bracket until json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return json.substring(bracket + 1, i)
                }
            }
        }
        return ""
    }

    private fun splitObjects(body: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        body.forEachIndexed { i, ch ->
            when (ch) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(body.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }

    data class AroundResult(
        val ok: Boolean,
        val places: List<PlaceProfile> = emptyList(),
        val error: String = "",
        val message: String = "",
    )
}
