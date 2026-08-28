package com.glass.dining.shared.agent

enum class TtsPlayState { IDLE, SYNTHESIZING, AUDIBLE, DRAINING }

class TtsSession {
    @Volatile var generation: Int = 0
        private set
    @Volatile var state: TtsPlayState = TtsPlayState.IDLE
        private set
    @Volatile var priority: SpeechPriority = SpeechPriority.LOW
        private set
    @Volatile var audibleText: String = ""
        private set
    @Volatile var queuedText: String = ""
        private set

    val speaking: Boolean get() = state != TtsPlayState.IDLE
    val audible: Boolean get() = state == TtsPlayState.AUDIBLE || state == TtsPlayState.DRAINING
    val echoWindow: String get() = if (audible) audibleText else ""
    val lookahead: String get() = TtsRouter.leftover(queuedText, audibleText).take(8)

    fun unplayed(): String = TtsRouter.leftover(queuedText, audibleText)

    fun begin(priority: SpeechPriority, flush: Boolean): Int? {
        if (flush) {
            if (speaking && priority.rank < this.priority.rank) return null
            bump()
            this.priority = priority
            state = TtsPlayState.SYNTHESIZING
            return generation
        }
        if (state == TtsPlayState.IDLE) {
            this.priority = priority
            state = TtsPlayState.SYNTHESIZING
        } else if (priority.rank < this.priority.rank) {
            return null
        }
        return generation
    }

    fun appendQueued(text: String) {
        queuedText += text
    }

    fun markAudible(gen: Int, text: String) {
        if (gen != generation) return
        audibleText = text
        if (state == TtsPlayState.SYNTHESIZING || state == TtsPlayState.IDLE) {
            state = TtsPlayState.AUDIBLE
        }
    }

    fun markDraining(gen: Int) {
        if (gen != generation) return
        if (state == TtsPlayState.AUDIBLE) state = TtsPlayState.DRAINING
    }

    fun markIdle(gen: Int) {
        if (gen != generation) return
        state = TtsPlayState.IDLE
        priority = SpeechPriority.LOW
        audibleText = ""
        queuedText = ""
    }

    fun bump(): Int {
        generation += 1
        state = TtsPlayState.IDLE
        priority = SpeechPriority.LOW
        audibleText = ""
        queuedText = ""
        return generation
    }
}

data class SubtitleWord(
    val text: String,
    val beginMs: Int,
    val endMs: Int,
    val beginIndex: Int,
    val endIndex: Int,
    val sentence: Boolean = false,
)

object TtsTimeline {
    fun audiblePrefix(playedMs: Long, words: List<SubtitleWord>, sentence: String): String {
        val chars = words.filter { !it.sentence && it.text.isNotBlank() && it.beginMs <= playedMs }
        if (chars.isEmpty()) {
            if (sentence.isBlank() || playedMs <= 0L) return ""
            return sentence
        }
        val end = chars.maxOf { it.endIndex }.coerceIn(0, sentence.length)
        return sentence.take(end)
    }

    fun estimatePrefix(playedFrames: Int, totalFrames: Int, text: String): String {
        if (text.isEmpty() || totalFrames <= 0 || playedFrames <= 0) return ""
        val n = (text.length.toLong() * playedFrames / totalFrames).toInt().coerceIn(0, text.length)
        return text.take(n)
    }
}
