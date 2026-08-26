package com.glass.dining.shared.engine

import com.glass.dining.shared.mock.MockCatalog
import com.glass.dining.shared.model.ActiveSkill
import com.glass.dining.shared.model.LookInput
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.QaResult
import com.glass.dining.shared.model.Scene
import com.glass.dining.shared.model.Store
import kotlin.math.roundToInt

class DiningMatcher(
    private val catalog: MockCatalog = MockCatalog,
) {
    fun match(input: LookInput): MatchResult {
        val scene = catalog.sceneById(input.sceneId)
        val forced = input.forceStoreId?.let { id -> scene.stores.firstOrNull { it.id == id } }
        val fromVision = input.visionHint?.let { StoreVision.matchStore(scene, it) }
        val fromPhoto = if (fromVision == null) {
            input.imageFingerprint?.let { StoreVision.pickByFingerprint(scene, it) }
        } else {
            null
        }
        val store = forced ?: fromVision ?: fromPhoto ?: pickDefault(scene, input.headingDegrees)
        val confidence = when {
            forced != null -> 0.99f
            fromVision != null -> 0.92f
            fromPhoto != null -> 0.76f
            else -> (0.84f + (store.rating.toFloat() - 4.0f) * 0.08f).coerceIn(0.78f, 0.97f)
        }
        return resultOf(scene, store, confidence)
    }

    fun next(sceneId: String, currentStoreId: String?): MatchResult {
        val scene = catalog.sceneById(sceneId)
        if (scene.stores.isEmpty()) {
            error("empty scene ${scene.id}")
        }
        val index = scene.stores.indexOfFirst { it.id == currentStoreId }
        val nextIndex = if (index < 0) 0 else (index + 1) % scene.stores.size
        return resultOf(scene, scene.stores[nextIndex], confidence = 0.88f)
    }

    fun select(sceneId: String, storeId: String): MatchResult {
        return match(LookInput(sceneId = sceneId, forceStoreId = storeId))
    }

    fun recommend(sceneId: String, query: String): MatchResult {
        val scene = catalog.sceneById(sceneId)
        val picked = filterRecommend(scene.stores, query).ifEmpty {
            scene.stores.sortedBy { it.distanceMeters }.take(3)
        }
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
            sceneId = scene.id,
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
        if (q.contains("约会")) {
            pool = pool.filter { "约会" in it.suitable }.ifEmpty { pool }
        }
        if (q.contains("带娃") || q.contains("小孩")) {
            pool = pool.filter { "带娃" in it.suitable }.ifEmpty { pool }
        }
        return pool.sortedBy { it.distanceMeters }.take(3)
    }

    private fun pickDefault(scene: Scene, headingDegrees: Float?): Store {
        // 公开 SDK 没有 IMU 朝向。heading 预留，当前按距离最近一家。
        if (headingDegrees != null) {
            val shifted = (headingDegrees.roundToInt() / 45).mod(scene.stores.size)
            return scene.stores[shifted]
        }
        return scene.stores.minBy { it.distanceMeters }
    }

    private fun resultOf(scene: Scene, store: Store, confidence: Float): MatchResult {
        val candidates = scene.stores
            .sortedBy { it.distanceMeters }
            .take(3)
        return MatchResult(
            store = store,
            candidates = candidates,
            tts = summaryTts(store),
            confidence = confidence,
            sceneId = scene.id,
        )
    }

    companion object {
        fun summaryTts(store: Store): String {
            val rating = ratingSpeech(store.rating)
            val wait = if (!store.openNow) {
                "现在已经打烊"
            } else if (store.waitMinutes <= 0) {
                "现在不用排队"
            } else {
                "现在大约排${numberSpeech(store.waitMinutes)}分钟"
            }
            return "${store.shortName}，${rating}，人均${numberSpeech(store.avgPrice)}，$wait"
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

class QaEngine {
    fun ask(store: Store, question: String): QaResult {
        val intent = detectIntent(question)
        val answer = store.answers[intent] ?: "${store.shortName}这个问题我暂时只有这些：评分 ${store.rating}，人均 ${store.avgPrice}。"
        return QaResult(
            question = question,
            intent = intent,
            answer = answer,
            tts = answer,
        )
    }

    fun detectIntent(question: String): String {
        val q = question.trim()
        return when {
            q.contains("人均") || q.contains("多少钱") || q.contains("贵不") -> "人均"
            q.contains("排队") || q.contains("等位") || q.contains("要等") -> "排队"
            q.contains("招牌") || q.contains("推荐") || q.contains("吃什么") || q.contains("菜") -> "招牌"
            q.contains("团购") || q.contains("优惠") || q.contains("便宜") -> "团购"
            q.contains("约会") || q.contains("浪漫") -> "约会"
            q.contains("带娃") || q.contains("小孩") || q.contains("儿童") -> "带娃"
            q.contains("包间") || q.contains("包厢") -> "包间"
            q.contains("评价") || q.contains("怎么样") || q.contains("评分") -> "评价"
            q.contains("营业") || q.contains("开门") || q.contains("打烊") -> "营业"
            q.contains("怎么走") || q.contains("导航") || q.contains("出发") || q.contains("带我去") -> "导航"
            else -> "评价"
        }
    }
}

class DiningSession(
    private val matcher: DiningMatcher = DiningMatcher(),
    private val qaEngine: QaEngine = QaEngine(),
    initialSceneId: String = MockCatalog.defaultSceneId,
) {
    var sceneId: String = initialSceneId
        private set
    var lastMatch: MatchResult? = null
        private set
    var activeSkill: ActiveSkill = ActiveSkill.NONE
        private set

    val currentStore: Store?
        get() = lastMatch?.store
    val candidates: List<Store>
        get() = lastMatch?.candidates.orEmpty()

    fun setScene(id: String) {
        sceneId = id
        lastMatch = null
        activeSkill = ActiveSkill.NONE
    }

    fun look(
        forceStoreId: String? = null,
        imageBase64: String? = null,
        visionHint: String? = null,
        imageFingerprint: Long? = null,
    ): MatchResult {
        val result = matcher.match(
            LookInput(
                sceneId = sceneId,
                forceStoreId = forceStoreId,
                imageBase64 = imageBase64,
                visionHint = visionHint,
                imageFingerprint = imageFingerprint,
            ),
        )
        lastMatch = result
        activeSkill = ActiveSkill.BROWSE
        return result
    }

    fun next(): MatchResult {
        val result = matcher.next(sceneId, lastMatch?.store?.id)
        lastMatch = result
        activeSkill = ActiveSkill.BROWSE
        return result
    }

    fun select(storeId: String): MatchResult {
        val result = matcher.select(sceneId, storeId)
        lastMatch = result
        activeSkill = ActiveSkill.BROWSE
        return result
    }

    fun recommend(query: String): MatchResult {
        val result = matcher.recommend(sceneId, query)
        lastMatch = result
        activeSkill = ActiveSkill.BROWSE
        return result
    }

    fun updateStore(store: Store) {
        val current = lastMatch ?: return
        lastMatch = current.copy(
            store = store,
            candidates = current.candidates.map { if (it.id == store.id) store else it },
        )
    }

    fun startNav(): Boolean {
        if (lastMatch?.store == null) return false
        activeSkill = ActiveSkill.NAV
        return true
    }

    fun stopNav() {
        activeSkill = if (lastMatch != null) ActiveSkill.BROWSE else ActiveSkill.NONE
    }

    fun ask(question: String): QaResult {
        val store = lastMatch?.store
        if (store == null) {
            return QaResult(
                question = question,
                intent = "闲置",
                answer = "还没选定餐厅。可以说附近火锅，或者说看店。",
                tts = "还没选定餐厅。可以说附近火锅，或者说看店。",
            )
        }
        return qaEngine.ask(store, question)
    }
}
