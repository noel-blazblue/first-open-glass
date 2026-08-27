package com.glass.dining.shared.agent

import com.glass.dining.shared.nav.LandmarkSignage

data class EnvironmentChange(
    val text: String,
    val atMs: Long,
)

data class EnvironmentObservation(
    val kind: String,
    val value: String,
    val evidence: String = "",
    val confidence: Float = 0f,
    val observedAt: Long = 0L,
    val status: String = STATUS_OBSERVED,
) {
    companion object {
        const val KIND_FLOOR = "floor_sign"
        const val KIND_TEXT = "visible_text"
        const val STATUS_OBSERVED = "observed"
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_INFERRED = "inferred"
        const val STATUS_STALE = "stale"
        const val STATUS_HYPOTHESIS = "hypothesis"

        fun evidenceMark(confidence: String): String {
            return when (confidence) {
                STATUS_CONFIRMED, STATUS_OBSERVED -> "观察到"
                STATUS_INFERRED -> "根据连续证据判断"
                else -> "推断"
            }
        }
    }
}

data class EnvironmentLook(
    val sceneBrief: String = "",
    val change: String = "",
    val salientText: String = "",
    val objects: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val location: String = "",
    val placeHint: String = "",
    val observedClaims: List<ObservedClaim> = emptyList(),
    val floorCandidate: String = "",
    val floorEvidence: String = "",
    val confidence: Float = 0f,
    val spaceType: String = "",
    val guideDir: String = "",
    val storeNames: List<String> = emptyList(),
    val blocked: Boolean = false,
    val exits: List<String> = emptyList(),
    val eventProposal: EventProposal? = null,
)

data class EnvironmentEvent(
    val id: String = "",
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val summary: String = "",
    val hypothesis: String = "",
    val confidence: String = EnvironmentObservation.STATUS_OBSERVED,
    val from: String = "",
    val to: String = "",
    val location: String = "",
    val objects: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val episodeCount: Int = 1,
    val episodeIds: List<String> = emptyList(),
    val relations: List<EventRelation> = emptyList(),
    val observedClaims: List<ObservedClaim> = emptyList(),
    val topologyNodeId: String = "",
    val poseX: Float = 0f,
    val poseY: Float = 0f,
    val poseZ: Float = 0f,
)

data class EnvironmentState(
    val currentBrief: String = "",
    val recentChanges: List<EnvironmentChange> = emptyList(),
    val userFacts: List<String> = emptyList(),
    val recentObservations: List<EnvironmentObservation> = emptyList(),
    val activeEvent: EnvironmentEvent? = null,
    val recentEvents: List<EnvironmentEvent> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val hasUsable: Boolean
        get() = currentBrief.isNotBlank() ||
            userFacts.isNotEmpty() ||
            recentObservations.isNotEmpty() ||
            activeEvent != null

    val usableFloor: String
        get() {
            userFacts.forEach { fact ->
                val spoken = LandmarkSignage.parseSpokenFloor(fact).ifBlank {
                    LandmarkSignage.parseVisibleFloor(fact)
                }
                if (spoken.isNotBlank()) return spoken
            }
            return recentObservations
                .filter { it.kind == EnvironmentObservation.KIND_FLOOR && it.status != EnvironmentObservation.STATUS_STALE }
                .maxByOrNull { it.observedAt }
                ?.value
                .orEmpty()
        }

    fun promptBlock(limit: Int = 480): String {
        val lines = buildList {
            if (currentBrief.isNotBlank()) add("【当前环境】${clip(currentBrief, 180)}")
            val obs = recentObservations.filter { it.status != EnvironmentObservation.STATUS_STALE }.take(4)
            if (obs.isNotEmpty()) {
                add("【近期观察】" + obs.joinToString("；") { item ->
                    val tag = when (item.kind) {
                        EnvironmentObservation.KIND_FLOOR -> "楼层标识"
                        EnvironmentObservation.KIND_TEXT -> "可见文字"
                        else -> item.kind
                    }
                    "$tag=${item.value}"
                })
            }
            activeEvent?.let { event ->
                val body = event.summary.ifBlank { event.hypothesis }
                if (body.isNotBlank()) {
                    val mark = EnvironmentObservation.evidenceMark(event.confidence)
                    add("【当前活动】$body（$mark）")
                }
            }
            if (recentEvents.isNotEmpty()) {
                add("【近期事件】" + recentEvents.take(3).joinToString("；") { it.summary })
            }
            if (recentChanges.isNotEmpty()) {
                add("【最近变化】" + recentChanges.joinToString("；") { it.text })
            }
            if (userFacts.isNotEmpty()) {
                add("【用户确认】" + userFacts.joinToString("；"))
            }
        }
        if (lines.isEmpty()) return "【当前环境】无稳定环境记录"
        val text = lines.joinToString("\n")
        return if (text.length <= limit) text else text.take(limit - 1) + "…"
    }

    fun compactLine(): String = promptBlock()

    fun eventThreadPrompt(limit: Int = 360): String {
        val lines = buildList {
            activeEvent?.let { event ->
                add(
                    "上一活动 id=${event.id} summary=${event.summary} " +
                        "confidence=${event.confidence} location=${event.location.ifBlank { event.to }} " +
                        "episodes=${event.episodeCount}",
                )
            }
            if (recentEvents.isNotEmpty()) {
                add("近期事件 " + recentEvents.take(3).joinToString("；") { "${it.id}:${it.summary}" })
            }
        }
        if (lines.isEmpty()) return ""
        val text = lines.joinToString("\n")
        return if (text.length <= limit) text else text.take(limit - 1) + "…"
    }

    fun stale(now: Long, maxAgeMs: Long = 5_000L): Boolean {
        if (currentBrief.isBlank()) return true
        if (updatedAt <= 0L) return true
        return now - updatedAt >= maxAgeMs
    }

    private fun clip(text: String, budget: Int): String {
        val trimmed = text.trim()
        return if (trimmed.length <= budget) trimmed else trimmed.take(budget - 1) + "…"
    }
}

object EnvironmentStore {
    @Volatile private var state: EnvironmentState = EnvironmentState()

    fun snapshot(): EnvironmentState = state

    fun replace(next: EnvironmentState) {
        state = next
    }

    fun update(block: (EnvironmentState) -> EnvironmentState): EnvironmentState {
        synchronized(this) {
            val next = block(state)
            state = next
            return next
        }
    }
}
