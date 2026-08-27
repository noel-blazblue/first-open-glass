package com.glass.dining.shared.indoor

/**
 * 用当前 VIO 位姿把世界点投到 HUD。跟踪丢失时返回空，不画假箭头。
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
        if (quality == TrackQuality.LOST) return emptyList()
        return points.mapNotNull { project(it, pose, calib, viewW, viewH) }
    }

    fun chevrons(
        pose: Pose3,
        headingDeg: Float,
        occupancy: OccupancyGrid,
        quality: TrackQuality,
    ): List<Vec3> {
        if (quality == TrackQuality.LOST) return emptyList()
        if (occupancy.read(pose.position).kind == CellKind.UNKNOWN) {
            occupancy.recenter(pose)
        }
        val pts = LocalPath.conservative(occupancy, pose, headingDeg)
        return if (pts.isNotEmpty()) pts else LocalPath.CHEVRON_M.map { meters ->
            pointAhead(pose, meters, headingDeg)
        }
    }
}
