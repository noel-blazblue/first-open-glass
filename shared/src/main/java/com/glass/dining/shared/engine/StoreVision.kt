package com.glass.dining.shared.engine

import com.glass.dining.shared.model.ScoredStore
import com.glass.dining.shared.model.Store

object StoreVision {
    fun matchStore(stores: List<Store>, rawHint: String): Store? {
        return rankStores(stores, rawHint).firstOrNull()?.store
    }

    fun rankStores(stores: List<Store>, rawHint: String): List<ScoredStore> {
        val hint = rawHint.trim()
        if (hint.isBlank() || isUnknown(hint)) return emptyList()
        val compact = compact(hint)
        return stores.mapNotNull { store ->
            val best = keysOf(store).map { key -> key to scoreKey(compact, hint, key) }.maxByOrNull { it.second }
            if (best != null && best.second > 0) {
                ScoredStore(store, best.second, best.first)
            } else {
                null
            }
        }.sortedByDescending { it.score }
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
        return (fromName + store.tags + store.signatures)
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
