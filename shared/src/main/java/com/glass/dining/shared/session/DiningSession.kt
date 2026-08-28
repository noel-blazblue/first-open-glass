package com.glass.dining.shared.session

import com.glass.dining.shared.catalog.DiningMatcher
import com.glass.dining.shared.catalog.MemoryStoreCatalog
import com.glass.dining.shared.catalog.StoreCatalog
import com.glass.dining.shared.mock.MockCommerce
import com.glass.dining.shared.model.Coupon
import com.glass.dining.shared.model.LookInput
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.MenuItem
import com.glass.dining.shared.model.PayOrder
import com.glass.dining.shared.model.RedeemReceipt
import com.glass.dining.shared.model.Store
import com.glass.dining.shared.place.PlaceProfile
import com.glass.dining.shared.place.PlaceResolver

class DiningSession(
    catalog: StoreCatalog = MemoryStoreCatalog(),
) {
    private var matcher = DiningMatcher(catalog)

    @Volatile var catalog: StoreCatalog = catalog
        private set
    var lastMatch: MatchResult? = null
        private set
    var navigating: Boolean = false
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
    val hasPendingConfirm: Boolean
        get() = pendingPay != null || pendingCoupon != null
    val surfaceLocked: Boolean
        get() = navigating || hasPendingConfirm || lastMenu.isNotEmpty()

    fun currentSurface(): HudSurface {
        return when {
            navigating -> HudSurface.NAV
            hasPendingConfirm -> HudSurface.CONFIRM
            lastMatch != null -> HudSurface.STORE
            else -> HudSurface.TALK
        }
    }

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

    fun bindPlace(place: PlaceProfile): MatchResult {
        val result = PlaceResolver.matchResult(place)
        lastMatch = result
        return result
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
        return result
    }

    fun clearLook() {
        lastMatch = null
    }

    fun next(): MatchResult? {
        val result = matcher.next(lastMatch?.store?.id) ?: return null
        lastMatch = result
        return result
    }

    fun select(storeId: String): MatchResult? {
        val result = matcher.select(storeId) ?: return null
        lastMatch = result
        return result
    }

    fun recommend(query: String): MatchResult? {
        val result = matcher.recommend(query) ?: return null
        lastMatch = result
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
        navigating = true
        return true
    }

    fun stopNav() {
        navigating = false
    }

    fun startMenu(items: List<MenuItem> = MockCommerce.menuOf(currentStore)): List<MenuItem> {
        lastMenu = items
        return items
    }

    fun listCoupons(): List<Coupon> {
        lastCoupons = MockCommerce.couponsOf(currentStore)
        pendingCoupon = null
        return lastCoupons
    }

    fun prepareCoupon(couponId: String): Coupon? {
        val coupon = lastCoupons.firstOrNull { it.id == couponId }
            ?: MockCommerce.couponsOf(currentStore).firstOrNull { it.id == couponId }
            ?: lastCoupons.firstOrNull()
        pendingCoupon = coupon
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
        return receipt
    }

    fun preparePay(order: PayOrder): PayOrder {
        pendingPay = order
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
        return done
    }

    fun cancelPay() {
        pendingPay = null
    }

    /**
     * 逻辑离开当前业务面。返回离开的是哪一面，供模块 beforeExit。
     * STORE 只表示会话认为可以关浏览闩，不解绑。
     */
    fun dismiss(reason: DismissReason): HudSurface {
        val surface = currentSurface()
        when (surface) {
            HudSurface.NAV -> stopNav()
            HudSurface.CONFIRM -> {
                pendingPay = null
                pendingCoupon = null
            }
            HudSurface.STORE, HudSurface.TALK -> Unit
        }
        if (reason == DismissReason.REPLACE && surface != HudSurface.NAV) {
            clearTransient()
        }
        return surface
    }

    private fun clearTransient() {
        lastMenu = emptyList()
        lastCoupons = emptyList()
        pendingCoupon = null
        pendingPay = null
    }
}
