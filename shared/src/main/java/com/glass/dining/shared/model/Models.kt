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
    val sceneId: String,
    val forceStoreId: String? = null,
    val imageBase64: String? = null,
    val visionHint: String? = null,
    val imageFingerprint: Long? = null,
    val headingDegrees: Float? = null,
)
