package com.glass.dining.shared.place

data class PlaceRef(
    val id: String,
    val name: String,
    val address: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val distanceMeters: Int = 0,
    val source: String = SOURCE_AMAP,
    val storeId: String = "",
    val floor: String = "",
    val category: String = "",
) {
    val catalogBacked: Boolean get() = source == SOURCE_CATALOG
    val hasCoords: Boolean get() = kotlin.math.abs(lat) > 1e-6 || kotlin.math.abs(lng) > 1e-6

    fun asProfile(
        rating: Double = 0.0,
        avgCost: Int = 0,
        tel: String = "",
        hoursToday: String = "",
        hoursWeek: String = "",
        businessArea: String = "",
        tag: String = "",
        keytag: String = "",
        alias: String = "",
    ): PlaceProfile {
        return PlaceProfile(
            ref = this,
            rating = rating,
            avgCost = avgCost,
            tel = tel,
            hoursToday = hoursToday,
            hoursWeek = hoursWeek,
            businessArea = businessArea,
            tag = tag,
            keytag = keytag,
            alias = alias,
        )
    }

    companion object {
        const val SOURCE_CATALOG = "catalog"
        const val SOURCE_AMAP = "amap"
    }
}
