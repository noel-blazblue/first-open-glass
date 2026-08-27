package com.glass.dining.shared.agent

/**
 * 无依赖 JSON 切片，供 VLM 嵌套对象解析。只抽已知键，不实现完整 JSON。
 */
object JsonLite {
    fun objectBody(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        return slice(raw, start, '{', '}')
    }

    fun child(body: String, key: String): String? {
        val needle = "\"$key\""
        var from = 0
        while (true) {
            val at = body.indexOf(needle, from)
            if (at < 0) return null
            var i = skipWs(body, at + needle.length)
            if (i < body.length && body[i] == ':') {
                i = skipWs(body, i + 1)
                if (i >= body.length) return null
                return when (body[i]) {
                    '{' -> slice(body, i, '{', '}')
                    '[' -> slice(body, i, '[', ']')
                    '"' -> quoted(body, i)
                    else -> token(body, i)
                }
            }
            from = at + 1
        }
    }

    fun known(body: String, keys: List<String>): Map<String, String> {
        return keys.associateWith { child(body, it).orEmpty() }
    }

    fun objects(arrayBody: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < arrayBody.length) {
            if (arrayBody[i] == '{') {
                val chunk = slice(arrayBody, i, '{', '}')
                out += chunk
                i += chunk.length
            } else {
                i++
            }
        }
        return out
    }

    fun splitList(raw: String?): List<String> {
        val text = raw.orEmpty().trim()
        if (text.isBlank() || text == "[]") return emptyList()
        if (text.startsWith("{") || text.contains("\"dir\"")) {
            return objects(text).mapNotNull { obj ->
                val label = child(obj, "label").orEmpty()
                val dir = child(obj, "dir").orEmpty()
                label.ifBlank { dir }.takeIf { it.isNotBlank() }
            }.take(8)
        }
        return text
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .take(8)
    }

    private fun slice(source: String, start: Int, open: Char, close: Char): String {
        var depth = 0
        var inStr = false
        var escape = false
        for (i in start until source.length) {
            val c = source[i]
            if (inStr) {
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        return source.substring(start)
    }

    private fun quoted(source: String, start: Int): String {
        var escape = false
        val out = StringBuilder()
        for (i in start + 1 until source.length) {
            val c = source[i]
            if (escape) {
                out.append(if (c == 'n') '\n' else c)
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') return out.toString()
            out.append(c)
        }
        return out.toString()
    }

    private fun token(source: String, start: Int): String {
        var i = start
        while (i < source.length && source[i] !in ",}]") i++
        return source.substring(start, i).trim()
    }

    private fun skipWs(source: String, start: Int): Int {
        var i = start
        while (i < source.length && source[i].isWhitespace()) i++
        return i
    }
}
