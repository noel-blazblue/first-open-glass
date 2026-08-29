package com.glass.dining.shared.link

/** 手机↔眼镜 CXR 命令通道。下行通用指令，不是导航专用。 */
object GlassLink {
    const val GLASS_PACKAGE = "com.glass.dining.glass"
    const val GLASS_ACTIVITY = ".GlassActivity"
    const val GLASS_ENTRY = "$GLASS_PACKAGE$GLASS_ACTIVITY"

    const val CHANNEL_DOWN = "rk_cmd"
    const val CHANNEL_UP = "rk_evt"

    const val CMD_HELLO = "hello"
    const val CMD_READY = "ready"
    const val CMD_HUD = "hud.card"
    const val CMD_STORE = "hud.store"
    const val CMD_DRAW = "hud.draw"
    const val CMD_CAMERA_ON = "camera.on"
    const val CMD_CAMERA_OFF = "camera.off"
    const val CMD_CAMERA_SNAP = "camera.snap"
    const val CMD_NAV_START = "nav.start"
    const val CMD_NAV_UPDATE = "nav.update"
    const val CMD_NAV_STOP = "nav.stop"
    const val CMD_NAV_GUIDE = "nav.guide"
    const val CMD_CALIBRATE = "calibrate"
    const val CMD_FRAME = "frame"
    const val CMD_FRAME_OK = "frame.ok"
    const val CMD_POSE = "pose"
    const val CMD_ERROR = "error"
    const val CMD_MIC_ON = "mic.on"
    const val CMD_MIC_OFF = "mic.off"
    const val CMD_PCM = "pcm"
    const val CMD_RTC_START = "rtc.start"
    const val CMD_RTC_STOP = "rtc.stop"
    const val CMD_RTC_READY = "rtc.ready"
    const val CMD_RTC_SDP = "rtc.sdp"
    const val CMD_RTC_ICE = "rtc.ice"
    const val CMD_RTC_STAT = "rtc.stat"
    const val CMD_P2P_OFFER = "p2p.offer"
    const val CMD_P2P_READY = "p2p.ready"
    const val CMD_P2P_STOP = "p2p.stop"
    const val CMD_P2P_FAIL = "p2p.fail"
    const val CMD_WIFI_KEEP = "wifi.keep"

    fun glassEntry(): String = GLASS_ENTRY
}
