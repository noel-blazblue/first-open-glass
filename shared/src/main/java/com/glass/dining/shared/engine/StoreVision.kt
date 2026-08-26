package com.glass.dining.shared.engine

import com.glass.dining.shared.model.Scene
import com.glass.dining.shared.model.ScoredStore
import com.glass.dining.shared.model.Store
import kotlin.math.absoluteValue

object StoreVision {
    fun matchStore(scene: Scene, rawHint: String): Store? {
        return rankStores(scene, rawHint).firstOrNull()?.store
    }

    fun rankStores(scene: Scene, rawHint: String): List<ScoredStore> {
        val hint = rawHint.trim()
        if (hint.isBlank() || isUnknown(hint)) return emptyList()
        val compact = compact(hint)
        return scene.stores.mapNotNull { store ->
            val best = keysOf(store).map { key -> key to scoreKey(compact, hint, key) }.maxByOrNull { it.second }
            if (best != null && best.second > 0) {
                ScoredStore(store, best.second, best.first)
            } else {
                null
            }
        }.sortedByDescending { it.score }
    }

    fun pickByFingerprint(scene: Scene, fingerprint: Long): Store? {
        if (scene.stores.isEmpty()) return null
        val index = (fingerprint.absoluteValue % scene.stores.size).toInt()
        return scene.stores[index]
    }

    private fun isUnknown(hint: String): Boolean {
        return listOf("未识别", "看不清", "没有店", "不是店", "无招牌", "无法识别", "没有看到")
            .any { hint.contains(it) }
    }

    private fun keysOf(store: Store): List<String> {
        val fromName = listOf(store.name, store.shortName)
            .flatMap { name ->
                listOf(
                    name,
                    name.replace(Regex("[（(].*"), ""),
                    name.replace("店", ""),
                )
            }
        val brands = listOf(
            "海底捞", "喜茶", "巴奴", "西贝", "奈雪", "麦当劳", "春风十里", "屋头",
            "大董", "京A", "鼎泰丰", "星巴克", "云海肴", "楼外楼", "知味观",
            "新白鹿", "绿茶", "肯德基", "夜归",
        ).filter { brand -> store.name.contains(brand) || store.shortName.contains(brand) }
        return (fromName + brands + store.tags)
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun scoreKey(compactHint: String, rawHint: String, key: String): Int {
        val compactKey = compact(key)
        if (compactKey.length < 2) return 0
        return when {
            compactHint.contains(compactKey) -> compactKey.length * 3
            rawHint.contains(key) -> key.length * 2
            compactKey.contains(compactHint) && compactHint.length >= 2 -> compactHint.length
            else -> 0
        }
    }

    private fun compact(value: String): String {
        return value.replace(Regex("[\\s\\p{Punct}的了呢啊]+"), "")
    }
}
