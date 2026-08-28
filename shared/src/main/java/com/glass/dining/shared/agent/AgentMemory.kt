package com.glass.dining.shared.agent

internal data class ConversationTurn(
    val user: LlmMessage,
    val followUp: List<LlmMessage>,
) {
    fun flatten(): List<LlmMessage> = listOf(user) + followUp
}

object AgentMemory {
    const val DEFAULT_MAX_TURNS = 8
    const val DEFAULT_RECENT_TURNS = 2

    fun prepare(history: List<LlmMessage>): List<LlmMessage> {
        return LlmMessageSanitizer.sanitize(compact(history))
    }

    fun commit(
        prior: List<LlmMessage>,
        userText: String,
        loopMessages: List<LlmMessage>,
        speak: String,
    ): List<LlmMessage> {
        val turn = extractTurn(loopMessages, userText, speak)
        return prepare(prior + turn)
    }

    fun compact(
        messages: List<LlmMessage>,
        maxTurns: Int = DEFAULT_MAX_TURNS,
        recentTurns: Int = DEFAULT_RECENT_TURNS,
    ): List<LlmMessage> {
        val system = messages.filter { it.role == "system" }
        val turns = groupTurns(messages)
        if (turns.isEmpty()) return system
        val overflow = (turns.size - maxTurns).coerceAtLeast(0)
        val dropped = turns.take(overflow)
        val kept = turns.drop(overflow)
        val pruned = pruneOlder(kept, recentTurns.coerceAtLeast(1))
        val summary = if (dropped.isEmpty()) emptyList() else {
            listOf(LlmMessage("user", summarize(dropped.flatMap { it.flatten() })))
        }
        return system + summary + pruned.flatMap { it.flatten() }
    }

    fun summarize(dropped: List<LlmMessage>): String {
        val goal = dropped.firstOrNull { it.role == "user" }?.content.orEmpty()
            .removePrefix("【压缩摘要】").take(40)
        val facts = dropped.filter { it.role == "tool" }
            .mapNotNull { factFromTool(it.content) }
            .distinct()
            .take(6)
        val replies = dropped.filter { it.role == "assistant" && it.toolCalls.isEmpty() }
            .map { it.content.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
        val pending = dropped.filter { it.role == "tool" && it.content.contains("need_confirm") }
            .mapNotNull { ToolSpeak.field(it.content, "message").takeIf { text -> text.isNotBlank() } }
            .filter { !ToolSpeak.isDeveloper(it) }
            .distinct()
            .take(3)
        return buildString {
            append("【压缩摘要】目标：").append(goal.ifBlank { "未记录" })
            append("。已确认事实：").append(if (facts.isEmpty()) "无" else facts.joinToString("；"))
            if (replies.isNotEmpty()) {
                append("。近期回复：").append(replies.joinToString("；") { it.take(40) })
            }
            append("。待办：").append(if (pending.isEmpty()) "无" else pending.joinToString("；"))
        }
    }

    internal fun extractTurn(loopMessages: List<LlmMessage>, userText: String, speak: String): List<LlmMessage> {
        val idx = loopMessages.indexOfLast { it.role == "user" && it.content == userText }
        val rest = if (idx >= 0) loopMessages.drop(idx) else listOf(LlmMessage("user", userText))
        val turn = rest.filter { it.role != "system" }
        val hasSpeak = speak.isNotBlank() && turn.any { msg ->
            msg.role == "assistant" && msg.toolCalls.isEmpty() && msg.content == speak
        }
        return if (speak.isNotBlank() && !hasSpeak) turn + LlmMessage("assistant", speak) else turn
    }

    internal fun groupTurns(messages: List<LlmMessage>): List<ConversationTurn> {
        val turns = ArrayList<ConversationTurn>()
        var user: LlmMessage? = null
        var follow = ArrayList<LlmMessage>()
        fun flush() {
            val current = user ?: return
            turns += ConversationTurn(current, follow.toList())
            user = null
            follow = ArrayList()
        }
        for (msg in messages) {
            if (msg.role == "system") continue
            if (msg.role == "user") {
                flush()
                user = msg
            } else if (user != null) {
                follow += msg
            }
        }
        flush()
        return turns
    }

    private fun pruneOlder(turns: List<ConversationTurn>, recentTurns: Int): List<ConversationTurn> {
        if (turns.size <= recentTurns) return turns
        val split = turns.size - recentTurns
        return turns.mapIndexed { index, turn ->
            if (index < split) turn.asSemanticPair() else turn
        }
    }

    private fun ConversationTurn.asSemanticPair(): ConversationTurn {
        val finalReply = followUp.lastOrNull { it.role == "assistant" && it.toolCalls.isEmpty() }
            ?: followUp.lastOrNull { it.role == "assistant" }?.let { msg ->
                LlmMessage("assistant", msg.content.ifBlank { "已处理。" })
            }
        return ConversationTurn(user, listOfNotNull(finalReply))
    }

    private fun factFromTool(content: String): String? {
        if (!content.contains("\"ok\":true")) return null
        if (content.contains("need_confirm") || content.contains("need_disambiguation")) return null
        if (content.contains("\"observation\":true") && !content.contains("\"matched\":true")) return null
        for (key in LABEL_KEYS) {
            val value = ToolSpeak.field(content, key)
            if (value.isNotBlank() && !looksLikeId(value)) return value
        }
        return null
    }

    private fun looksLikeId(value: String): Boolean {
        return value.matches(Regex("[A-Za-z0-9_-]{8,}")) && value.any { it.isDigit() } && !value.any { it in '\u4e00'..'\u9fff' }
    }

    private val LABEL_KEYS = listOf("summary", "label", "title", "name", "shortName")
}

object DegradedPlanner {
    fun turn(messages: List<LlmMessage>, world: WorldContext): LlmTurn {
        val user = lastUser(messages)
        val lastTool = messages.lastOrNull { it.role == "tool" }
        if (lastTool != null) {
            return afterTool(user, lastTool, world)
        }
        navQuery(user)?.let { name ->
            return tool("resolve_destination", """{"name":${ToolResult.jsonString(name)}}""")
        }
        nearbyQuery(user)?.let { keyword ->
            return tool("search_nearby_places", """{"keyword":${ToolResult.jsonString(keyword)}}""")
        }
        if (isLook(user)) {
            return tool("look_at_scene", """{"reason":"user_asked"}""")
        }
        if (world.pendingPay.isNotBlank() && isPayConfirm(user)) {
            return tool("checkout", """{"confirm":true}""")
        }
        if (world.pendingCoupon.isNotBlank() && isRedeemConfirm(user)) {
            return tool("redeem_coupon", """{"confirm":true}""")
        }
        val speak = if (world.modelReady) {
            "我这次没想好，请再说一次。"
        } else {
            "语言模型还没配好。我仍能看眼前、搜附近地点、带路。配好密钥后可以开放问答。"
        }
        return LlmTurn(content = speak)
    }

    private fun afterTool(user: String, tool: LlmMessage, world: WorldContext): LlmTurn {
        val content = tool.content
        if (content.contains("need_search") || content.contains("\"error\":\"need_search\"")) {
            val name = navQuery(user) ?: nearbyQuery(user) ?: user.take(12)
            return tool("search_nearby_places", """{"keyword":${ToolResult.jsonString(name)}}""")
        }
        if (content.contains("\"unique\":true") || content.contains("\"place_id\"")) {
            if (wantsNav(user)) {
                val id = ToolSpeak.field(content, "place_id").ifBlank { ToolSpeak.field(content, "id") }
                if (id.isNotBlank()) {
                    return tool("start_navigation", """{"place_id":${ToolResult.jsonString(id)}}""")
                }
            }
        }
        return LlmTurn(content = ToolSpeak.forUser(content, world.modelReady))
    }

    private fun tool(name: String, args: String): LlmTurn {
        val call = LlmToolCall("call_${name}_${args.hashCode() and 0x7fffffff}", name, args)
        return LlmTurn(toolCalls = listOf(call), assistant = LlmMessage("assistant", toolCalls = listOf(call)))
    }

    private fun lastUser(messages: List<LlmMessage>): String {
        return messages.lastOrNull { it.role == "user" }?.content.orEmpty()
            .substringAfter("用户：").substringAfter("用户:").trim()
    }

    private fun navQuery(text: String): String? {
        if (!wantsNav(text)) return null
        val cleaned = text.replace(Regex("导航|带我去|走去|现在去|出发去|去一下|去"), " ")
            .replace(Regex("[，。！？]"), " ")
            .trim()
        return cleaned.takeIf { it.length >= 2 }
    }

    private fun nearbyQuery(text: String): String? {
        if (!text.contains("附近") && !text.contains("有没有") && !text.contains("哪里有")) return null
        val cleaned = text.replace(Regex("附近|有没有|哪里有|吗|呢|啊"), " ").trim()
        return cleaned.takeIf { it.length >= 2 } ?: "地点"
    }

    private fun wantsNav(text: String): Boolean {
        return listOf("导航", "带我去", "走去", "现在去", "出发", "带路").any { text.contains(it) }
    }

    private fun isLook(text: String): Boolean {
        return listOf("这是什么", "前面是什么", "那是什么", "看一下", "眼前").any { text.contains(it) }
    }

    private fun isPayConfirm(text: String): Boolean {
        return listOf("确认付款", "确认支付", "付钱吧", "付款吧").any { text.contains(it) }
    }

    private fun isRedeemConfirm(text: String): Boolean {
        return listOf("确认核销", "核销吧").any { text.contains(it) }
    }
}
