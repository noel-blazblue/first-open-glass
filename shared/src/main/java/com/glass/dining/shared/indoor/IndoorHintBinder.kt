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
        val mode = when {
            arrived -> "arrive"
            explore.tracking == "tracking_lost" || explore.scanRequired -> ""
            explore.mode.isNotBlank() -> explore.mode
            else -> ocr?.mode.orEmpty()
        }
        val text = explore.text.ifBlank { ocr?.text.orEmpty() }.ifBlank { "跟着走" }
        val turn = when {
            arrived -> "arrive"
            explore.turn.isNotBlank() -> explore.turn
            else -> ocr?.turn ?: "straight"
        }
        val pts = WorldAnchor.chevrons(pose, heading, occupancy, quality)
        return IndoorGuide(
            turn = turn,
            meters = if (arrived) 0 else (explore.meters.takeIf { it > 0 } ?: ocr?.meters ?: 6),
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
            observation.kind == NodeKind.SIGNAGE
        ) {
            occupancy.markCorridor(pose)
        }
    }
}
