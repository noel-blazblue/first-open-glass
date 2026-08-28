package com.glass.dining.glass.spatial

import com.glass.dining.shared.indoor.Pose3
import com.glass.dining.shared.indoor.TrackQuality
import com.glass.dining.shared.indoor.Vec3
import com.glass.dining.shared.indoor.VisualInertialFilter
import com.glass.dining.shared.indoor.VisualSample
import com.glass.dining.shared.nav.NavPose
import com.glass.dining.shared.vision.ImageQuality

class SpatialTracker {
    private var filter = VisualInertialFilter(SensorProbe.calibration)

    fun reset() {
        filter.reset()
    }

    fun onImu(sample: com.glass.dining.shared.indoor.ImuSample) {
        filter.ingestImu(sample)
    }

    fun onGray(width: Int, height: Int, gray: ByteArray, tNs: Long, sharpness: Double = 20.0) {
        val grid = ImageQuality.visualGrid(width, height, gray)
        filter.ingestVisual(VisualSample(tNs, grid, sharpness))
        SensorProbe.noteSync(tNs, filter.state.lastImuNs)
    }

    fun pose(): Pose3 = filter.pose()
    fun quality(): TrackQuality = filter.quality()

    fun navPose(): NavPose {
        val p = filter.pose()
        return NavPose(
            yaw = p.yawDeg,
            pitch = p.pitchDeg,
            roll = p.rollDeg,
            x = p.position.x,
            y = p.position.y,
            z = p.position.z,
            tracking = filter.quality().name.lowercase(),
            tNs = p.tNs,
        )
    }

    fun position(): Vec3 = filter.pose().position
}
