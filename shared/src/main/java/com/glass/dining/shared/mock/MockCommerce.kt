package com.glass.dining.shared.mock

import com.glass.dining.shared.model.Coupon
import com.glass.dining.shared.model.MenuItem
import com.glass.dining.shared.model.PayOrder
import com.glass.dining.shared.model.Store
import com.glass.dining.shared.vision.VisionPolicy

object MockCommerce {
    fun menuOf(store: Store?): List<MenuItem> {
        if (store == null) return emptyList()
        val signatures = store.signatures.mapIndexed { index, name ->
            MenuItem(name, (store.avgPrice / 3 + index * 8).coerceAtLeast(12), "招牌")
        }
        return signatures.distinctBy { it.name }
    }

    fun couponsOf(store: Store?): List<Coupon> {
        if (store == null) return emptyList()
        return store.deals.mapIndexed { index, deal ->
            Coupon(
                id = "${store.id}_coupon_$index",
                storeId = store.id,
                title = deal.title,
                price = deal.price,
                original = deal.original,
                usable = true,
            )
        }
    }

    fun payFromQr(payload: String, fallback: Store?, stores: List<Store> = emptyList()): PayOrder? {
        val kind = VisionPolicy.classifyQr(payload)
        if (kind == "table") return null
        val merchant = VisionPolicy.merchantFromQr(payload)
        val store = stores.firstOrNull { it.id == merchant } ?: fallback
        val amount = store?.deals?.firstOrNull()?.price ?: store?.avgPrice ?: 0
        if (store == null || amount <= 0) return null
        return PayOrder(
            merchantId = store.id,
            storeName = store.shortName,
            amount = amount,
            qrType = VisionPolicy.qrPayBrand(payload),
        )
    }

    fun tableFromQr(payload: String): Pair<String, String>? {
        if (VisionPolicy.classifyQr(payload) != "table") return null
        val storeId = VisionPolicy.merchantFromQr(payload)
        val table = VisionPolicy.tableFromQr(payload)
        return storeId to table
    }
}
