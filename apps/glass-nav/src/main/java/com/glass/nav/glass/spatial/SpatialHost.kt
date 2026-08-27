package com.glass.nav.glass.spatial

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.glass.dining.shared.indoor.OccupancyGrid
import com.glass.dining.shared.indoor.Pose3
import com.glass.dining.shared.indoor.PoseSendCursor
import com.glass.dining.shared.indoor.PoseSendGate
import com.glass.dining.shared.indoor.TrackQuality
import com.glass.dining.shared.nav.NavPose

class SpatialHost(private val context: Context) {
    val hub = CameraFrameHub(context)
    val imu = ImuSampleSource(context)
    val tracker = SpatialTracker()
    val occupancy = OccupancyGrid()
    val renderer = WorldAnchorRenderer()
    @Volatile var running: Boolean = false
        private set
    private var wifiCursor = PoseSendCursor()
    private var cxrCursor = PoseSendCursor()

    fun start() {
        if (running) return
        SensorProbe.dump(context, imu)
        tracker.reset()
        imu.onSample = { sample -> tracker.onImu(sample) }
        imu.start()
        hub.onGray = { tNs, w, h, gray, sharp ->
            tracker.onGray(w, h, gray, tNs, sharp)
            val pose = tracker.pose()
            occupancy.recenter(pose)
            if (tracker.quality() == TrackQuality.GOOD) occupancy.markCorridor(pose)
        }
        val status = hub.start()
        running = true
        Log.i(TAG, "spatial start $status gyro=${imu.gyroName}")
    }

    fun stop() {
        if (!running) return
        running = false
        hub.onGray = null
        imu.onSample = null
        hub.stop()
        imu.stop()
        tracker.reset()
        occupancy.recenter(Pose3())
        wifiCursor = PoseSendCursor()
        cxrCursor = PoseSendCursor()
        Log.i(TAG, "spatial stop")
    }

    fun ensureCamera(): String {
        if (!running) start()
        return if (hub.opened) "相机已开" else hub.lastError ?: "正在打开相机"
    }

    fun pose(): Pose3 = tracker.pose()
    fun quality(): TrackQuality = tracker.quality()
    fun navPose(): NavPose = tracker.navPose()

    fun shouldSendWifi(): Boolean {
        if (!running) return false
        val (ok, next) = PoseSendGate.wifi(
            wifiCursor,
            SystemClock.elapsedRealtime(),
            tracker.pose(),
            tracker.quality() == TrackQuality.LOST,
        )
        if (ok) wifiCursor = next
        return ok
    }

    fun shouldSendCxr(micOn: Boolean, navOn: Boolean): Boolean {
        if (!running) return false
        val (ok, next) = PoseSendGate.cxr(
            cxrCursor,
            SystemClock.elapsedRealtime(),
            tracker.pose(),
            tracker.quality() == TrackQuality.LOST,
            micOn = micOn,
            navOn = navOn,
        )
        if (ok) cxrCursor = next
        return ok
    }

    companion object {
        private const val TAG = "GlassNav"
    }
}
