package com.glass.dining.shared.agent

enum class TtsBackend { NONE, WS, REST, ANDROID }

/**
 * 一轮 generation 只走一个后端。WS 失败后本轮钉死 REST。
 */
object TtsRouter {
    const val REST_SYNTH_ATTEMPTS = 3

    fun choose(nlsReady: Boolean, streamingDenied: Boolean, current: TtsBackend): TtsBackend {
        if (!nlsReady) return TtsBackend.ANDROID
        if (current == TtsBackend.REST || streamingDenied) return TtsBackend.REST
        return TtsBackend.WS
    }

    fun leftover(queued: String, vararg heard: String): String {
        if (queued.isBlank()) return ""
        val audible = heard.maxByOrNull { it.length }.orEmpty()
        if (audible.isBlank()) return queued
        if (queued.startsWith(audible)) return queued.substring(audible.length)
        if (audible.startsWith(queued)) return ""
        var n = minOf(queued.length, audible.length)
        while (n > 0) {
            if (audible.endsWith(queued.take(n))) return queued.substring(n)
            n -= 1
        }
        return queued
    }

    fun restSlice(queued: String, covered: Int): Pair<String, Int> {
        val extra = if (covered >= queued.length) "" else queued.substring(covered.coerceAtLeast(0))
        return extra to queued.length
    }

    fun stickyDeny(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("permission") ||
            lower.contains("denied") ||
            lower.contains("free_trial_expired") ||
            message.contains("权限")
    }

    fun acceptPcm(pcm: ByteArray?): Boolean = pcm != null && pcm.isNotEmpty()
}

/**
 * NLS 流式合成的握手 / 收尾门闩。finish 可早于 SynthesisStarted。
 */
class StreamingSynthGate {
    @Volatile var started: Boolean = false
        private set
    @Volatile var completed: Boolean = false
        private set
    @Volatile var failed: Boolean = false
        private set
    @Volatile var pendingStop: Boolean = false
        private set

    fun acceptText(): Boolean = !completed && !failed

    fun requestStop(): Boolean {
        if (completed || failed) return false
        if (!started) {
            pendingStop = true
            return false
        }
        return true
    }

    fun onStarted(): Boolean {
        started = true
        if (!pendingStop) return false
        pendingStop = false
        return true
    }

    fun onCompleted() {
        completed = true
    }

    fun onFailed() {
        failed = true
    }
}
