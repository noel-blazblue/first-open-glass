package com.glass.dining.shared.nav

import org.json.JSONObject
import kotlin.math.max

object NavProtocol {
    const val GLASS_PACKAGE = "com.glass.nav.glass"
    const val GLASS_ACTIVITY = ".NavActivity"
    const val GLASS_ENTRY = "$GLASS_PACKAGE$GLASS_ACTIVITY"

    const val CHANNEL_DOWN = "rk_nav"
    const val CHANNEL_UP = "rk_evt"

    const val CMD_HELLO = "hello"
    const val CMD_READY = "ready"
    const val CMD_HUD = "hud.card"
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
    const val DC_POSE = "pose"
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

    const val PCM_SAMPLE_RATE = 16_000

    const val FRAME_INTERVAL_MS = 5_000L
    const val JPEG_WIDTH = 1280
    const val JPEG_HEIGHT = 720
    const val JPEG_QUALITY = 72
    const val SDP_CHUNK = 2400
    const val RTC_WIDTH = 1280
    const val RTC_HEIGHT = 720
    const val RTC_FPS = 15
    const val RTC_MAX_BITRATE = 2_500_000
    const val RTC_MIN_BITRATE = 1_200_000
    const val POSE_WIFI_TICK_MS = 200L

    const val SKILL_NONE = "none"
    const val SKILL_BROWSE = "browse"
    const val SKILL_NAV = "nav"
    const val SKILL_MENU = "menu"
    const val SKILL_COUPON = "coupon"
    const val SKILL_PAY = "pay"
    const val LAYOUT_TALK = "talk"
    const val LAYOUT_CARD = "card"
    const val LAYOUT_NAV = "nav"

    fun glassEntry(): String = GLASS_ENTRY

    fun hintJson(hint: NavHint): String {
        return JSONObject()
            .put("turn", hint.turn)
            .put("meters", hint.meters)
            .put("text", hint.text)
            .put("storeName", hint.storeName)
            .put("remaining", hint.remaining)
            .put("mode", hint.mode)
            .put("headingDeg", hint.headingDeg.toDouble())
            .put("elevationDeg", hint.elevationDeg.toDouble())
            .put("stage", hint.stage)
            .put("sessionId", hint.sessionId)
            .put("tracking", hint.tracking)
            .put("waypoints", hint.waypoints)
            .toString()
    }

    fun parseHint(raw: String?): NavHint {
        if (raw.isNullOrBlank()) return NavHint()
        return try {
            val obj = JSONObject(raw)
            NavHint(
                turn = obj.optString("turn", "straight"),
                meters = obj.optInt("meters", 0),
                text = obj.optString("text"),
                storeName = obj.optString("storeName"),
                remaining = obj.optInt("remaining", 0),
                mode = obj.optString("mode"),
                headingDeg = obj.optDouble("headingDeg").toFloat(),
                elevationDeg = obj.optDouble("elevationDeg").toFloat(),
                stage = obj.optString("stage"),
                sessionId = obj.optString("sessionId"),
                tracking = obj.optString("tracking"),
                waypoints = obj.optString("waypoints"),
            )
        } catch (_: Exception) {
            NavHint(text = raw.take(24))
        }
    }

    fun poseJson(pose: NavPose): String {
        return JSONObject()
            .put("yaw", pose.yaw.toDouble())
            .put("pitch", pose.pitch.toDouble())
            .put("roll", pose.roll.toDouble())
            .put("x", pose.x.toDouble())
            .put("y", pose.y.toDouble())
            .put("z", pose.z.toDouble())
            .put("tracking", pose.tracking)
            .put("tNs", pose.tNs)
            .toString()
    }

    fun parsePose(raw: String?): NavPose? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            NavPose(
                yaw = obj.optDouble("yaw").toFloat(),
                pitch = obj.optDouble("pitch").toFloat(),
                roll = obj.optDouble("roll").toFloat(),
                x = obj.optDouble("x").toFloat(),
                y = obj.optDouble("y").toFloat(),
                z = obj.optDouble("z").toFloat(),
                tracking = obj.optString("tracking"),
                tNs = obj.optLong("tNs"),
            )
        } catch (_: Exception) {
            raw.toFloatOrNull()?.let { NavPose(yaw = it) }
        }
    }

    fun rtcSdpChunks(type: String, sdp: String, size: Int = SDP_CHUNK): List<String> {
        val total = max(1, (sdp.length + size - 1) / size)
        return (0 until total).map { index ->
            val start = index * size
            val end = kotlin.math.min(sdp.length, start + size)
            JSONObject()
                .put("t", type)
                .put("i", index)
                .put("n", total)
                .put("p", sdp.substring(start, end))
                .toString()
        }
    }

    fun rtcIceJson(mid: String?, index: Int, candidate: String): String {
        return JSONObject()
            .put("mid", mid ?: "")
            .put("index", index)
            .put("candidate", candidate)
            .toString()
    }

    fun p2pOfferJson(offer: P2pOffer): String {
        return JSONObject()
            .put("ssid", offer.ssid)
            .put("pass", offer.passphrase)
            .put("goIp", offer.goIp)
            .put("mac", offer.goMac)
            .put("name", offer.goName)
            .put("attemptId", offer.attemptId)
            .toString()
    }

    fun parseP2pOffer(raw: String?): P2pOffer? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val ssid = obj.optString("ssid")
            val pass = obj.optString("pass")
            if (ssid.isBlank() || pass.isBlank()) {
                null
            } else {
                P2pOffer(
                    ssid = ssid,
                    passphrase = pass,
                    goIp = obj.optString("goIp"),
                    goMac = obj.optString("mac"),
                    goName = obj.optString("name"),
                    attemptId = obj.optString("attemptId"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun p2pReadyJson(ip: String, attemptId: String = ""): String {
        return JSONObject()
            .put("ip", ip)
            .put("attemptId", attemptId)
            .toString()
    }

    fun parseP2pReady(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            JSONObject(raw).optString("ip")
        } catch (_: Exception) {
            ""
        }
    }

    fun parseP2pAttemptId(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            JSONObject(raw).optString("attemptId")
        } catch (_: Exception) {
            ""
        }
    }

    fun p2pFailJson(reason: String, attemptId: String = ""): String {
        return JSONObject()
            .put("reason", reason)
            .put("attemptId", attemptId)
            .toString()
    }

    fun parseP2pFail(raw: String?): Pair<String, String> {
        if (raw.isNullOrBlank()) return "" to ""
        return try {
            val obj = JSONObject(raw)
            val reason = obj.optString("reason").ifBlank { raw }
            reason to obj.optString("attemptId")
        } catch (_: Exception) {
            raw to ""
        }
    }

    fun parseRtcIce(raw: String?): Triple<String, Int, String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            Triple(
                obj.optString("mid"),
                obj.optInt("index", 0),
                obj.optString("candidate"),
            )
        } catch (_: Exception) {
            null
        }
    }

    class SdpAssembler {
        private var type: String = ""
        private var total: Int = 0
        private var parts: Array<String?> = emptyArray()

        @Synchronized
        fun push(raw: String?): Pair<String, String>? {
            if (raw.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(raw)
                val nextType = obj.getString("t")
                val index = obj.getInt("i")
                val nextTotal = obj.getInt("n")
                val piece = obj.getString("p")
                if (nextType != type || nextTotal != total) {
                    type = nextType
                    total = nextTotal
                    parts = arrayOfNulls(nextTotal)
                }
                if (index !in 0 until total) return null
                parts[index] = piece
                if (parts.any { it == null }) return null
                val sdp = parts.joinToString("")
                type = ""
                total = 0
                parts = emptyArray()
                nextType to sdp
            } catch (_: Exception) {
                null
            }
        }
    }

    fun cardJson(
        title: String,
        meta: String = "",
        wait: String = "",
        extra: String = "",
        skill: String = SKILL_NONE,
        layout: String = LAYOUT_CARD,
        speech: String = "",
        turn: String = "",
        meters: Int = 0,
        remaining: Int = 0,
        mode: String = "",
        headingDeg: Float = 0f,
        elevationDeg: Float = 0f,
        stage: String = "",
        sessionId: String = "",
        tracking: String = "",
        waypoints: String = "",
        pose: String = "",
    ): String {
        return JSONObject()
            .put("title", title)
            .put("meta", meta)
            .put("wait", wait)
            .put("extra", extra)
            .put("skill", skill)
            .put("layout", layout)
            .put("speech", speech)
            .put("turn", turn)
            .put("meters", meters)
            .put("remaining", remaining)
            .put("mode", mode)
            .put("headingDeg", headingDeg.toDouble())
            .put("elevationDeg", elevationDeg.toDouble())
            .put("stage", stage)
            .put("sessionId", sessionId)
            .put("tracking", tracking)
            .put("waypoints", waypoints)
            .put("pose", pose)
            .toString()
    }

    fun parseCard(raw: String?): HudLines {
        if (raw.isNullOrBlank()) return HudLines.idle()
        return try {
            val obj = JSONObject(raw)
            val skill = obj.optString("skill", SKILL_NONE)
            val layout = obj.optString("layout").ifBlank {
                if (skill == SKILL_NAV) LAYOUT_NAV else LAYOUT_CARD
            }
            HudLines(
                title = obj.optString("title"),
                meta = obj.optString("meta"),
                wait = obj.optString("wait"),
                extra = obj.optString("extra"),
                skill = skill,
                layout = layout,
                speech = obj.optString("speech"),
                turn = obj.optString("turn"),
                meters = obj.optInt("meters", 0),
                remaining = obj.optInt("remaining", 0),
                mode = obj.optString("mode"),
                headingDeg = obj.optDouble("headingDeg").toFloat(),
                elevationDeg = obj.optDouble("elevationDeg").toFloat(),
                stage = obj.optString("stage"),
                sessionId = obj.optString("sessionId"),
                tracking = obj.optString("tracking"),
                waypoints = obj.optString("waypoints"),
                pose = obj.optString("pose"),
            )
        } catch (_: Exception) {
            HudLines(speech = raw.take(24), layout = LAYOUT_TALK)
        }
    }

    fun wrapSpeech(text: String, width: Int = 14, maxLines: Int = 3): List<String> {
        val clean = text.replace(Regex("[\\r\\n]+"), "").trim().ifBlank { "Hi, 我在听" }
        val parts = clean.chunked(width)
        if (parts.size <= maxLines) return parts
        val kept = parts.take(maxLines).toMutableList()
        val last = kept.last()
        kept[kept.lastIndex] = if (last.length <= 1) "…" else last.dropLast(1) + "…"
        return kept
    }

    fun scriptFor(storeName: String, distanceMeters: Int): List<NavHint> {
        val d = distanceMeters.coerceIn(16, 200)
        return listOf(
            NavHint("left", max(6, d / 8), "出电梯后左转", storeName),
            NavHint("straight", max(10, d / 2), "沿走廊再走约${max(10, d / 2)}米", storeName),
            NavHint("left", max(4, d / 10), "左手第三家就是目的店", storeName),
            NavHint("arrive", 0, "已到门口，停下即可", storeName),
        )
    }

    val indoorScript: List<NavHint> = emptyList()
}

data class P2pOffer(
    val ssid: String,
    val passphrase: String,
    val goIp: String = "",
    val goMac: String = "",
    val goName: String = "",
    val attemptId: String = "",
)

data class NavHint(
    val turn: String = "straight",
    val meters: Int = 0,
    val text: String = "跟着走",
    val storeName: String = "",
    val remaining: Int = 0,
    val mode: String = "",
    val headingDeg: Float = 0f,
    val elevationDeg: Float = 0f,
    val stage: String = "",
    val sessionId: String = "",
    val tracking: String = "",
    val waypoints: String = "",
) {
    val visual: Boolean
        get() = mode.isNotBlank() && tracking != "tracking_lost"
}

data class NavPose(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val tracking: String = "",
    val tNs: Long = 0L,
)

data class HudLines(
    val title: String = "",
    val meta: String = "",
    val wait: String = "",
    val extra: String = "",
    val skill: String = NavProtocol.SKILL_NONE,
    val layout: String = NavProtocol.LAYOUT_TALK,
    val speech: String = "Hi, 我在听",
    val turn: String = "",
    val meters: Int = 0,
    val remaining: Int = 0,
    val mode: String = "",
    val headingDeg: Float = 0f,
    val elevationDeg: Float = 0f,
    val stage: String = "",
    val sessionId: String = "",
    val tracking: String = "",
    val waypoints: String = "",
    val pose: String = "idle",
) {
    val isTalk: Boolean
        get() = layout == NavProtocol.LAYOUT_TALK
    val isNav: Boolean
        get() = layout == NavProtocol.LAYOUT_NAV || skill == NavProtocol.SKILL_NAV
    val visual: Boolean
        get() = isNav && mode.isNotBlank() && tracking != "tracking_lost"

    companion object {
        fun idle(): HudLines = HudLines()

        fun fromHint(hint: NavHint): HudLines {
            val turnLabel = when (hint.turn) {
                "left" -> "左转"
                "right" -> "右转"
                "arrive" -> "到了"
                else -> "直行"
            }
            val meta = if (hint.turn == "arrive" || hint.meters <= 0) {
                turnLabel
            } else {
                "$turnLabel ${hint.meters}米"
            }
            return HudLines(
                title = "去${hint.storeName.ifBlank { "目的店" }}".take(16),
                meta = meta,
                wait = hint.text.ifBlank { "跟着走" }.take(16),
                extra = if (hint.turn == "arrive") "可以说取消" else if (hint.remaining > 0) "剩余${hint.remaining}米" else "",
                skill = NavProtocol.SKILL_NAV,
                layout = NavProtocol.LAYOUT_NAV,
                speech = "",
                turn = hint.turn,
                meters = hint.meters,
                remaining = hint.remaining,
                mode = hint.mode,
                headingDeg = hint.headingDeg,
                elevationDeg = hint.elevationDeg,
                stage = hint.stage,
                sessionId = hint.sessionId,
                tracking = hint.tracking,
                waypoints = hint.waypoints,
            )
        }
    }
}
