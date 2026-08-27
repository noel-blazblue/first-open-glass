package com.glass.dining.shared.agent

data class ObservedClaim(
    val id: String = "",
    val text: String = "",
    val evidence: String = "",
)

data class EventRelation(
    val relatedEventId: String = "",
    val type: String = "",
    val evidenceRefs: List<String> = emptyList(),
    val evidenceLevel: String = EnvironmentObservation.STATUS_INFERRED,
)

data class SpatialEvidence(
    val episodeId: String = "",
    val poseX: Float = 0f,
    val poseY: Float = 0f,
    val poseZ: Float = 0f,
    val yawDeg: Float = 0f,
    val tracking: String = "",
    val topologyNodeId: String = "",
    val topologyEdgeId: String = "",
    val loopClosed: Boolean = false,
    val movedMeters: Float = 0f,
) {
    val trackingLost: Boolean
        get() {
            val text = tracking.lowercase()
            return text.contains("lost")
        }

    val hasPose: Boolean
        get() = episodeId.isNotBlank() || poseX != 0f || poseY != 0f || poseZ != 0f

    companion object {
        fun from(episode: EnvironmentEpisode, extra: SpatialEvidence = SpatialEvidence()): SpatialEvidence {
            return extra.copy(
                episodeId = episode.id.ifBlank { extra.episodeId },
                poseX = episode.poseX,
                poseY = episode.poseY,
                poseZ = episode.poseZ,
                yawDeg = episode.yawDeg,
                tracking = episode.tracking.ifBlank { extra.tracking },
            )
        }
    }
}

data class EventProposal(
    val operation: String = "",
    val summary: String = "",
    val hypothesis: String = "",
    val observedClaims: List<ObservedClaim> = emptyList(),
    val inferredClaims: List<ObservedClaim> = emptyList(),
    val actors: List<String> = emptyList(),
    val objects: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val location: String = "",
    val relations: List<EventRelation> = emptyList(),
    val evidenceLevel: String = EnvironmentObservation.STATUS_OBSERVED,
    val confidence: Float = 0f,
)
