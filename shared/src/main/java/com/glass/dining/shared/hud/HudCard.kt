package com.glass.dining.shared.hud

import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.Store

data class HudCard(
    val title: String,
    val meta: String = "",
    val wait: String = "",
    val extra: String = "",
) {
    fun clipped(): HudCard {
        return copy(
            title = title.take(LINE),
            meta = meta.take(LINE),
            wait = wait.take(LINE),
            extra = extra.take(LINE),
        )
    }

    fun withExtra(text: String): HudCard {
        return copy(extra = text.take(LINE))
    }

    companion object {
        const val LINE = 16

        fun fromTalkText(text: String): HudCard {
            val lines = wrap(text, LINE, 4)
            return HudCard(
                title = lines.getOrElse(0) { "对话中" },
                meta = lines.getOrElse(1) { "" },
                wait = lines.getOrElse(2) { "" },
                extra = lines.getOrElse(3) { "" },
            ).clipped()
        }

        fun wrap(text: String, width: Int, maxLines: Int): List<String> {
            val clean = text.replace(Regex("[\\r\\n]+"), "").trim().ifBlank { return listOf("对话中") }
            val parts = clean.chunked(width)
            if (parts.size <= maxLines) return parts
            val kept = parts.take(maxLines).toMutableList()
            val last = kept.last()
            kept[kept.lastIndex] = if (last.length <= 1) "…" else last.dropLast(1) + "…"
            return kept
        }
        fun idle(): HudCard {
            return HudCard(
                title = "到店餐饮",
                meta = "点对话才收眼镜语音",
                wait = "点看店识别拍照",
            ).clipped()
        }

        fun listening(current: HudCard? = null): HudCard {
            return talkListening()
        }

        fun talkListening(): HudCard {
            return HudCard(
                title = "对话中",
                meta = "对着眼镜说话",
                wait = "我在听",
                extra = "",
            ).clipped()
        }

        fun talkThinking(heard: String): HudCard {
            return HudCard(
                title = "对话中",
                meta = heard,
                wait = "思考中",
                extra = "",
            ).clipped()
        }

        fun talkReply(heard: String, hud: String, speak: String): HudCard {
            return fromTalkText(speak.ifBlank { hud })
        }

        fun thinking(current: HudCard? = null): HudCard {
            return (current ?: idle()).withExtra("思考中")
        }

        fun shooting(current: HudCard? = null): HudCard {
            return (current ?: idle()).copy(
                wait = "正在拍照识别",
                extra = "请看向店招",
            ).clipped()
        }

        fun fromMatch(result: MatchResult): HudCard {
            return fromStore(result.store)
        }

        fun fromStore(store: Store): HudCard {
            val wait = when {
                !store.openNow -> "现在已打烊"
                store.waitMinutes <= 0 -> "现在不用排队"
                else -> "排队约${store.waitMinutes}分钟"
            }
            val deal = store.deals.firstOrNull()?.let { deal ->
                "团购¥${deal.price} ${deal.title}"
            }.orEmpty()
            return HudCard(
                title = store.shortName,
                meta = "${store.rating}分 · 人均${store.avgPrice}",
                wait = wait,
                extra = deal,
            ).clipped()
        }
    }
}
