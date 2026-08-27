package com.glass.nav.glass.spatial

import com.glass.dining.shared.indoor.OccupancyGrid
import com.glass.dining.shared.indoor.Pose3
import com.glass.dining.shared.indoor.SensorCalibration
import com.glass.dining.shared.indoor.TrackQuality
import com.glass.dining.shared.indoor.Vec3
import com.glass.dining.shared.indoor.WorldAnchor
import com.glass.dining.shared.nav.IndoorProtocol

class WorldAnchorRenderer {
    fun screens(
        pose: Pose3,
        quality: TrackQuality,
        waypoints: String,
        occupancy: OccupancyGrid,
        headingDeg: Float,
        viewW: Float,
        viewH: Float,
        calib: SensorCalibration,
    ): List<Pair<Float, Float>> {
        if (quality == TrackQuality.LOST) return emptyList()
        val parsed = IndoorProtocol.parseWaypoints(waypoints).map { Vec3(it[0], it[1], it[2]) }
        val world = parsed.ifEmpty { WorldAnchor.chevrons(pose, headingDeg, occupancy, quality) }
        return WorldAnchor.projectPath(world, pose, calib, viewW, viewH, quality)
    }
}
