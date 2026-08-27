package com.glass.dining.shared.agent

/** 把工具 JSON 投影成可对用户说的话，过滤开发指令。 */
object ToolSpeak {
    fun forUser(raw: String, modelReady: Boolean): String {
        val message = field(raw, "message").ifBlank { field(raw, "error") }
        if (message.isBlank()) {
            return if (modelReady) "我看过工具结果了。" else "已处理。配好语言模型后能讲得更清楚。"
        }
        if (isDeveloper(message)) {
            return when {
                raw.contains("need_search") -> "我再搜一下附近。"
                raw.contains("empty_catalog") -> "本地目录没有合适的店，我去搜附近公开地点。"
                else -> "我先换个办法查一下。"
            }
        }
        return message
    }

    fun isDeveloper(text: String): Boolean {
        return text.contains("请调用") ||
            text.contains("search_nearby_places") ||
            text.contains("look_at_scene") ||
            text.contains("need_search") ||
            text.contains("retryable")
    }

    fun field(json: String, key: String): String {
        val token = "\"$key\""
        val start = json.indexOf(token)
        if (start < 0) return ""
        val colon = json.indexOf(':', start + token.length)
        if (colon < 0) return ""
        val from = json.indexOf('"', colon + 1)
        val to = json.indexOf('"', from + 1)
        if (from < 0 || to < 0) return ""
        return json.substring(from + 1, to)
    }
}
