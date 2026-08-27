package com.glass.dining.shared.agent

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

    companion object {
        const val SOURCE_CATALOG = "catalog"
        const val SOURCE_AMAP = "amap"
    }
}

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
    val skill: String = "none",
    val boundPlace: PlaceRef? = null,
    val disambiguation: List<PlaceRef> = emptyList(),
    val recentSearch: List<PlaceRef> = emptyList(),
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
    val navActive: Boolean = false,
    val modelReady: Boolean = false,
    val capabilities: List<String> = emptyList(),
    val navArrived: Boolean = false,
) {
    val hasGps: Boolean get() = gpsPermission
    val currentFloor: String
        get() = environment?.usableFloor?.ifBlank { spokenFloor }.orEmpty().ifBlank { spokenFloor }

    val businessRole: String
        get() = when {
            boundPlace == null -> ROLE_NONE
            navActive && navArrived -> ROLE_APPROACHING
            navActive -> ROLE_DESTINATION
            skill == "menu" || skill == "coupon" || skill == "pay" -> ROLE_SERVING
            else -> ROLE_VIEWING
        }

    companion object {
        const val ROLE_NONE = "none"
        const val ROLE_DESTINATION = "destination"
        const val ROLE_VIEWING = "viewing"
        const val ROLE_SERVING = "serving"
        const val ROLE_APPROACHING = "approaching"
    }
}

data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class LlmMessage(
    val role: String,
    val content: String = "",
    val toolCallId: String = "",
    val toolCalls: List<LlmToolCall> = emptyList(),
    val extra: Map<String, String> = emptyMap(),
    val rawJson: String = "",
)

data class LlmTurn(
    val content: String = "",
    val toolCalls: List<LlmToolCall> = emptyList(),
    val assistant: LlmMessage = LlmMessage("assistant", content, toolCalls = toolCalls),
)

data class ToolResult(
    val ok: Boolean,
    val content: String,
    val error: String? = null,
    val policyBlocked: Boolean = false,
    val retryable: Boolean = false,
) {
    companion object {
        fun json(ok: Boolean, body: String): ToolResult = ToolResult(ok, body)
        fun fail(
            message: String,
            retryable: Boolean = false,
            policyBlocked: Boolean = false,
            code: String = "error",
        ): ToolResult {
            val json = """{"ok":false,"error":"$code","message":${jsonString(message)}}"""
            return ToolResult(false, json, message, policyBlocked, retryable)
        }

        fun jsonString(value: String): String {
            return buildString {
                append('"')
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        else -> append(ch)
                    }
                }
                append('"')
            }
        }
    }
}

data class AgentEvent(
    val runId: String,
    val step: Int,
    val kind: String,
    val name: String = "",
    val latencyMs: Long = 0,
    val detail: String = "",
)

data class AgentOutcome(
    val speak: String,
    val steps: Int,
    val messages: List<LlmMessage>,
    val reachedLimit: Boolean = false,
    val failed: Boolean = false,
    val cancelled: Boolean = false,
    val events: List<AgentEvent> = emptyList(),
)

enum class ToolRisk { READ, WRITE, CONFIRM }

data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
    val risk: ToolRisk = ToolRisk.READ,
    val timeoutMs: Long = 15_000,
    val requiredCapability: String = "",
    val parallelSafe: Boolean = true,
    val maxRetries: Int = 1,
)
