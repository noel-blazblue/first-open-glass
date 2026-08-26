package com.glass.dining.shared.mock

import com.glass.dining.shared.model.Coupon
import com.glass.dining.shared.model.MenuItem
import com.glass.dining.shared.model.PayOrder
import com.glass.dining.shared.vision.VisionPolicy

object MockCommerce {
    fun menuOf(storeId: String): List<MenuItem> {
        val store = MockCatalog.storeById(storeId) ?: return defaultMenu()
        val signatures = store.signatures.mapIndexed { index, name ->
            MenuItem(name, (store.avgPrice / 3 + index * 8).coerceAtLeast(12), "招牌")
        }
        val extras = listOf(
            MenuItem("米饭", 3, "主食"),
            MenuItem("例汤", 8, "汤"),
            MenuItem("时蔬", 18, "素菜"),
        )
        return (signatures + extras).distinctBy { it.name }
    }

    fun couponsOf(storeId: String): List<Coupon> {
        val store = MockCatalog.storeById(storeId) ?: return emptyList()
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

    fun payFromQr(payload: String, fallbackStoreId: String?): PayOrder? {
        val kind = VisionPolicy.classifyQr(payload)
        if (kind == "table") return null
        val merchant = VisionPolicy.merchantFromQr(payload).ifBlank { fallbackStoreId.orEmpty() }
        val store = MockCatalog.storeById(merchant)
        val amount = store?.deals?.firstOrNull()?.price ?: store?.avgPrice ?: 88
        return PayOrder(
            merchantId = store?.id ?: merchant.ifBlank { "unknown_merchant" },
            storeName = store?.shortName ?: "眼前这家店",
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

    private fun defaultMenu(): List<MenuItem> {
        return listOf(
            MenuItem("今日例汤", 12, "汤"),
            MenuItem("时蔬", 18, "素菜"),
            MenuItem("米饭", 3, "主食"),
        )
    }
}
