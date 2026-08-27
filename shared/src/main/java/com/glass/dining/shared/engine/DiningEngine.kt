package com.glass.dining.shared.engine

import com.glass.dining.shared.catalog.MemoryStoreCatalog
import com.glass.dining.shared.catalog.StoreCatalog
import com.glass.dining.shared.catalog.StoreCatalogIds
import com.glass.dining.shared.mock.MockCommerce
import com.glass.dining.shared.model.ActiveSkill
import com.glass.dining.shared.model.Coupon
import com.glass.dining.shared.model.LookInput
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.MenuItem
import com.glass.dining.shared.model.PayOrder
import com.glass.dining.shared.model.QaResult
import com.glass.dining.shared.model.RedeemReceipt
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

class QaEngine {
    fun ask(store: Store, question: String): QaResult {
        val intent = detectIntent(question)
        val answer = answerOf(store, intent)
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
            q.contains("菜单") || q.contains("菜谱") || q.contains("点什么") -> "招牌"
            q.contains("招牌") || q.contains("推荐") || q.contains("吃什么") || q.contains("菜") -> "招牌"
            q.contains("券") || q.contains("核销") -> "团购"
            q.contains("买单") || q.contains("结账") || q.contains("付款") -> "人均"
            q.contains("团购") || q.contains("优惠") || q.contains("便宜") -> "团购"
            q.contains("包间") || q.contains("包厢") -> "包间"
            q.contains("评价") || q.contains("怎么样") || q.contains("评分") -> "评价"
            q.contains("营业") || q.contains("开门") || q.contains("打烊") -> "营业"
            q.contains("怎么走") || q.contains("导航") || q.contains("出发") || q.contains("带我去") -> "导航"
            else -> "评价"
        }
    }

    private fun answerOf(store: Store, intent: String): String {
        return when (intent) {
            "人均" -> if (store.avgPrice > 0) "${store.shortName}人均大约${store.avgPrice}元。" else "${store.shortName}还没录入人均。"
            "排队" -> if (!store.openNow) {
                "${store.shortName}现在已经打烊。"
            } else if (store.waitMinutes <= 0) {
                "${store.shortName}现在不用排队。"
            } else {
                "${store.shortName}现在大约排${store.waitMinutes}分钟，${store.waitTables}桌。"
            }
            "招牌" -> if (store.signatures.isNotEmpty()) {
                "${store.shortName}招牌有${store.signatures.joinToString("、")}。"
            } else {
                "${store.shortName}还没录入招牌。"
            }
            "团购" -> if (store.deals.isNotEmpty()) {
                store.deals.joinToString("，") { "${it.title}${it.price}元" }
            } else {
                "${store.shortName}还没录入优惠。"
            }
            "包间" -> if (store.hasPrivateRoom) "${store.shortName}有包间。" else "${store.shortName}没录入包间。"
            "营业" -> if (store.hours.isNotBlank()) {
                "${store.shortName}营业时间${store.hours}。"
            } else if (store.openNow) {
                "${store.shortName}现在营业。"
            } else {
                "${store.shortName}现在打烊。"
            }
            "导航" -> if (store.address.isNotBlank()) {
                "${store.shortName}在${store.address}。"
            } else {
                "可以说出发，我按录入的坐标带你去。"
            }
            else -> {
                val rating = if (store.rating > 0) "评分${store.rating}。" else ""
                val price = if (store.avgPrice > 0) "人均${store.avgPrice}。" else ""
                "${store.shortName}。$rating$price".trim()
            }
        }
    }
}

class DiningSession(
    catalog: StoreCatalog = MemoryStoreCatalog(),
    private val qaEngine: QaEngine = QaEngine(),
) {
    private var matcher = DiningMatcher(catalog)

    @Volatile var catalog: StoreCatalog = catalog
        private set
    var lastMatch: MatchResult? = null
        private set
    var activeSkill: ActiveSkill = ActiveSkill.NONE
        private set
    var lastMenu: List<MenuItem> = emptyList()
        private set
    var lastCoupons: List<Coupon> = emptyList()
        private set
    var pendingCoupon: Coupon? = null
        private set
    var pendingPay: PayOrder? = null
        private set
    var lastReceipt: RedeemReceipt? = null
        private set

    val currentStore: Store?
        get() = lastMatch?.store
    val candidates: List<Store>
        get() = lastMatch?.candidates.orEmpty()

    fun replaceCatalog(next: StoreCatalog) {
        catalog = next
        matcher = DiningMatcher(next)
        val currentId = lastMatch?.store?.id
        if (currentId != null) {
            val refreshed = matcher.select(currentId)
            if (refreshed != null) {
                lastMatch = lastMatch?.copy(
                    store = refreshed.store,
                    candidates = refreshed.candidates,
                ) ?: refreshed
            }
        }
    }

    fun look(
        forceStoreId: String? = null,
        imageBase64: String? = null,
        visionHint: String? = null,
    ): MatchResult? {
        val result = matcher.match(
            LookInput(
                forceStoreId = forceStoreId,
                imageBase64 = imageBase64,
                visionHint = visionHint,
            ),
        ) ?: return null
        lastMatch = result
        activeSkill = ActiveSkill.BROWSE
        return result
    }

    fun clearLook() {
        lastMatch = null
        if (activeSkill == ActiveSkill.BROWSE) {
            activeSkill = ActiveSkill.NONE
        }
    }

    fun next(): MatchResult? {
        val result = matcher.next(lastMatch?.store?.id) ?: return null
        lastMatch = result
        activeSkill = ActiveSkill.BROWSE
        return result
    }

    fun select(storeId: String): MatchResult? {
        val result = matcher.select(storeId) ?: return null
        lastMatch = result
        activeSkill = ActiveSkill.BROWSE
        return result
    }

    fun recommend(query: String): MatchResult? {
        val result = matcher.recommend(query) ?: return null
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
        clearTransient()
        activeSkill = ActiveSkill.NAV
        return true
    }

    fun stopNav() {
        activeSkill = if (lastMatch != null) ActiveSkill.BROWSE else ActiveSkill.NONE
    }

    fun startMenu(items: List<MenuItem> = MockCommerce.menuOf(currentStore)): List<MenuItem> {
        lastMenu = items
        activeSkill = ActiveSkill.MENU
        return items
    }

    fun listCoupons(): List<Coupon> {
        lastCoupons = MockCommerce.couponsOf(currentStore)
        pendingCoupon = null
        activeSkill = ActiveSkill.COUPON
        return lastCoupons
    }

    fun prepareCoupon(couponId: String): Coupon? {
        val coupon = lastCoupons.firstOrNull { it.id == couponId }
            ?: MockCommerce.couponsOf(currentStore).firstOrNull { it.id == couponId }
            ?: lastCoupons.firstOrNull()
        pendingCoupon = coupon
        if (coupon != null) {
            activeSkill = ActiveSkill.COUPON
        }
        return coupon
    }

    fun redeemCoupon(): RedeemReceipt {
        val coupon = pendingCoupon
        if (coupon == null) {
            return RedeemReceipt("", "", false, "", "还没选定要核销的券")
        }
        val receipt = RedeemReceipt(
            couponId = coupon.id,
            title = coupon.title,
            ok = coupon.usable,
            sequenceId = "mock-${System.currentTimeMillis()}",
            message = if (coupon.usable) "已核销${coupon.title}" else "这张券现在不能用",
        )
        lastReceipt = receipt
        pendingCoupon = null
        activeSkill = if (lastMatch != null) ActiveSkill.BROWSE else ActiveSkill.NONE
        return receipt
    }

    fun preparePay(order: PayOrder): PayOrder {
        pendingPay = order
        activeSkill = ActiveSkill.PAY
        return order
    }

    fun confirmPay(): PayOrder? {
        val order = pendingPay ?: return null
        val done = order.copy(confirmed = true)
        pendingPay = null
        lastReceipt = RedeemReceipt(
            couponId = "",
            title = "买单",
            ok = true,
            sequenceId = "pay-${System.currentTimeMillis()}",
            message = "已付${done.amount}元给${done.storeName}",
        )
        activeSkill = if (lastMatch != null) ActiveSkill.BROWSE else ActiveSkill.NONE
        return done
    }

    fun cancelPay() {
        pendingPay = null
        if (activeSkill == ActiveSkill.PAY) {
            activeSkill = if (lastMatch != null) ActiveSkill.BROWSE else ActiveSkill.NONE
        }
    }

    private fun clearTransient() {
        lastMenu = emptyList()
        lastCoupons = emptyList()
        pendingCoupon = null
        pendingPay = null
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
