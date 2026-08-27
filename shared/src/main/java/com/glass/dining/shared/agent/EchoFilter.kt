package com.glass.dining.shared.agent

/**
 * TTS 被眼镜麦听回去时，识别结果会像正在播的那句。
 * 用文本重叠判断回声，不要用 RMS 在播报期间抢话。
 */
object EchoFilter {
    fun isEcho(heard: String, spoken: String): Boolean {
        val a = normalize(heard)
        val b = normalize(spoken)
        if (a.isBlank()) return true
        if (b.isBlank()) return false
        if (a.length >= 2 && b.contains(a)) return true
        if (b.length >= 2 && a.contains(b) && a.length <= b.length + 4) return true
        val overlap = longestOverlap(a, b)
        return overlap >= 2 && overlap * 2 >= a.length
    }

    fun isUserPartial(heard: String, spoken: String): Boolean {
        val a = normalize(heard)
        if (hanCount(a) < 2) return false
        return !isEcho(a, spoken)
    }

    fun normalize(raw: String): String {
        return buildString(raw.length) {
            raw.forEach { ch ->
                when {
                    ch.isWhitespace() -> Unit
                    ch in "，。！？、；：,.!?;:\"'“”‘’…—-·" -> Unit
                    else -> append(ch.lowercaseChar())
                }
            }
        }
    }

    private fun hanCount(text: String): Int {
        return text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    }

    private fun longestOverlap(a: String, b: String): Int {
        var best = 0
        val maxLen = minOf(a.length, b.length)
        var len = maxLen
        while (len >= 2) {
            var start = 0
            while (start + len <= a.length) {
                if (b.contains(a.substring(start, start + len))) {
                    return len
                }
                start += 1
            }
            len -= 1
            if (len < best) break
        }
        return best
    }
}
