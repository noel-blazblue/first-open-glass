package com.glass.dining.phone.agent

import android.util.Log
import com.glass.dining.phone.AiReply
import com.glass.dining.phone.PhoneAi
import com.glass.dining.phone.tools.ToolRegistry
import com.glass.dining.shared.agent.ActionPolicy
import com.glass.dining.shared.agent.AgentContextAssembler
import com.glass.dining.shared.agent.AgentLoop
import com.glass.dining.shared.agent.AgentMemory
import com.glass.dining.shared.agent.AgentPrompts
import com.glass.dining.shared.agent.DegradedPlanner
import com.glass.dining.shared.agent.LlmMessage
import com.glass.dining.shared.agent.LlmMessageSanitizer
import com.glass.dining.shared.agent.WorldContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

object PhoneAgent {
    private const val TAG = "GlassDiningPhone"
    private val history = CopyOnWriteArrayList<LlmMessage>()
    private val loop = AgentLoop(maxSteps = 8)

    fun ask(
        question: String,
        world: WorldContext,
        registry: ToolRegistry,
        onDelta: ((String) -> Unit)? = null,
        onContinueSpeak: (() -> Unit)? = null,
        cancel: () -> Boolean = { false },
    ): AiReply {
        val text = question.trim()
        if (text.isBlank()) {
            return AiReply("我没听清，再说一次。", "再说一次", false)
        }
        val prior = history.toList()
        val messages = buildMessages(text, world, prior)
        val tools = registry.toJsonArray()
        val outcome = loop.run(
            messages = messages,
            userText = text,
            requestModel = { current, onStream ->
                if (PhoneAi.ready) {
                    PhoneAi.streamTurn(toJson(LlmMessageSanitizer.sanitize(current)), tools, onStream, cancel)
                } else {
                    DegradedPlanner.turn(current, world.copy(modelReady = false))
                }
            },
            execute = { call -> registry.execute(call.name, parseArgs(call.argumentsJson)) },
            specOf = { registry.specOf(it) },
            policy = ActionPolicy(world.runtimeCapabilities),
            cancel = cancel,
            onEvent = { event ->
                Log.i(TAG, "agent ${event.kind} step=${event.step} name=${event.name} ${event.latencyMs}ms ${event.detail}")
            },
            onSpeak = { partial -> onDelta?.invoke(partial) },
            onContinueSpeak = { onContinueSpeak?.invoke() },
        )
        if (outcome.cancelled) {
            return AiReply("", "", PhoneAi.ready)
        }
        var speak = outcome.speak
        if (speak.isBlank() && (outcome.failed || outcome.reachedLimit)) {
            speak = if (PhoneAi.ready) "我这次没回上，再说一次。" else "语言模型还没配好。我仍能看眼前、搜附近、带路。"
        }
        if (!outcome.failed && !outcome.cancelled) {
            persist(prior, text, messages, speak)
        }
        return AiReply(speak.ifBlank { "我没听清，再说一次。" }, speak.take(16), PhoneAi.ready && speak.isNotBlank())
    }

    private fun buildMessages(question: String, world: WorldContext, prior: List<LlmMessage>): MutableList<LlmMessage> {
        val system = LlmMessage("system", AgentPrompts.BASE + "\n" + AgentContextAssembler.format(world))
        return (listOf(system) + AgentMemory.prepare(prior) + LlmMessage("user", question)).toMutableList()
    }

    private fun persist(prior: List<LlmMessage>, userText: String, loopMessages: List<LlmMessage>, speak: String) {
        val next = AgentMemory.commit(prior, userText, loopMessages, speak)
        history.clear()
        history.addAll(next)
    }

    private fun toJson(messages: List<LlmMessage>): JSONArray {
        val arr = JSONArray()
        messages.forEach { msg ->
            if (msg.rawJson.isNotBlank()) {
                arr.put(JSONObject(msg.rawJson))
                return@forEach
            }
            val obj = JSONObject().put("role", msg.role)
            if (msg.role == "tool") {
                obj.put("tool_call_id", msg.toolCallId).put("content", msg.content)
            } else {
                obj.put("content", msg.content)
            }
            if (msg.toolCalls.isNotEmpty()) {
                val calls = JSONArray()
                msg.toolCalls.forEach { call ->
                    calls.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", call.name).put("arguments", call.argumentsJson),
                            ),
                    )
                }
                obj.put("tool_calls", calls)
            }
            msg.extra["reasoning_content"]?.let { obj.put("reasoning_content", it) }
            arr.put(obj)
        }
        return arr
    }

    private fun parseArgs(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
