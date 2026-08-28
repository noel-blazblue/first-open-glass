package com.glass.dining.shared.agent

data class LlmToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class LlmMessage(
    val role: String,
    val content: String = "",
    val toolCallId: String = "",
    val toolCalls: List<LlmToolCall> = emptyList(),
    val extra: Map<String, String> = emptyMap(),
    val rawJson: String = "",
)

data class LlmTurn(
    val content: String = "",
    val toolCalls: List<LlmToolCall> = emptyList(),
    val assistant: LlmMessage = LlmMessage("assistant", content, toolCalls = toolCalls),
)

data class ToolResult(
    val ok: Boolean,
    val content: String,
    val error: String? = null,
    val policyBlocked: Boolean = false,
    val retryable: Boolean = false,
) {
    companion object {
        fun json(ok: Boolean, body: String): ToolResult = ToolResult(ok, body)
        fun fail(
            message: String,
            retryable: Boolean = false,
            policyBlocked: Boolean = false,
            code: String = "error",
        ): ToolResult {
            val json = """{"ok":false,"error":"$code","message":${jsonString(message)}}"""
            return ToolResult(false, json, message, policyBlocked, retryable)
        }

        fun jsonString(value: String): String {
            return buildString {
                append('"')
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        else -> append(ch)
                    }
                }
                append('"')
            }
        }
    }
}
