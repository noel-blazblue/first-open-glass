package com.glass.dining.shared.catalog

import com.glass.dining.shared.model.LookInput
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.Store
import kotlin.math.roundToInt

class DiningMatcher(
    private val catalog: StoreCatalog,
) {
    fun match(input: LookInput): MatchResult? {
        val stores = catalog.stores()
        if (stores.isEmpty()) return null
        val forced = input.forceStoreId?.let { id -> catalog.storeById(id) }
        val fromVision = input.visionHint?.let { StoreVision.matchStore(stores, it) }
        val store = forced ?: fromVision ?: return null
        val confidence = when {
            forced != null -> 0.99f
            fromVision != null -> 0.92f
            else -> 0.8f
        }
        return resultOf(store, stores, confidence)
    }

    fun next(currentStoreId: String?): MatchResult? {
        val stores = catalog.stores()
        if (stores.isEmpty()) return null
        val index = stores.indexOfFirst { it.id == currentStoreId }
        val nextIndex = if (index < 0) 0 else (index + 1) % stores.size
        return resultOf(stores[nextIndex], stores, confidence = 0.88f)
    }

    fun select(storeId: String): MatchResult? {
        return match(LookInput(forceStoreId = storeId))
    }

    fun recommend(query: String): MatchResult? {
        val stores = catalog.stores()
        if (stores.isEmpty()) return null
        val picked = filterRecommend(stores, query).ifEmpty {
            stores.sortedBy { if (it.distanceMeters > 0) it.distanceMeters else Int.MAX_VALUE }.take(3)
        }
        if (picked.isEmpty()) return null
        val store = picked.first()
        val names = picked.joinToString("、") { it.shortName }
        val waitHint = if (picked.all { it.waitMinutes <= 0 }) {
            "这几家现在都不用排。"
        } else {
            ""
        }
        return MatchResult(
            store = store,
            candidates = picked,
            tts = "附近有$names。${waitHint}去哪家？",
            confidence = 0.86f,
            sceneId = StoreCatalogIds.LOCAL_SCENE,
        )
    }

    private fun filterRecommend(stores: List<Store>, query: String): List<Store> {
        val q = query.trim()
        var pool = stores
        val cuisine = listOf(
            "火锅", "川菜", "烧烤", "咖啡", "茶", "西餐", "快餐",
            "面", "杭帮", "云南", "台菜", "北京", "西北", "点心",
        ).firstOrNull { q.contains(it) }
        if (cuisine != null) {
            pool = pool.filter { store ->
                store.category.contains(cuisine) ||
                    store.tags.any { it.contains(cuisine) } ||
                    store.name.contains(cuisine) ||
                    store.shortName.contains(cuisine)
            }
        }
        if (q.contains("排队") || q.contains("不用排") || q.contains("别排") || q.contains("别等")) {
            val shortWait = pool.filter { it.waitMinutes <= 10 }.sortedBy { it.waitMinutes }
            pool = shortWait.ifEmpty { pool.sortedBy { it.waitMinutes } }
        }
        return pool.sortedBy { if (it.distanceMeters > 0) it.distanceMeters else Int.MAX_VALUE }.take(3)
    }

    private fun resultOf(store: Store, stores: List<Store>, confidence: Float): MatchResult {
        val candidates = stores
            .sortedBy { if (it.distanceMeters > 0) it.distanceMeters else Int.MAX_VALUE }
            .take(3)
            .ifEmpty { listOf(store) }
        return MatchResult(
            store = store,
            candidates = candidates,
            tts = summaryTts(store),
            confidence = confidence,
            sceneId = StoreCatalogIds.LOCAL_SCENE,
        )
    }

    companion object {
        fun summaryTts(store: Store): String {
            if (!store.catalogBacked) {
                val addr = store.address.ifBlank { "" }
                val dist = if (store.distanceMeters > 0) "大约${store.distanceMeters}米。" else ""
                return "${store.shortName}。$addr$dist".trim()
            }
            val rating = if (store.rating > 0) ratingSpeech(store.rating) + "，" else ""
            val wait = if (!store.openNow) {
                "现在已经打烊"
            } else if (store.waitMinutes <= 0) {
                "现在不用排队"
            } else {
                "现在大约排${numberSpeech(store.waitMinutes)}分钟"
            }
            val price = if (store.avgPrice > 0) "人均${numberSpeech(store.avgPrice)}，" else ""
            return "${store.shortName}，$rating$price$wait"
        }

        private fun ratingSpeech(rating: Double): String {
            val whole = rating.toInt()
            val tenth = ((rating - whole) * 10).roundToInt()
            return if (tenth == 0) "${digit(whole)}分" else "${digit(whole)}点${digit(tenth)}分"
        }

        private fun numberSpeech(value: Int): String {
            return when {
                value < 10 -> digit(value)
                value == 10 -> "十"
                value < 20 -> "十${digit(value - 10)}"
                value % 10 == 0 -> "${digit(value / 10)}十"
                value < 100 -> "${digit(value / 10)}十${digit(value % 10)}"
                else -> value.toString()
            }
        }

        private fun digit(value: Int): String {
            return listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九").getOrElse(value) { value.toString() }
        }
    }
}

