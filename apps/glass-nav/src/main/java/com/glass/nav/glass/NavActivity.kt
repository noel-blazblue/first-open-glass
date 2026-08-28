package com.glass.nav.glass

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.glass.dining.shared.nav.HudLines
import com.glass.dining.shared.nav.NavHint
import com.glass.dining.shared.nav.NavProtocol
import com.glass.nav.glass.spatial.SpatialHost

class NavActivity : android.app.Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var hud: NavHudView
    private lateinit var imu: ImuTracker
    private lateinit var spatial: SpatialHost
    private lateinit var camera: NavCamera
    private lateinit var mic: GlassMic
    private lateinit var bridge: NavBridge

    @Volatile private var navActive: Boolean = false
    @Volatile private var snapOnce: Boolean = false
    @Volatile private var wantMic: Boolean = false
    @Volatile private var rtcActive: Boolean = false

    private val drawTick = object : Runnable {
        override fun run() {
            hud.pitchDeg = imu.pitchDegrees
            hud.pose = spatial.pose()
            hud.quality = spatial.quality()
            hud.invalidate()
            val gap = when {
                hud.card.visual || hud.card.isTalk -> 50L
                else -> 200L
            }
            main.postDelayed(this, gap)
        }
    }

    private val poseTick = object : Runnable {
        override fun run() {
            maybeSendPose()
            val gap = if (GlassRtc.poseOpen) NavProtocol.POSE_WIFI_TICK_MS else 1_000L
            main.postDelayed(this, gap)
        }
    }

    private fun maybeSendPose() {
        if (GlassRtc.poseOpen) {
            if (spatial.shouldSendWifi()) {
                GlassRtc.sendPose(spatial.navPose())
            }
            return
        }
        if (!spatial.shouldSendCxr(wantMic, navActive || hud.card.isNav)) return
        bridge.sendPose(spatial.navPose())
    }

    private val captureTick = object : Runnable {
        override fun run() {
            if (navActive && camera.opened) {
                val issued = camera.captureIfIdle()
                if (issued) {
                    main.removeCallbacks(frameTimeout)
                    main.postDelayed(frameTimeout, 8_000)
                }
            }
            if (navActive) {
                main.postDelayed(this, NavProtocol.FRAME_INTERVAL_MS)
            }
        }
    }

    private val frameTimeout = Runnable {
        Log.w(TAG, "frame ack timeout, allow next capture")
        camera.markIdle()
        bridge.markFrameIdle()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true)
        }
        hud = NavHudView(this)
        hud.card = HudLines.idle()
        setContentView(hud)
        GlassWifi.hold(this) { line ->
            hud.status = line
            hud.invalidate()
        }
        imu = ImuTracker(this)
        spatial = SpatialHost(this)
        hud.occupancy = spatial.occupancy
        camera = NavCamera(this)
        mic = GlassMic(this)
        camera.onJpeg = { bytes ->
            val ok = bridge.trySendFrame(bytes)
            if (!ok) {
                camera.markIdle()
            }
            if (snapOnce && !navActive) {
                snapOnce = false
                main.post { stopCamera() }
            }
            ok
        }
        bridge = NavBridge(
            onCard = { lines ->
                hud.card = lines
                hud.store = null
                hud.storeCaption = ""
                hud.invalidate()
            },
            onStore = { payload ->
                hud.store = payload.place
                hud.storeCaption = payload.caption
                hud.invalidate()
            },
            onHint = { hint ->
                showHint(hint)
                if (hint.storeName.isNotBlank() || hint.text.isNotBlank()) {
                    navActive = true
                    spatial.start()
                }
            },
            onCamera = { on ->
                if (on && rtcActive) {
                    hud.status = "视频流占用相机"
                } else if (on) {
                    navActive = true
                    spatial.start()
                    if (!rtcActive) openCamera()
                } else {
                    navActive = false
                    snapOnce = false
                    if (!rtcActive) {
                        stopCamera()
                        spatial.stop()
                    }
                }
            },
            onSnap = {
                if (rtcActive) {
                    stopRtc()
                    main.postDelayed({ snapPhoto() }, 500)
                } else {
                    snapPhoto()
                }
            },
            onMic = { on ->
                wantMic = on
                if (on) startMic() else stopMic()
            },
            onCalibrate = {
                imu.calibrate()
            },
            onStatus = { hud.status = it },
            onFrameAck = {
                main.removeCallbacks(frameTimeout)
                camera.markIdle()
            },
            onRtc = { cmd, json -> handleRtc(cmd, json) },
            onPhoneGone = {
                Log.i(TAG, "phone gone, release hud")
                finishAndRemoveTask()
            },
        )
        mic.onPcm = { pcm -> bridge.sendPcm(pcm) }
        bridge.start()
        requestDevicePermissions()
        val calib = com.glass.nav.glass.spatial.SensorProbe.dump(this, spatial.imu)
        Log.i(
            TAG,
            "idle hud, camera off, imu=${imu.sensorName} fx=${calib.camera.fx} ts=${calib.camera.timestampSource}",
        )
    }

    override fun onResume() {
        super.onResume()
        GlassWifi.ensure()
        imu.start()
        main.removeCallbacks(drawTick)
        main.post(drawTick)
        main.removeCallbacks(poseTick)
        main.post(poseTick)
        if (navActive && !rtcActive) {
            spatial.start()
            openCamera()
        }
        if (wantMic) {
            startMic()
        }
    }

    override fun onPause() {
        main.removeCallbacks(drawTick)
        main.removeCallbacks(poseTick)
        main.removeCallbacks(captureTick)
        main.removeCallbacks(frameTimeout)
        if (!isFinishing) {
            stopCamera()
            if (!rtcActive) spatial.stop()
            // P2P 入网会 pause/resume。对话还在听时停麦再开，会和旧 AudioRecord 叠成两路 PCM。
            if (!wantMic) stopMic()
        }
        imu.stop()
        super.onPause()
    }

    override fun onDestroy() {
        navActive = false
        snapOnce = false
        wantMic = false
        rtcActive = false
        GlassRtc.stop()
        GlassP2p.stop()
        stopCamera()
        stopMic()
        spatial.stop()
        imu.stop()
        super.onDestroy()
    }

    private fun showHint(hint: NavHint) {
        hud.card = HudLines.fromHint(hint)
        hud.invalidate()
    }

    private fun snapPhoto() {
        snapOnce = true
        if (camera.opened) {
            camera.captureIfIdle()
            return
        }
        openCamera()
        main.postDelayed({
            if (snapOnce) camera.captureIfIdle()
        }, 900)
    }

    private fun handleRtc(cmd: String, json: String?) {
        when (cmd) {
            NavProtocol.CMD_P2P_OFFER -> joinP2p(json)
            NavProtocol.CMD_P2P_STOP -> GlassP2p.stop()
            NavProtocol.CMD_WIFI_KEEP -> GlassWifi.ensure()
            NavProtocol.CMD_RTC_START -> startRtc()
            NavProtocol.CMD_RTC_STOP -> stopRtc()
            NavProtocol.CMD_RTC_SDP -> GlassRtc.onRemoteSdp(json)
            NavProtocol.CMD_RTC_ICE -> GlassRtc.onRemoteIce(json)
        }
    }

    private fun joinP2p(json: String?) {
        val offer = NavProtocol.parseP2pOffer(json)
        if (offer == null) {
            bridge.sendRtc(NavProtocol.CMD_P2P_FAIL, "p2p.offer 无效")
            return
        }
        hud.status = "正在连 Direct"
        GlassP2p.join(this, offer) { cmd, payload ->
            main.post { bridge.sendRtc(cmd, payload) }
        }
        Log.i(TAG, "p2p join ssid=${offer.ssid}")
    }

    private fun startRtc() {
        rtcActive = true
        snapOnce = false
        stopCamera()
        spatial.ensureCamera()
        hud.status = "视频流"
        GlassRtc.start(this, spatial.hub) { cmd, payload ->
            main.post { bridge.sendRtc(cmd, payload) }
        }
        Log.i(TAG, "rtc start requested")
    }

    private fun stopRtc() {
        rtcActive = false
        GlassRtc.stop()
        hud.status = ""
        if (!navActive) spatial.stop()
        Log.i(TAG, "rtc stop")
    }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            hud.status = "没有相机权限"
            bridge.sendError("没有相机权限")
            return
        }
        if (rtcActive || spatial.hub.opened || navActive) {
            spatial.ensureCamera()
            hud.status = if (navActive || rtcActive) "" else "正在打开相机"
            return
        }
        val status = camera.start()
        hud.status = if (navActive) "" else status
        main.removeCallbacks(captureTick)
        if (navActive) {
            main.postDelayed(captureTick, 800)
        }
    }

    private fun stopCamera() {
        main.removeCallbacks(captureTick)
        main.removeCallbacks(frameTimeout)
        camera.stop()
        camera.markIdle()
        bridge.markFrameIdle()
        hud.status = ""
    }

    private fun startMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            hud.status = "没有麦克风权限"
            bridge.sendError("没有麦克风权限")
            requestDevicePermissions()
            return
        }
        val status = mic.start()
        if (mic.lastError != null) {
            hud.status = status
            bridge.sendError(status)
        }
        Log.i(TAG, "mic $status")
    }

    private fun stopMic() {
        mic.stop()
    }

    private fun requestDevicePermissions() {
        val needed = buildList {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 11)
        }
    }

    companion object {
        private const val TAG = "GlassNav"
    }
}
