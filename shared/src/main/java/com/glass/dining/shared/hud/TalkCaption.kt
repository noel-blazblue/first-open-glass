package com.glass.dining.shared.hud

data class TalkLine(
    val text: String,
    val x: Float,
    val y: Float,
    val alpha: Float,
    val fromBottom: Int,
)

/** 字幕折行、只留最近几行，上一行渐隐。 */
object TalkCaption {
    fun chunks(text: String, width: Int): List<String> {
        val clean = text.replace(Regex("[\\r\\n]+"), "").trim().ifBlank { "Hi, 我在听" }
        return clean.chunked(width).ifEmpty { listOf("Hi, 我在听") }
    }

    fun fade(fromBottom: Int, total: Int, progress: Float = 1f): Float {
        if (total <= 1 || fromBottom == 0) return 1f
        val end = if (fromBottom == 1) TalkLayout.FADE_NEAR else TalkLayout.FADE_FAR
        val t = progress.coerceIn(0f, 1f)
        return TalkLayout.FADE_START * (1f - t) + end * t
    }

    fun visibleTexts(
        text: String,
        width: Int = TalkLayout.speechColumns(),
        maxLines: Int = TalkLayout.SPEECH_LINES,
    ): List<String> {
        val all = chunks(text, width)
        return if (all.size <= maxLines) all else all.takeLast(maxLines)
    }

    fun lines(
        w: Float,
        h: Float,
        speech: String,
        descent: Float = 0f,
        width: Int = TalkLayout.speechColumns(w),
        maxLines: Int = TalkLayout.SPEECH_LINES,
        scroll: Float = 1f,
    ): List<TalkLine> {
        val all = chunks(speech, width)
        val shown = if (all.size <= maxLines) all else all.takeLast(maxLines)
        val x = TalkLayout.textCenterX(w)
        return shown.mapIndexed { index, line ->
            val fromBottom = shown.lastIndex - index
            TalkLine(
                text = line,
                x = x,
                y = TalkLayout.lineBaselineY(w, h, fromBottom, descent, scroll),
                alpha = fade(fromBottom, all.size, scroll),
                fromBottom = fromBottom,
            )
        }
    }

    fun key(lines: List<TalkLine>): String {
        val ghosts = lines.filter { it.fromBottom > 0 }.joinToString("\n") { it.text }
        return "$ghosts#${lines.size}"
    }
}
