package com.glass.dining.shared.agent

data class LlmToolCallDelta(
    val index: Int,
    val id: String = "",
    val name: String = "",
    val argumentsFragment: String = "",
)

data class LlmStreamDelta(
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<LlmToolCallDelta> = emptyList(),
    val finishReason: String = "",
    val done: Boolean = false,
)

enum class StreamKind { UNKNOWN, CONTENT, TOOLS, MIXED }

data class StreamAccept(
    val kind: StreamKind,
    val content: String,
    val committed: Boolean,
)

/**
 * 按 OpenAI/DeepSeek SSE 碎片组装一轮 assistant。
 * 先看到工具就当工具轮；先看到足够正文才开口。两者都出现视为协议错误。
 */
class StreamingTurnAssembler(
    private val commitMinChars: Int = 8,
) {
    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val tools = sortedMapOf<Int, Slot>()
    private var finishReason: String = ""
    var kind: StreamKind = StreamKind.UNKNOWN
        private set
    val mixed: Boolean get() = kind == StreamKind.MIXED
    val hasData: Boolean get() = content.isNotBlank() || tools.isNotEmpty()

    fun accept(delta: LlmStreamDelta): StreamAccept {
        if (delta.reasoning.isNotBlank()) reasoning.append(delta.reasoning)
        if (delta.content.isNotEmpty()) content.append(delta.content)
        delta.toolCalls.forEach { mergeTool(it) }
        if (delta.finishReason.isNotBlank()) finishReason = delta.finishReason
        decideKind(delta.done)
        return StreamAccept(kind, content.toString(), committed = kind != StreamKind.UNKNOWN)
    }

    fun finish(): LlmTurn {
        val calls = tools.values.map { slot ->
            LlmToolCall(
                id = slot.id.ifBlank { "call-${slot.index}" },
                name = slot.name,
                argumentsJson = slot.args.toString().ifBlank { "{}" },
            )
        }
        val text = content.toString()
        val extra = HashMap<String, String>()
        if (reasoning.isNotBlank()) extra["reasoning_content"] = reasoning.toString()
        return LlmTurn(
            content = text,
            toolCalls = calls,
            assistant = LlmMessage(
                role = "assistant",
                content = text,
                toolCalls = calls,
                extra = extra,
            ),
        )
    }

    private fun mergeTool(delta: LlmToolCallDelta) {
        val slot = tools.getOrPut(delta.index) { Slot(delta.index) }
        if (delta.id.isNotBlank()) slot.id = delta.id
        if (delta.name.isNotBlank()) slot.name = delta.name
        if (delta.argumentsFragment.isNotEmpty()) slot.args.append(delta.argumentsFragment)
    }

    private fun decideKind(done: Boolean) {
        if (kind == StreamKind.MIXED) return
        val hasTools = tools.isNotEmpty()
        val hasSpeak = content.toString().trim().isNotBlank()
        when (kind) {
            StreamKind.UNKNOWN -> {
                if (hasTools && hasSpeak) {
                    kind = StreamKind.MIXED
                } else if (hasTools) {
                    kind = StreamKind.TOOLS
                } else if (shouldCommitContent(done)) {
                    kind = StreamKind.CONTENT
                }
            }
            StreamKind.CONTENT -> if (hasTools) kind = StreamKind.MIXED
            StreamKind.TOOLS -> if (hasSpeak) kind = StreamKind.MIXED
            StreamKind.MIXED -> Unit
        }
    }

    private fun shouldCommitContent(done: Boolean): Boolean {
        val text = content.toString().trim()
        if (text.isEmpty()) return false
        if (done) return true
        if (text.length >= commitMinChars) return true
        return text.any { it in ENDS }
    }

    private class Slot(val index: Int) {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    companion object {
        private val ENDS = setOf('。', '！', '？', '；', '!', '?', '.', '\n')
    }
}

enum class SpeechPriority(val rank: Int) {
    LOW(0),
    AGENT(1),
    NAV(2),
}
