package com.glass.dining.shared.agent

import com.glass.dining.shared.place.PlaceProfile
import com.glass.dining.shared.place.PlaceRef

data class SceneObservation(
    val scene: String = SCENE_UNKNOWN,
    val entities: List<String> = emptyList(),
    val visibleText: String = "",
    val salientRegions: List<String> = emptyList(),
    val confidence: Float = 0f,
    val evidence: List<String> = emptyList(),
    val stability: Int = 0,
    val storeCandidate: String = "",
    val isStoreFact: Boolean = false,
) {
    val label: String
        get() = when {
            isStoreFact && storeCandidate.isNotBlank() -> storeCandidate.take(8)
            scene == SCENE_BANNER -> "横幅"
            scene == SCENE_SIGNAGE -> "标识"
            scene == SCENE_BILLBOARD -> "广告牌"
            scene == SCENE_STREET_SIGN -> "路牌"
            scene == SCENE_STOREFRONT && storeCandidate.isNotBlank() -> "疑似门头"
            scene == SCENE_STOREFRONT -> "门头"
            scene == SCENE_BUILDING -> "建筑"
            scene == SCENE_KEYBOARD -> ""
            else -> ""
        }

    companion object {
        const val SCENE_UNKNOWN = "unknown"
        const val SCENE_BANNER = "banner"
        const val SCENE_SIGNAGE = "signage"
        const val SCENE_BILLBOARD = "billboard"
        const val SCENE_STREET_SIGN = "street_sign"
        const val SCENE_STOREFRONT = "storefront"
        const val SCENE_BUILDING = "building"
        const val SCENE_KEYBOARD = "keyboard"
    }
}

data class WorldContext(
    val boundPlace: PlaceRef? = null,
    val boundProfile: PlaceProfile? = null,
    val viewingPlace: PlaceRef? = null,
    val viewingProfile: PlaceProfile? = null,
    val disambiguation: List<PlaceRef> = emptyList(),
    val recentSearch: List<PlaceProfile> = emptyList(),
    val observation: SceneObservation? = null,
    val gpsPermission: Boolean = false,
    val hasGpsFix: Boolean = false,
    val gpsLat: Double? = null,
    val gpsLng: Double? = null,
    val catalogCount: Int = 0,
    val pendingPay: String = "",
    val pendingCoupon: String = "",
    val spokenFloor: String = "",
    val environment: EnvironmentState? = null,
    val hudOverlay: String = "",
    val navActive: Boolean = false,
    val modelReady: Boolean = false,
    val capabilities: List<String> = emptyList(),
    val navArrived: Boolean = false,
) {
    val hasGps: Boolean get() = gpsPermission
    val runtimeCapabilities: Set<String>
        get() = buildSet {
            if (gpsPermission || hasGpsFix || navActive) add(CAP_GPS)
        }
    val currentFloor: String
        get() = environment?.usableFloor?.ifBlank { spokenFloor }.orEmpty().ifBlank { spokenFloor }

    val businessRole: String
        get() = when {
            boundPlace == null -> ROLE_NONE
            navActive && navArrived -> ROLE_APPROACHING
            navActive -> ROLE_DESTINATION
            pendingPay.isNotBlank() || pendingCoupon.isNotBlank() -> ROLE_SERVING
            else -> ROLE_VIEWING
        }

    companion object {
        const val ROLE_NONE = "none"
        const val ROLE_DESTINATION = "destination"
        const val ROLE_VIEWING = "viewing"
        const val ROLE_SERVING = "serving"
        const val ROLE_APPROACHING = "approaching"
        const val CAP_GPS = "gps"
    }
}
