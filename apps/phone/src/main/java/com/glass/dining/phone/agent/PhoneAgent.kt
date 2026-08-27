package com.glass.dining.phone.agent

import android.util.Log
import com.glass.dining.phone.AiReply
import com.glass.dining.phone.PhoneAi
import com.glass.dining.shared.agent.ActionPolicy
import com.glass.dining.shared.agent.AgentContextAssembler
import com.glass.dining.shared.agent.AgentLoop
import com.glass.dining.shared.agent.AgentMemory
import com.glass.dining.shared.agent.AgentPrompts
import com.glass.dining.shared.agent.DegradedPlanner
import com.glass.dining.shared.agent.LlmMessage
import com.glass.dining.shared.agent.LlmToolCall
import com.glass.dining.shared.agent.LlmTurn
import com.glass.dining.shared.agent.WorldContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

object PhoneAgent {
    private const val TAG = "GlassDiningPhone"
    private val history = CopyOnWriteArrayList<LlmMessage>()
    private val loop = AgentLoop(maxSteps = 8)
    private val policy = ActionPolicy()

    fun ask(
        question: String,
        world: WorldContext,
        registry: ToolRegistry,
        onDelta: ((String) -> Unit)? = null,
        cancel: () -> Boolean = { false },
    ): AiReply {
        val text = question.trim()
        if (text.isBlank()) {
            return AiReply("我没听清，再说一次。", "再说一次", false)
        }
        val messages = buildMessages(text, world)
        val tools = registry.toJsonArray()
        val outcome = loop.run(
            messages = messages,
            userText = text,
            requestModel = { current ->
                if (PhoneAi.ready) {
                    requestModel(current, tools)
                } else {
                    DegradedPlanner.turn(current, world.copy(modelReady = false))
                }
            },
            execute = { call -> registry.execute(call.name, parseArgs(call.argumentsJson)) },
            specOf = { registry.specOf(it) },
            policy = policy,
            cancel = cancel,
            onEvent = { event ->
                Log.i(TAG, "agent ${event.kind} step=${event.step} name=${event.name} ${event.latencyMs}ms ${event.detail}")
            },
        )
        var speak = outcome.speak
        if (speak.isBlank() && outcome.reachedLimit && PhoneAi.ready) {
            speak = PhoneAi.stream(toJson(messages), onDelta)?.speak.orEmpty()
        }
        if (speak.isBlank() && outcome.failed) {
            speak = if (PhoneAi.ready) "我这次没回上，再说一次。" else "语言模型还没配好。我仍能看眼前、搜附近、带路。"
        }
        if (speak.isNotBlank()) {
            onDelta?.invoke(speak)
        }
        persist(messages, text, speak)
        return AiReply(speak.ifBlank { "我没听清，再说一次。" }, speak.take(16), PhoneAi.ready && speak.isNotBlank())
    }

    private fun buildMessages(question: String, world: WorldContext): MutableList<LlmMessage> {
        val system = LlmMessage("system", AgentPrompts.BASE + "\n" + AgentContextAssembler.format(world))
        val compact = AgentMemory.compact(history.toList())
        return (listOf(system) + compact + LlmMessage("user", question)).toMutableList()
    }

    private fun persist(messages: List<LlmMessage>, userText: String, speak: String) {
        val turn = messages.filter { it.role != "system" }
        history.add(LlmMessage("user", userText))
        turn.filter { it.role == "assistant" || it.role == "tool" }.forEach { history.add(it) }
        if (speak.isNotBlank() && history.none { it.role == "assistant" && it.content == speak }) {
            history.add(LlmMessage("assistant", speak))
        }
        val compacted = AgentMemory.compact(history.toList())
        history.clear()
        history.addAll(compacted)
    }

    private fun requestModel(current: List<LlmMessage>, tools: JSONArray): LlmTurn? {
        val message = PhoneAi.complete(toJson(current), tools) ?: return null
        return parseTurn(message)
    }

    private fun parseTurn(message: JSONObject): LlmTurn {
        val callsJson = message.optJSONArray("tool_calls")
        val calls = ArrayList<LlmToolCall>()
        if (callsJson != null) {
            for (i in 0 until callsJson.length()) {
                val call = callsJson.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function")
                calls.add(
                    LlmToolCall(
                        id = call.optString("id"),
                        name = fn?.optString("name").orEmpty(),
                        argumentsJson = fn?.optString("arguments").orEmpty(),
                    ),
                )
            }
        }
        val content = message.optString("content").ifBlank { message.optString("reasoning_content") }
        val extra = HashMap<String, String>()
        val reasoning = message.optString("reasoning_content")
        if (reasoning.isNotBlank()) extra["reasoning_content"] = reasoning
        return LlmTurn(
            content = content,
            toolCalls = calls,
            assistant = LlmMessage(
                role = "assistant",
                content = content,
                toolCalls = calls,
                extra = extra,
                rawJson = message.toString(),
            ),
        )
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
