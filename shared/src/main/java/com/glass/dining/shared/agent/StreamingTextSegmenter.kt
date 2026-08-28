package com.glass.dining.shared.agent

/**
 * 把流式正文切成可送 TTS 的片段。只用通用标点和长度，不认业务词。
 * 非单调缩短视为迟到事件，丢弃；真正分叉由 [StreamingReplyAssembler] 显式替换。
 */
class StreamingTextSegmenter(
    private val maxChars: Int = 36,
    private val idleFlushChars: Int = 18,
) {
    private val unread = StringBuilder()
    private var seen = ""

    fun reset() {
        unread.clear()
        seen = ""
    }

    fun seenText(): String = seen

    fun consume(partial: String, idle: Boolean = false): List<String> {
        if (partial.isNotEmpty()) {
            when {
                seen.startsWith(partial) -> Unit
                partial.startsWith(seen) -> {
                    unread.append(partial.substring(seen.length))
                    seen = partial
                }
                else -> Unit
            }
        }
        val out = ArrayList<String>()
        drain(force = false, into = out)
        if (idle && unread.isNotBlank() && unread.length >= idleFlushChars) {
            drain(force = true, into = out)
        }
        return out
    }

    fun finish(): List<String> {
        val out = ArrayList<String>()
        drain(force = false, into = out)
        if (unread.isNotBlank()) drain(force = true, into = out)
        return out
    }

    private fun drain(force: Boolean, into: MutableList<String>) {
        val buf = StringBuilder()
        var i = 0
        while (i < unread.length) {
            val ch = unread[i]
            buf.append(ch)
            val split = ch in ENDS || buf.length >= maxChars
            if (split && buf.isNotBlank()) {
                into.add(buf.toString().trim())
                buf.clear()
            }
            i += 1
        }
        unread.clear()
        unread.append(buf)
        if (force && unread.isNotBlank()) {
            into.add(unread.toString().trim())
            unread.clear()
        }
    }

    companion object {
        private val ENDS = setOf('。', '！', '？', '；', '，', ',', '!', '?', '.', '\n')
    }
}

sealed class StreamSpeakAction {
    data class Speak(val chunks: List<String>) : StreamSpeakAction()
    data class Replace(val chunks: List<String>) : StreamSpeakAction()
}

/**
 * 把累积 partial / 结束全文收敛成只应开口一次的片段。
 */
class StreamingReplyAssembler(
    private val segmenter: StreamingTextSegmenter = StreamingTextSegmenter(),
) {
    fun reset() = segmenter.reset()

    fun onPartial(partial: String): List<String> = segmenter.consume(partial)

    fun onIdle(): List<String> = segmenter.consume("", idle = true)

    fun finishStep(): List<String> {
        val leftover = segmenter.finish()
        segmenter.reset()
        return leftover
    }

    fun onDone(finalText: String): StreamSpeakAction {
        val text = finalText.trim()
        val seen = segmenter.seenText()
        return when {
            text.isBlank() -> StreamSpeakAction.Speak(segmenter.finish())
            text == seen || text.startsWith(seen) -> {
                val extra = if (text.length > seen.length) segmenter.consume(text) else emptyList()
                StreamSpeakAction.Speak(extra + segmenter.finish())
            }
            seen.startsWith(text) -> StreamSpeakAction.Speak(segmenter.finish())
            else -> {
                segmenter.reset()
                StreamSpeakAction.Replace(segmenter.consume(text) + segmenter.finish())
            }
        }
    }
}

class TtsJobQueue(private val maxPrefetch: Int = 2) {
    data class Job(
        val index: Int,
        val text: String,
        var pcm: ByteArray? = null,
        var failed: Boolean = false,
        var synthesizing: Boolean = false,
        var played: Boolean = false,
    )

    private val jobs = ArrayList<Job>()
    private var nextIndex = 0

    val size: Int get() = jobs.size
    val pendingPlay: Int get() = jobs.count { !it.played }

    fun enqueue(text: String): Job {
        val job = Job(nextIndex, text)
        nextIndex += 1
        jobs.add(job)
        return job
    }

    fun toSynthesize(): List<Job> {
        val inflight = jobs.count { it.synthesizing && it.pcm == null && !it.failed }
        val room = (maxPrefetch - inflight).coerceAtLeast(0)
        if (room == 0) return emptyList()
        return jobs.filter { !it.synthesizing && it.pcm == null && !it.failed && !it.played }.take(room)
    }

    fun nextPlayable(): Job? {
        val next = jobs.firstOrNull { !it.played } ?: return null
        return if (next.pcm != null || next.failed) next else null
    }

    fun markPlayed(index: Int) {
        jobs.firstOrNull { it.index == index }?.played = true
    }

    fun finished(): Boolean = jobs.isNotEmpty() && jobs.all { it.played }

    fun unplayedText(): String = jobs.filter { !it.played }.joinToString("") { it.text }

    fun clear() {
        jobs.clear()
        nextIndex = 0
    }
}
