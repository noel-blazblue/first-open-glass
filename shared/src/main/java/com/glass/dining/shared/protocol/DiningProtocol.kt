package com.glass.dining.shared.protocol

import com.glass.dining.shared.model.Deal
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.QaResult
import com.glass.dining.shared.model.Store
import com.glass.dining.shared.model.StoreSummary
import org.json.JSONArray
import org.json.JSONObject

object DiningIds {
    const val CLIENT_ID = "GlassDining"
    const val PHONE_CLIENT_ID = "GlassDiningPhone"
}

object DiningMsgType {
    const val LOOK = "LOOK"
    const val STORE_RESULT = "STORE_RESULT"
    const val NEXT = "NEXT"
    const val ASK = "ASK"
    const val ANSWER = "ANSWER"
    const val TTS = "TTS"
    const val SELECT = "SELECT"
}

data class DiningMessage(
    val type: String,
    val sceneId: String? = null,
    val storeId: String? = null,
    val question: String? = null,
    val answer: String? = null,
    val tts: String? = null,
    val imageBase64: String? = null,
    val confidence: Float? = null,
    val store: Store? = null,
    val candidates: List<StoreSummary>? = null,
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("type", type)
        sceneId?.let { obj.put("sceneId", it) }
        storeId?.let { obj.put("storeId", it) }
        question?.let { obj.put("question", it) }
        answer?.let { obj.put("answer", it) }
        tts?.let { obj.put("tts", it) }
        imageBase64?.let { obj.put("imageBase64", it) }
        confidence?.let { obj.put("confidence", it.toDouble()) }
        store?.let { obj.put("store", storeToJson(it)) }
        candidates?.let { list ->
            val arr = JSONArray()
            list.forEach { arr.put(summaryToJson(it)) }
            obj.put("candidates", arr)
        }
        return obj.toString()
    }

    companion object {
        fun parse(raw: String): DiningMessage? {
            return try {
                val obj = JSONObject(raw)
                val type = obj.optString("type").ifBlank { return null }
                DiningMessage(
                    type = type,
                    sceneId = obj.optStringOrNull("sceneId"),
                    storeId = obj.optStringOrNull("storeId"),
                    question = obj.optStringOrNull("question"),
                    answer = obj.optStringOrNull("answer"),
                    tts = obj.optStringOrNull("tts"),
                    imageBase64 = obj.optStringOrNull("imageBase64"),
                    confidence = if (obj.has("confidence")) obj.optDouble("confidence").toFloat() else null,
                    store = obj.optJSONObject("store")?.let { jsonToStore(it) },
                    candidates = obj.optJSONArray("candidates")?.let { arr ->
                        buildList {
                            for (i in 0 until arr.length()) {
                                add(jsonToSummary(arr.getJSONObject(i)))
                            }
                        }
                    },
                )
            } catch (_: Exception) {
                null
            }
        }

        fun fromMatch(result: MatchResult): DiningMessage {
            return DiningMessage(
                type = DiningMsgType.STORE_RESULT,
                sceneId = result.sceneId,
                storeId = result.store.id,
                tts = result.tts,
                confidence = result.confidence,
                store = result.store,
                candidates = result.candidates.map {
                    StoreSummary(it.id, it.name, it.category, it.distanceMeters)
                },
            )
        }

        fun fromQa(result: QaResult, sceneId: String?, storeId: String?): DiningMessage {
            return DiningMessage(
                type = DiningMsgType.ANSWER,
                sceneId = sceneId,
                storeId = storeId,
                question = result.question,
                answer = result.answer,
                tts = result.tts,
            )
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key)
    return value.takeIf { it.isNotBlank() }
}

private fun storeToJson(store: Store): JSONObject {
    return JSONObject().apply {
        put("id", store.id)
        put("name", store.name)
        put("shortName", store.shortName)
        put("category", store.category)
        put("rating", store.rating)
        put("reviewCount", store.reviewCount)
        put("avgPrice", store.avgPrice)
        put("distanceMeters", store.distanceMeters)
        put("openNow", store.openNow)
        put("hours", store.hours)
        put("phone", store.phone)
        put("address", store.address)
        put("waitTables", store.waitTables)
        put("waitMinutes", store.waitMinutes)
        put("hasPrivateRoom", store.hasPrivateRoom)
        put("lat", store.lat)
        put("lng", store.lng)
        put("tags", JSONArray(store.tags))
        put("signatures", JSONArray(store.signatures))
        put("suitable", JSONArray(store.suitable))
        val deals = JSONArray()
        store.deals.forEach { deal ->
            deals.put(
                JSONObject()
                    .put("title", deal.title)
                    .put("price", deal.price)
                    .put("original", deal.original),
            )
        }
        put("deals", deals)
        val answers = JSONObject()
        store.answers.forEach { (k, v) -> answers.put(k, v) }
        put("answers", answers)
    }
}

private fun jsonToStore(obj: JSONObject): Store {
    return Store(
        id = obj.optString("id"),
        name = obj.optString("name"),
        shortName = obj.optString("shortName"),
        category = obj.optString("category"),
        rating = obj.optDouble("rating"),
        reviewCount = obj.optInt("reviewCount"),
        avgPrice = obj.optInt("avgPrice"),
        distanceMeters = obj.optInt("distanceMeters"),
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
        answers = obj.optJSONObject("answers").toStringMap(),
        lat = obj.optDouble("lat"),
        lng = obj.optDouble("lng"),
    )
}

private fun summaryToJson(summary: StoreSummary): JSONObject {
    return JSONObject()
        .put("id", summary.id)
        .put("name", summary.name)
        .put("category", summary.category)
        .put("distanceMeters", summary.distanceMeters)
}

private fun jsonToSummary(obj: JSONObject): StoreSummary {
    return StoreSummary(
        id = obj.optString("id"),
        name = obj.optString("name"),
        category = obj.optString("category"),
        distanceMeters = obj.optInt("distanceMeters"),
    )
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) add(optString(i))
    }
}

private fun JSONArray?.toDeals(): List<Deal> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val item = optJSONObject(i) ?: continue
            add(
                Deal(
                    title = item.optString("title"),
                    price = item.optInt("price"),
                    original = item.optInt("original"),
                ),
            )
        }
    }
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val result = mutableMapOf<String, String>()
    keys().forEach { key -> result[key] = optString(key) }
    return result
}
