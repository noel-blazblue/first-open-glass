package com.glass.dining.shared.agent

/**
 * 通用推理循环：推理 → 工具 → 观察回灌 → 再推理。
 * 读工具可有限重试；写/确认工具不整轮盲重试。互不依赖的只读调用可并行。
 */
class AgentLoop(
    private val maxSteps: Int = 8,
    private val maxToolCallsPerStep: Int = 6,
) {
    fun run(
        messages: MutableList<LlmMessage>,
        userText: String,
        requestModel: (List<LlmMessage>, (LlmStreamDelta) -> Unit) -> LlmTurn?,
        execute: (LlmToolCall) -> ToolResult,
        specOf: (String) -> ToolSpec?,
        policy: ActionPolicy = ActionPolicy(),
        cancel: () -> Boolean = { false },
        onEvent: (AgentEvent) -> Unit = {},
        onSpeak: (String) -> Unit = {},
        onContinueSpeak: () -> Unit = {},
    ): AgentOutcome {
        val runId = "run-${System.currentTimeMillis()}"
        val events = mutableListOf<AgentEvent>()
        fun emit(step: Int, kind: String, name: String = "", startedAt: Long = System.currentTimeMillis(), detail: String = "") {
            val event = AgentEvent(runId, step, kind, name, System.currentTimeMillis() - startedAt, redact(detail))
            events.add(event)
            onEvent(event)
        }
        for (step in 1..maxSteps) {
            if (cancel()) {
                return AgentOutcome("", step, messages.toList(), cancelled = true, events = events)
            }
            val startedAt = System.currentTimeMillis()
            val assembler = StreamingTurnAssembler()
            var spoken = ""
            val turn = requestModel(messages) { delta ->
                val accepted = assembler.accept(delta)
                if (accepted.kind != StreamKind.UNKNOWN &&
                    accepted.content.trim().isNotBlank() &&
                    accepted.content.length > spoken.length
                ) {
                    spoken = accepted.content
                    onSpeak(spoken)
                }
            }
            if (cancel()) {
                return AgentOutcome("", step, messages.toList(), cancelled = true, events = events)
            }
            if (assembler.mixed) {
                emit(step, "protocol", "mixed", startedAt, "mixed_content_and_tools")
            }
            val resolved = if (assembler.hasData) assembler.finish() else turn
            if (resolved == null) {
                emit(step, "error", "model", startedAt, "model_error")
                return AgentOutcome("", step, messages.toList(), failed = true, events = events)
            }
            if (resolved.toolCalls.isEmpty()) {
                emit(step, "final", startedAt = startedAt, detail = resolved.content.take(80))
                return AgentOutcome(resolved.content.trim(), step, messages.toList(), events = events)
            }
            messages.add(resolved.assistant)
            val ordered = orderCalls(resolved.toolCalls, specOf)
            val taken = ordered.take(maxToolCallsPerStep)
            val overflow = ordered.drop(maxToolCallsPerStep)
            val results = runCalls(taken, userText, execute, specOf, policy, parallel = canParallel(taken, specOf))
            taken.zip(results).forEach { (call, result) ->
                messages.add(
                    LlmMessage(
                        role = "tool",
                        content = result.content,
                        toolCallId = call.id,
                    ),
                )
                val kind = if (result.policyBlocked) "policy" else if (result.ok) "result" else "error"
                emit(step, kind, call.name, startedAt, result.error ?: result.content.take(80))
            }
            overflow.forEach { call ->
                messages.add(
                    LlmMessage(
                        role = "tool",
                        content = ToolResult.fail("单步工具调用过多，已停止执行", code = "too_many_tools").content,
                        toolCallId = call.id,
                    ),
                )
            }
            emit(step, "tools", "batch", startedAt, "count=${taken.size}")
            onContinueSpeak()
        }
        emit(maxSteps, "budget", detail = "maxSteps=$maxSteps")
        return AgentOutcome("", maxSteps, messages.toList(), reachedLimit = true, events = events)
    }

    private fun runCalls(
        calls: List<LlmToolCall>,
        userText: String,
        execute: (LlmToolCall) -> ToolResult,
        specOf: (String) -> ToolSpec?,
        policy: ActionPolicy,
        parallel: Boolean,
    ): List<ToolResult> {
        if (!parallel || calls.size <= 1) {
            return calls.map { runOne(it, userText, execute, specOf, policy) }
        }
        val out = arrayOfNulls<ToolResult>(calls.size)
        val threads = calls.mapIndexed { index, call ->
            Thread {
                out[index] = runOne(call, userText, execute, specOf, policy)
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        return out.map { it ?: ToolResult.fail("工具执行中断") }
    }

    private fun runOne(
        call: LlmToolCall,
        userText: String,
        execute: (LlmToolCall) -> ToolResult,
        specOf: (String) -> ToolSpec?,
        policy: ActionPolicy,
    ): ToolResult {
        if (call.id.isBlank() || call.name.isBlank()) {
            return ToolResult.fail("工具调用格式不完整", code = "bad_call")
        }
        val spec = specOf(call.name)
        val blocked = policy.guard(spec, call.name, call.argumentsJson, userText)
        if (blocked != null) {
            return ToolResult.fail(blocked, policyBlocked = true, code = "policy_blocked")
        }
        var attempt = 0
        var last = executeSafely(call, execute)
        while (policy.canRetry(spec, last, attempt)) {
            attempt += 1
            last = executeSafely(call, execute)
        }
        if (last.ok) {
            policy.markExecuted(spec, call.name, call.argumentsJson, userText)
        }
        return last
    }

    private fun executeSafely(call: LlmToolCall, execute: (LlmToolCall) -> ToolResult): ToolResult {
        return try {
            execute(call)
        } catch (error: Exception) {
            ToolResult.fail(error.message ?: "工具 ${call.name} 执行失败", retryable = true)
        }
    }

    private fun canParallel(calls: List<LlmToolCall>, specOf: (String) -> ToolSpec?): Boolean {
        if (calls.size <= 1) return false
        return calls.all { call ->
            val spec = specOf(call.name)
            spec != null && spec.risk == ToolRisk.READ && spec.parallelSafe
        }
    }

    private fun orderCalls(calls: List<LlmToolCall>, specOf: (String) -> ToolSpec?): List<LlmToolCall> {
        return calls.sortedBy { call ->
            when (specOf(call.name)?.risk) {
                ToolRisk.READ -> 0
                ToolRisk.WRITE -> 1
                ToolRisk.CONFIRM -> 2
                null -> 1
            }
        }
    }

    companion object {
        fun redact(text: String): String {
            return text
                .replace(Regex("(?i)(sk-|Bearer |key=)[A-Za-z0-9._\\-]{8,}"), "$1***")
                .replace(Regex("(?i)\"(token|api_key|authorization)\"\\s*:\\s*\"[^\"]+\""), "\"$1\":\"***\"")
        }
    }
}
