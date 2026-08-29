package com.glass.dining.shared.link

import org.json.JSONObject

object HudWire {
    const val SKILL_NONE = "none"
    const val SKILL_BROWSE = "browse"
    const val SKILL_NAV = "nav"
    const val SKILL_MENU = "menu"
    const val SKILL_COUPON = "coupon"
    const val SKILL_PAY = "pay"
    const val LAYOUT_TALK = "talk"
    const val LAYOUT_CARD = "card"
    const val LAYOUT_NAV = "nav"

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
}

data class HudLines(
    val title: String = "",
    val meta: String = "",
    val wait: String = "",
    val extra: String = "",
    val skill: String = HudWire.SKILL_NONE,
    val layout: String = HudWire.LAYOUT_TALK,
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
        get() = layout == HudWire.LAYOUT_TALK
    val isNav: Boolean
        get() = layout == HudWire.LAYOUT_NAV || skill == HudWire.SKILL_NAV
    val visual: Boolean
        get() = isNav && mode.isNotBlank() && tracking != IndoorProtocol.TRACK_LOST

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
                title = "去${hint.storeName.ifBlank { "目的地" }}".take(16),
                meta = meta,
                wait = hint.text.ifBlank { "跟着走" }.take(16),
                extra = if (hint.turn == "arrive") "可以说取消" else if (hint.remaining > 0) "剩余${hint.remaining}米" else "",
                skill = HudWire.SKILL_NAV,
                layout = HudWire.LAYOUT_NAV,
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
