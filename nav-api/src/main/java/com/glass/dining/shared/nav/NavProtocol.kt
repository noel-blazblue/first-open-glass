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

    const val PCM_SAMPLE_RATE = 16_000

    const val FRAME_INTERVAL_MS = 5_000L
    const val JPEG_WIDTH = 640
    const val JPEG_HEIGHT = 480
    const val JPEG_QUALITY = 55
    const val SDP_CHUNK = 2400
    const val RTC_WIDTH = 640
    const val RTC_HEIGHT = 480
    const val RTC_FPS = 8
    const val RTC_MAX_BITRATE = 600_000

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
            )
        } catch (_: Exception) {
            NavHint(text = raw.take(24))
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

    val indoorScript: List<NavHint> = scriptFor("海底捞三里屯店", 40)
}

data class NavHint(
    val turn: String = "straight",
    val meters: Int = 0,
    val text: String = "跟着走",
    val storeName: String = "",
    val remaining: Int = 0,
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
) {
    val isTalk: Boolean
        get() = layout == NavProtocol.LAYOUT_TALK
    val isNav: Boolean
        get() = layout == NavProtocol.LAYOUT_NAV || skill == NavProtocol.SKILL_NAV

    companion object {
        fun idle(): HudLines = HudLines()
    }
}
