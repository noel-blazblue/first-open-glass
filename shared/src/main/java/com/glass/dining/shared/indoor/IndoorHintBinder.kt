package com.glass.dining.shared.indoor

import com.glass.dining.shared.nav.LandmarkHint
import kotlin.math.abs

data class IndoorGuide(
    val turn: String,
    val meters: Int,
    val text: String,
    val mode: String,
    val headingDeg: Float,
    val elevationDeg: Float,
    val stage: String,
    val tracking: String,
    val arrived: Boolean,
    val scanRequired: Boolean,
    val waypoints: List<Vec3>,
)

object IndoorHintBinder {
    fun bind(
        ocr: LandmarkHint?,
        explore: ExploreHint,
        pose: Pose3,
        occupancy: OccupancyGrid,
        quality: TrackQuality,
        storeName: String,
        remaining: Int,
        stage: String,
    ): IndoorGuide {
        val arrived = explore.arrived || ocr?.turn == "arrive"
        val heading = when {
            arrived -> 0f
            ocr != null && abs(ocr.headingDeg) > 4f -> ocr.headingDeg
            else -> explore.headingDeg
        }
        val leaving = stage == "seek_exit"
        val mode = when {
            arrived -> "arrive"
            leaving -> explore.mode.ifBlank { ocr?.mode.orEmpty() }.ifBlank { "ground" }
            explore.mode.isNotBlank() -> explore.mode
            else -> ocr?.mode.orEmpty().ifBlank { "ground" }
        }
        val text = when {
            arrived -> explore.text.ifBlank { ocr?.text }.orEmpty().ifBlank { "跟着走" }
            leaving -> ocr?.text?.ifBlank { explore.text }.orEmpty().ifBlank { "找出口出门" }
            else -> explore.text.ifBlank { ocr?.text.orEmpty() }.ifBlank { "跟着走" }
        }
        val turn = when {
            arrived -> "arrive"
            explore.turn.isNotBlank() -> explore.turn
            else -> ocr?.turn ?: "straight"
        }
        val pts = if (!arrived) {
            WorldAnchor.chevrons(pose, heading, occupancy, quality)
        } else {
            emptyList()
        }
        val meters = when {
            arrived -> 0
            explore.meters > 0 -> explore.meters
            (ocr?.meters ?: 0) > 0 -> ocr?.meters ?: 0
            else -> 0
        }
        return IndoorGuide(
            turn = turn,
            meters = meters,
            text = text,
            mode = mode,
            headingDeg = heading,
            elevationDeg = ocr?.elevationDeg ?: 0f,
            stage = stage,
            tracking = explore.tracking,
            arrived = arrived,
            scanRequired = explore.scanRequired,
            waypoints = pts,
        )
    }
}

object OccupancyCue {
    fun apply(occupancy: OccupancyGrid, pose: Pose3, observation: SemanticObservation) {
        occupancy.recenter(pose)
        if (observation.blocked) {
            occupancy.markBlocked(pose)
        } else if (observation.kind == NodeKind.CORRIDOR ||
            observation.kind == NodeKind.JUNCTION ||
            observation.kind == NodeKind.SIGNAGE ||
            observation.kind == NodeKind.ENTRANCE
        ) {
            occupancy.markCorridor(pose)
        }
    }
}
