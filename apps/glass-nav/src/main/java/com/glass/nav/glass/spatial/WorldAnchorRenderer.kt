package com.glass.nav.glass.spatial

import com.glass.dining.shared.indoor.GroundChevron
import com.glass.dining.shared.indoor.GroundGuide
import com.glass.dining.shared.indoor.OccupancyGrid
import com.glass.dining.shared.indoor.Pose3
import com.glass.dining.shared.indoor.SensorCalibration
import com.glass.dining.shared.indoor.TrackQuality
import com.glass.dining.shared.indoor.WorldAnchor

class WorldAnchorRenderer {
    private var last: List<GroundChevron> = emptyList()

    fun screens(
        pose: Pose3,
        quality: TrackQuality,
        waypoints: String,
        occupancy: OccupancyGrid,
        headingDeg: Float,
        viewW: Float,
        viewH: Float,
        calib: SensorCalibration,
    ): List<GroundChevron> {
        if (waypoints.isBlank()) {
            last = emptyList()
            return emptyList()
        }
        val world = WorldAnchor.chevrons(pose, headingDeg, occupancy, quality)
        val drawn = GroundGuide.project(
            world = world,
            pose = pose,
            calib = calib,
            viewW = viewW,
            viewH = viewH,
            faded = quality == TrackQuality.LOST,
        )
        if (drawn.isNotEmpty()) {
            last = drawn
            return drawn
        }
        return last.map { it.copy(alpha = 0.45f) }
    }
}
