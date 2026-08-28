package com.glass.dining.shared.agent

/**
 * TTS 被眼镜麦听回去时，识别结果会像正在播的那句。
 * 只对齐已经/正在发出的窗口，不要拿尚未发声的全文误杀用户。
 */
object EchoFilter {
    fun isEcho(heard: String, audible: String): Boolean {
        val a = normalize(heard)
        val b = normalize(audible)
        if (a.isBlank()) return true
        if (b.isBlank()) return false
        if (a.length >= 2 && b.contains(a)) return true
        if (b.length >= 2 && a.contains(b) && a.length <= b.length + 4) return true
        val overlap = longestOverlap(a, b)
        return overlap >= 4 && overlap * 10 >= a.length * 7
    }

    fun isUserPartial(heard: String, audible: String): Boolean {
        val a = normalize(heard)
        if (hanCount(a) < 2) return false
        if (normalize(audible).isBlank()) return false
        return !isEcho(a, audible)
    }

    fun normalize(raw: String): String {
        return buildString(raw.length) {
            raw.forEach { ch ->
                when {
                    ch.isWhitespace() -> Unit
                    ch in "，。！？、；：,.!?;:\"'“”‘’…—-·*" -> Unit
                    else -> append(ch.lowercaseChar())
                }
            }
        }
    }

    private fun hanCount(text: String): Int {
        return text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    }

    private fun longestOverlap(a: String, b: String): Int {
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
        }
        return 0
    }
}
