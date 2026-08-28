package com.glass.dining.shared.hud

import com.glass.dining.shared.place.PlaceFacts
import com.glass.dining.shared.model.Coupon
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.MenuItem
import com.glass.dining.shared.model.Store

data class HudCard(
    val title: String = "",
    val meta: String = "",
    val wait: String = "",
    val extra: String = "",
    val skill: String = "none",
    val layout: String = LAYOUT_CARD,
    val speech: String = "",
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
    val pose: String = POSE_IDLE,
) {
    val isTalk: Boolean
        get() = layout == LAYOUT_TALK
    val isNav: Boolean
        get() = layout == LAYOUT_NAV || skill == "nav"
    val visual: Boolean
        get() = isNav && mode.isNotBlank() && tracking != "tracking_lost"

    fun clipped(): HudCard {
        if (isTalk) {
            return copy(speech = speech.replace(Regex("[\\r\\n]+"), "").trim().take(SPEECH))
        }
        if (isNav) {
            return copy(
                title = title.take(LINE),
                meta = meta.take(LINE),
                wait = wait.take(LINE),
                extra = extra.take(LINE),
            )
        }
        return copy(
            title = title.take(LINE),
            meta = meta.take(LINE),
            wait = wait.take(LINE),
            extra = extra.take(LINE),
        )
    }

    fun withExtra(text: String): HudCard {
        return if (isTalk) {
            copy(speech = text.take(SPEECH))
        } else {
            copy(extra = text.take(LINE))
        }
    }

    companion object {
        const val LINE = 16
        const val SPEECH = 80
        const val SPEECH_WIDTH = 14
        const val SPEECH_LINES = 3
        const val LAYOUT_TALK = "talk"
        const val LAYOUT_CARD = "card"
        const val LAYOUT_NAV = "nav"
        const val POSE_IDLE = "idle"
        const val POSE_LISTEN = "listen"
        const val POSE_THINK = "think"
        const val POSE_SPEAK = "speak"
        const val POSE_LOOK = "look"

        fun wrap(text: String, width: Int, maxLines: Int): List<String> {
            val clean = text.replace(Regex("[\\r\\n]+"), "").trim().ifBlank { return emptyList() }
            val parts = clean.chunked(width)
            if (parts.size <= maxLines) return parts
            val kept = parts.take(maxLines).toMutableList()
            val last = kept.last()
            kept[kept.lastIndex] = if (last.length <= 1) "…" else last.dropLast(1) + "…"
            return kept
        }

        fun wrapSpeech(text: String, width: Int = SPEECH_WIDTH, maxLines: Int = SPEECH_LINES): List<String> {
            val lines = wrap(text.ifBlank { "Hi, 我在听" }, width, maxLines)
            return if (lines.isEmpty()) listOf("Hi, 我在听") else lines
        }

        fun talk(speech: String, skill: String = "none", pose: String = POSE_IDLE): HudCard {
            val line = speech.replace(Regex("[\\r\\n]+"), "").trim().ifBlank { "Hi, 我在听" }
            return HudCard(
                skill = skill,
                layout = LAYOUT_TALK,
                speech = line.take(SPEECH),
                pose = pose,
            )
        }

        fun idle(): HudCard {
            return talk("Hi, 我在听", pose = POSE_IDLE)
        }

        fun listening(
            match: MatchResult? = null,
            heard: String = "",
            navCard: HudCard? = null,
        ): HudCard {
            if (navCard != null && navCard.skill == "nav") return navCard
            if (heard.isNotBlank()) return talk(heard, pose = POSE_LISTEN)
            if (match != null) return fromStore(match.store)
            return talk("Hi, 我在听", pose = POSE_LISTEN)
        }

        fun thinking(
            match: MatchResult? = null,
            heard: String = "",
            navCard: HudCard? = null,
        ): HudCard {
            if (navCard != null && navCard.skill == "nav") return navCard
            return talk("思考中", pose = POSE_THINK)
        }

        fun answering(
            match: MatchResult?,
            partial: String,
            done: Boolean,
            navCard: HudCard? = null,
        ): HudCard {
            if (navCard != null && navCard.skill == "nav") return navCard
            val line = partial.ifBlank { if (done) "Hi, 我在听" else "思考中" }
            val pose = when {
                done && partial.isBlank() -> POSE_IDLE
                partial.isBlank() -> POSE_THINK
                else -> POSE_SPEAK
            }
            return talk(line, pose = pose)
        }

        fun talkListening(): HudCard {
            return listening()
        }

        fun talkThinking(heard: String): HudCard {
            return thinking(heard = heard)
        }

        fun talkReply(heard: String, hud: String, speak: String): HudCard {
            return talk(speak.ifBlank { hud }.ifBlank { "Hi, 我在听" }, pose = POSE_SPEAK)
        }

        fun shooting(match: MatchResult? = null, current: HudCard? = null): HudCard {
            if (match != null) {
                return fromStore(match.store).copy(
                    wait = "正在拍照识别",
                    extra = "请看向店招",
                ).clipped()
            }
            if (current != null && !current.isTalk && current.skill == "browse") {
                return current.copy(
                    wait = "正在拍照识别",
                    extra = "请看向店招",
                ).clipped()
            }
            return talk("正在拍照", pose = POSE_LOOK)
        }

        fun fromMatch(result: MatchResult): HudCard {
            return fromStore(result.store)
        }

        fun fromRecommend(result: MatchResult): HudCard {
            val others = result.candidates.drop(1).joinToString("、") { it.shortName }
            val extra = if (others.isBlank()) {
                "说去哪家"
            } else {
                "还有$others"
            }
            return fromStore(result.store, extra)
        }

        fun fromMenu(storeName: String, items: List<MenuItem>): HudCard {
            val first = items.getOrNull(0)
            val second = items.getOrNull(1)
            return HudCard(
                title = storeName.ifBlank { "菜单" },
                meta = "菜单 ${items.size}道",
                wait = first?.let { "${it.name}¥${it.price}" }.orEmpty(),
                extra = second?.let { "${it.name}¥${it.price}" } ?: "可以说下一道",
                skill = "menu",
                layout = LAYOUT_CARD,
            ).clipped()
        }

        fun fromCoupons(storeName: String, coupons: List<Coupon>): HudCard {
            val first = coupons.firstOrNull()
            return HudCard(
                title = storeName.ifBlank { "美团券" },
                meta = if (coupons.isEmpty()) "这店暂无券" else "可核销${coupons.size}张",
                wait = first?.let { "${it.title}¥${it.price}" }.orEmpty(),
                extra = if (coupons.isEmpty()) "换一家或问团购" else "确认后才核销",
                skill = "coupon",
                layout = LAYOUT_CARD,
            ).clipped()
        }

        fun fromPayConfirm(storeName: String, amount: Int, qrType: String): HudCard {
            val channel = when (qrType) {
                "weixin" -> "微信"
                "alipay" -> "支付宝"
                else -> "付款码"
            }
            return HudCard(
                title = storeName.ifBlank { "买单" },
                meta = "应付¥$amount",
                wait = channel,
                extra = "确认才付款",
                skill = "pay",
                layout = LAYOUT_CARD,
            ).clipped()
        }

        fun fromPayResult(storeName: String, amount: Int): HudCard {
            return HudCard(
                title = storeName.ifBlank { "买单" },
                meta = "已付¥$amount",
                wait = "mock 成功",
                extra = "不是真扣款",
                skill = "browse",
                layout = LAYOUT_CARD,
            ).clipped()
        }

        fun fromRedeem(title: String, ok: Boolean): HudCard {
            return HudCard(
                title = title.ifBlank { "美团券" },
                meta = if (ok) "核销成功" else "核销失败",
                wait = "mock 回执",
                extra = if (ok) "给服务员看手机" else "换一张再试",
                skill = "browse",
                layout = LAYOUT_CARD,
            ).clipped()
        }

        fun observe(label: String): HudCard {
            return talk(label.take(LINE), skill = "observe", pose = POSE_LOOK)
        }

        fun fromStore(store: Store, extra: String? = null): HudCard {
            if (!store.catalogBacked) {
                val lines = if (extra.isNullOrBlank()) {
                    PlaceFacts.detailCard(store)
                } else {
                    PlaceFacts.listCard(store, extra)
                }
                return HudCard(
                    title = lines.title,
                    meta = lines.meta,
                    wait = lines.wait,
                    extra = lines.extra,
                    skill = "browse",
                    layout = LAYOUT_CARD,
                ).clipped()
            }
            val wait = when {
                !store.openNow -> "现在已打烊"
                store.waitMinutes <= 0 -> "现在不用排队"
                else -> "排队约${store.waitMinutes}分钟"
            }
            val deal = extra ?: store.deals.firstOrNull()?.let { deal ->
                "团购¥${deal.price} ${deal.title}"
            }.orEmpty()
            return HudCard(
                title = store.shortName,
                meta = "${store.rating}分 · 人均${store.avgPrice}",
                wait = wait,
                extra = deal,
                skill = "browse",
                layout = LAYOUT_CARD,
            ).clipped()
        }

        fun fromNav(
            storeName: String,
            turn: String,
            meters: Int,
            text: String,
            extra: String = "",
            remaining: Int = 0,
            mode: String = "",
            headingDeg: Float = 0f,
            elevationDeg: Float = 0f,
            stage: String = "",
            sessionId: String = "",
            tracking: String = "",
            waypoints: String = "",
        ): HudCard {
            val turnLabel = when (turn) {
                "left" -> "左转"
                "right" -> "右转"
                "arrive" -> "到了"
                else -> "直行"
            }
            val meta = if (turn == "arrive" || meters <= 0) {
                turnLabel
            } else {
                "$turnLabel ${meters}米"
            }
            val remain = extra.ifBlank {
                if (turn == "arrive") "可以说取消" else if (remaining > 0) "剩余${remaining}米" else ""
            }
            return HudCard(
                title = "去${storeName.ifBlank { "目的店" }}",
                meta = meta,
                wait = text.ifBlank { "跟着走" },
                extra = remain,
                skill = "nav",
                layout = LAYOUT_NAV,
                turn = turn,
                meters = meters,
                remaining = remaining,
                mode = mode,
                headingDeg = headingDeg,
                elevationDeg = elevationDeg,
                stage = stage,
                sessionId = sessionId,
                tracking = tracking,
                waypoints = waypoints,
            ).clipped()
        }
    }
}
