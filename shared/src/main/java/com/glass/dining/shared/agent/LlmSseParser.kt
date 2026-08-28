package com.glass.dining.shared.agent

object LlmSseParser {
    fun parseLine(line: String): LlmStreamDelta? {
        val payload = when {
            line.startsWith("data:") -> line.removePrefix("data:").trim()
            else -> return null
        }
        return parsePayload(payload)
    }

    fun parsePayload(payload: String): LlmStreamDelta? {
        if (payload.isEmpty()) return null
        if (payload == "[DONE]") return LlmStreamDelta(done = true)
        val deltaBlock = objectAfter(payload, "delta") ?: ""
        val finish = jsonString(payload, "finish_reason").ifBlank {
            jsonBare(payload, "finish_reason")
        }.let { if (it == "null") "" else it }
        val callsRaw = objectAfter(deltaBlock, "tool_calls") ?: ""
        return LlmStreamDelta(
            content = jsonString(deltaBlock, "content"),
            reasoning = jsonString(deltaBlock, "reasoning_content"),
            toolCalls = parseToolCalls(callsRaw),
            finishReason = finish,
            done = finish.isNotBlank(),
        )
    }

    private fun parseToolCalls(raw: String): List<LlmToolCallDelta> {
        if (raw.isBlank()) return emptyList()
        return splitObjects(raw).mapIndexed { i, obj ->
            LlmToolCallDelta(
                index = jsonBare(obj, "index").toIntOrNull() ?: i,
                id = jsonString(obj, "id"),
                name = jsonString(obj, "name"),
                argumentsFragment = jsonString(obj, "arguments"),
            )
        }
    }

    private fun jsonString(raw: String, key: String): String {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(raw) ?: return ""
        return unescape(match.groupValues[1])
    }

    private fun jsonBare(raw: String, key: String): String {
        return Regex("\"${Regex.escape(key)}\"\\s*:\\s*([^,}\\]\\s]+)")
            .find(raw)
            ?.groupValues
            ?.get(1)
            ?.trim()
            .orEmpty()
    }

    private fun objectAfter(raw: String, key: String): String? {
        val token = "\"$key\""
        val at = raw.indexOf(token)
        if (at < 0) return null
        val colon = raw.indexOf(':', at + token.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < raw.length && raw[i].isWhitespace()) i += 1
        if (i >= raw.length) return null
        return when (raw[i]) {
            '{' -> sliceBalanced(raw, i, '{', '}')
            '[' -> sliceBalanced(raw, i, '[', ']')
            else -> null
        }
    }

    private fun sliceBalanced(raw: String, start: Int, open: Char, close: Char): String {
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until raw.length) {
            val ch = raw[i]
            if (inString) {
                when {
                    escape -> escape = false
                    ch == '\\' -> escape = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    open -> depth += 1
                    close -> {
                        depth -= 1
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
        }
        return raw.substring(start)
    }

    private fun splitObjects(raw: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < raw.length) {
            val start = raw.indexOf('{', i)
            if (start < 0) break
            val obj = sliceBalanced(raw, start, '{', '}')
            out.add(obj)
            i = start + obj.length
        }
        return out
    }

    private fun unescape(value: String): String {
        return buildString(value.length) {
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                if (ch == '\\' && i + 1 < value.length) {
                    when (val next = value[i + 1]) {
                        'n' -> append('\n')
                        't' -> append('\t')
                        'r' -> append('\r')
                        '"' -> append('"')
                        '\\' -> append('\\')
                        else -> append(next)
                    }
                    i += 2
                } else {
                    append(ch)
                    i += 1
                }
            }
        }
    }
}
