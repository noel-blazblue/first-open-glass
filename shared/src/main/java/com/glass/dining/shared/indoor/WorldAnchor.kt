package com.glass.dining.shared.indoor

/**
 * 用当前 VIO 位姿把世界点投到 HUD。短跟踪丢失仍给出地面点，由绘制端沿用上一帧。
 */
object WorldAnchor {
    fun project(
        world: Vec3,
        pose: Pose3,
        calib: SensorCalibration,
        viewW: Float,
        viewH: Float,
    ): Pair<Float, Float>? {
        val cam = pose.inverseTransform(world)
        return calib.projectToHud(cam.x, cam.y, cam.z, viewW, viewH)
    }

    fun projectPath(
        points: List<Vec3>,
        pose: Pose3,
        calib: SensorCalibration,
        viewW: Float,
        viewH: Float,
        quality: TrackQuality,
    ): List<Pair<Float, Float>> {
        return GroundGuide.project(points, pose, calib, viewW, viewH, quality == TrackQuality.LOST)
            .map { it.x to it.y }
    }

    fun chevrons(
        pose: Pose3,
        headingDeg: Float,
        occupancy: OccupancyGrid,
        @Suppress("UNUSED_PARAMETER") quality: TrackQuality,
    ): List<Vec3> {
        if (occupancy.read(pose.position).kind == CellKind.UNKNOWN) {
            occupancy.recenter(pose)
        }
        val pts = LocalPath.conservative(occupancy, pose, headingDeg)
        return pts.ifEmpty {
            LocalPath.CHEVRON_M.map { meters -> pointAhead(pose, meters, headingDeg) }
        }
    }
}
