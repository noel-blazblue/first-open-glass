package com.glass.nav.glass.spatial

import android.content.Context
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

class HubVideoCapturer(private val hub: CameraFrameHub) : VideoCapturer {
    private var observer: CapturerObserver? = null

    override fun initialize(
        helper: SurfaceTextureHelper?,
        context: Context?,
        capturerObserver: CapturerObserver?,
    ) {
        observer = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, fps: Int) {
        val next = observer ?: return
        next.onCapturerStarted(true)
        hub.attachRtc(next)
    }

    override fun stopCapture() {
        hub.detachRtc()
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, fps: Int) = Unit

    override fun dispose() {
        hub.detachRtc()
        observer = null
    }

    override fun isScreencast(): Boolean = false
}
