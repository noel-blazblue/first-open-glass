package com.glass.dining.shared.place

data class PlaceProfile(
    val ref: PlaceRef,
    val rating: Double = 0.0,
    val avgCost: Int = 0,
    val tel: String = "",
    val hoursToday: String = "",
    val hoursWeek: String = "",
    val businessArea: String = "",
    val tag: String = "",
    val keytag: String = "",
    val alias: String = "",
) {
    val id: String get() = ref.id
    val name: String get() = ref.name
    val address: String get() = ref.address
    val lat: Double get() = ref.lat
    val lng: Double get() = ref.lng
    val distanceMeters: Int get() = ref.distanceMeters
    val source: String get() = ref.source
    val storeId: String get() = ref.storeId
    val floor: String get() = ref.floor
    val category: String get() = ref.category
    val catalogBacked: Boolean get() = ref.catalogBacked
    val hasCoords: Boolean get() = ref.hasCoords

    val hours: String get() = hoursToday.ifBlank { hoursWeek }
}
