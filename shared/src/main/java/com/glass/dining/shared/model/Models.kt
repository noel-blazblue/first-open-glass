package com.glass.dining.shared.model

data class Deal(
    val title: String,
    val price: Int,
    val original: Int,
)

data class Store(
    val id: String,
    val name: String,
    val shortName: String,
    val category: String,
    val rating: Double,
    val reviewCount: Int,
    val avgPrice: Int,
    val distanceMeters: Int,
    val openNow: Boolean,
    val hours: String,
    val phone: String,
    val address: String,
    val waitTables: Int,
    val waitMinutes: Int,
    val tags: List<String>,
    val deals: List<Deal>,
    val signatures: List<String>,
    val suitable: List<String>,
    val hasPrivateRoom: Boolean,
    val answers: Map<String, String>,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val floor: String = "",
    val source: String = "catalog",
    val catalogBacked: Boolean = true,
)

data class Scene(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val stores: List<Store>,
)

data class StoreSummary(
    val id: String,
    val name: String,
    val category: String,
    val distanceMeters: Int,
)

enum class ActiveSkill {
    NONE,
    BROWSE,
    NAV,
    MENU,
    COUPON,
    PAY,
}

data class MenuItem(
    val name: String,
    val price: Int,
    val category: String = "",
)

data class Coupon(
    val id: String,
    val storeId: String,
    val title: String,
    val price: Int,
    val original: Int,
    val usable: Boolean = true,
)

data class PayOrder(
    val merchantId: String,
    val storeName: String,
    val amount: Int,
    val qrType: String,
    val table: String = "",
    val confirmed: Boolean = false,
)

data class RedeemReceipt(
    val couponId: String,
    val title: String,
    val ok: Boolean,
    val sequenceId: String,
    val message: String,
)

data class ScoredStore(
    val store: Store,
    val score: Int,
    val key: String,
)

data class MatchResult(
    val store: Store,
    val candidates: List<Store>,
    val tts: String,
    val confidence: Float,
    val sceneId: String,
)

data class QaResult(
    val question: String,
    val intent: String,
    val answer: String,
    val tts: String,
)

data class LookInput(
    val forceStoreId: String? = null,
    val imageBase64: String? = null,
    val visionHint: String? = null,
)
