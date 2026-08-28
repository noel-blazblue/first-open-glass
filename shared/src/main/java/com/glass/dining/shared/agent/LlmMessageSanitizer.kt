package com.glass.dining.shared.agent

/**
 * 发给 LLM 前的协议防线：tool 必须紧跟带 tool_calls 的 assistant，且每个 call id 都有结果。
 */
object LlmMessageSanitizer {
    fun sanitize(messages: List<LlmMessage>): List<LlmMessage> {
        val out = ArrayList<LlmMessage>(messages.size)
        var pending = emptyList<LlmToolCall>()
        fun closePending() {
            if (pending.isEmpty()) return
            pending.forEach { call ->
                out += LlmMessage(
                    role = "tool",
                    content = """{"ok":false,"error":"missing_result"}""",
                    toolCallId = call.id,
                )
            }
            pending = emptyList()
        }
        for (msg in messages) {
            when (msg.role) {
                "tool" -> {
                    val matched = matchPending(pending, msg.toolCallId)
                    if (matched == null) continue
                    val id = msg.toolCallId.ifBlank { matched.id }
                    out += if (id == msg.toolCallId) msg else msg.copy(toolCallId = id)
                    pending = pending.filter { it.id != matched.id }
                }
                "assistant" -> {
                    closePending()
                    out += msg
                    pending = msg.toolCalls.filter { it.id.isNotBlank() }
                }
                else -> {
                    closePending()
                    out += msg
                }
            }
        }
        closePending()
        return out
    }

    private fun matchPending(pending: List<LlmToolCall>, toolCallId: String): LlmToolCall? {
        if (pending.isEmpty()) return null
        if (toolCallId.isNotBlank()) return pending.firstOrNull { it.id == toolCallId }
        return pending.singleOrNull()
    }
}
