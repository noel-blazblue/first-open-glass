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
        val facts = dropped.filter { it.role == "tool" && it.content.contains("\"ok\":true") }
            .mapNotNull { factFromTool(it.content) }
            .distinct()
            .take(6)
        val pending = dropped.filter { it.role == "tool" && it.content.contains("need_confirm") }
            .mapNotNull { field(it.content, "message") }
            .distinct()
            .take(3)
        val ids = dropped.mapNotNull { ActionPolicy.requestIdOf(it.content) }
            .filter { it.isNotBlank() }
            .distinct()
        return buildString {
            append("【压缩摘要】目标：").append(goal.ifBlank { "未记录" })
            append("。已确认事实：").append(if (facts.isEmpty()) "无" else facts.joinToString("；"))
            append("。待办：").append(if (pending.isEmpty()) "无" else pending.joinToString("；"))
            append("。副作用ID：").append(if (ids.isEmpty()) "无" else ids.joinToString(","))
        }
    }

    private fun factFromTool(content: String): String? {
        val store = field(content, "shortName").ifBlank { field(content, "store") }
        val place = field(content, "name")
        return store.ifBlank { place }.ifBlank { null }
    }

    private fun field(json: String, key: String): String {
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

object AgentContextAssembler {
    fun format(world: WorldContext): String {
        val place = world.currentPlace
        val obs = world.observation
        val gps = if (world.hasGps) {
            "有定位"
        } else {
            "无定位"
        }
        val obsLine = if (obs == null || obs.scene == SceneObservation.SCENE_UNKNOWN) {
            "无稳定观察（观察不是事实）"
        } else {
            "场景=${obs.scene} 文本=${obs.visibleText.take(24)} 置信=${"%.2f".format(obs.confidence)} " +
                "稳定=${obs.stability} 候选=${obs.storeCandidate.ifBlank { "无" }} 已晋级事实=${obs.isStoreFact}"
        }
        return buildString {
            append("【世界】技能=").append(world.skill)
            append(" 导航中=").append(world.navActive)
            append(" 定位=").append(gps)
            append(" 本地餐饮目录=").append(world.catalogCount).append("家（增强数据，不是世界全集）")
            append(" 当前地点=")
            if (place == null) {
                append("无")
            } else {
                append(place.name).append("(").append(place.source).append(")")
                if (place.floor.isNotBlank()) append(" 楼层=").append(place.floor)
            }
            append(" 候选=").append(world.candidates.joinToString("、") { it.name }.ifBlank { "无" })
            append(" 楼层=").append(
                world.environment?.usableFloor?.ifBlank { null }
                    ?: world.currentFloor.ifBlank { world.spokenFloor }.ifBlank { "未知" },
            )
            append(" 待支付=").append(world.pendingPay.ifBlank { "无" })
            append(" 待核销=").append(world.pendingCoupon.ifBlank { "无" })
            append("\n【观察】").append(obsLine)
            append("\n").append(world.environment?.promptBlock() ?: "【当前环境】无稳定环境记录")
            append("\n【能力】").append(world.capabilities.joinToString("、").ifBlank { "问答、看环境、搜附近、导航" })
        }
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
                val id = AgentMemory.summarize(listOf(tool)).let { field(content, "place_id").ifBlank { field(content, "id") } }
                if (id.isNotBlank()) {
                    return tool("start_navigation", """{"place_id":${ToolResult.jsonString(id)}}""")
                }
            }
        }
        val message = field(content, "message").ifBlank { field(content, "error") }
        val speak = message.ifBlank {
            if (world.modelReady) "我看过工具结果了。" else "已处理。配好语言模型后能讲得更清楚。"
        }
        return LlmTurn(content = speak)
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

    private fun field(json: String, key: String): String {
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
