package com.glass.dining.shared.agent

object AgentMemory {
    fun compact(messages: List<LlmMessage>, maxMessages: Int = 20): List<LlmMessage> {
        val system = messages.filter { it.role == "system" }
        val rest = messages.filter { it.role != "system" }
        if (rest.size <= maxMessages) return messages
        val kept = rest.takeLast(maxMessages)
        val dropped = rest.dropLast(kept.size)
        val summary = summarize(dropped)
        return system + LlmMessage("user", summary) + kept
    }

    fun summarize(dropped: List<LlmMessage>): String {
        val goal = dropped.firstOrNull { it.role == "user" }?.content.orEmpty().take(40)
        val facts = dropped.filter { it.role == "tool" }
            .mapNotNull { factFromTool(it.content) }
            .distinct()
            .take(6)
        val pending = dropped.filter { it.role == "tool" && it.content.contains("need_confirm") }
            .mapNotNull { ToolSpeak.field(it.content, "message") }
            .filter { !ToolSpeak.isDeveloper(it) }
            .distinct()
            .take(3)
        return buildString {
            append("【压缩摘要】目标：").append(goal.ifBlank { "未记录" })
            append("。已确认事实：").append(if (facts.isEmpty()) "无" else facts.joinToString("；"))
            append("。待办：").append(if (pending.isEmpty()) "无" else pending.joinToString("；"))
        }
    }

    private fun factFromTool(content: String): String? {
        if (!content.contains("\"ok\":true")) return null
        if (content.contains("need_confirm") || content.contains("need_disambiguation")) return null
        if (content.contains("\"observation\":true") && !content.contains("\"matched\":true")) return null
        val store = ToolSpeak.field(content, "shortName").ifBlank { ToolSpeak.field(content, "store") }
        val place = ToolSpeak.field(content, "name")
        return store.ifBlank { place }.ifBlank { null }
    }
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
        return listOf("这是什么", "前面是什么", "那是什么", "看一下", "眼前", "几楼", "哪一层", "楼层").any { text.contains(it) }
    }

    private fun isPayConfirm(text: String): Boolean {
        return listOf("确认付款", "确认支付", "付钱吧", "付款吧").any { text.contains(it) }
    }

    private fun isRedeemConfirm(text: String): Boolean {
        return listOf("确认核销", "核销吧").any { text.contains(it) }
    }
}
