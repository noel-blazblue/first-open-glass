package com.glass.dining.phone

import android.util.Log
import com.glass.dining.shared.agent.LlmSseParser
import com.glass.dining.shared.agent.LlmStreamDelta
import com.glass.dining.shared.agent.LlmTurn
import com.glass.dining.shared.agent.StreamingTurnAssembler
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** 可取消的 DeepSeek/OpenAI 兼容 SSE。工具轮和最终回答都走这一路。 */
object PhoneAiStream {
    private const val TAG = "GlassDiningPhone"
    private val generation = AtomicInteger(0)
    @Volatile private var live: HttpURLConnection? = null

    fun abort() {
        generation.incrementAndGet()
        val conn = live
        live = null
        try {
            conn?.disconnect()
        } catch (_: Exception) {
        }
    }

    fun streamTurn(
        model: String,
        url: String,
        token: String,
        messages: JSONArray,
        tools: JSONArray?,
        onDelta: (LlmStreamDelta) -> Unit,
        cancel: () -> Boolean,
    ): LlmTurn? {
        val gen = generation.incrementAndGet()
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", 400)
            .put("stream", true)
            .put("thinking", JSONObject().put("type", "disabled"))
        if (tools != null) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "text/event-stream")
        }
        live = connection
        val assembler = StreamingTurnAssembler()
        val startedAt = System.currentTimeMillis()
        var firstEvent = false
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (stale(gen, cancel)) return null
            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
                Log.w(TAG, "LLM stream http=$code body=${err.take(180)}")
                return null
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                if (stale(gen, cancel)) {
                    connection.disconnect()
                    return@forEachLine
                }
                val delta = LlmSseParser.parseLine(line) ?: return@forEachLine
                if (!firstEvent && (delta.content.isNotEmpty() || delta.toolCalls.isNotEmpty() || delta.done)) {
                    firstEvent = true
                    Log.i(TAG, "voice phase=llm-ttft ${System.currentTimeMillis() - startedAt}ms tools=${delta.toolCalls.isNotEmpty()} chars=${delta.content.length}")
                }
                assembler.accept(delta)
                onDelta(delta)
            }
            if (stale(gen, cancel)) return null
            if (!assembler.hasData) return null
            assembler.finish()
        } catch (error: Exception) {
            if (stale(gen, cancel)) return null
            Log.w(TAG, "LLM stream ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            if (live === connection) live = null
            connection.disconnect()
        }
    }

    private fun stale(gen: Int, cancel: () -> Boolean): Boolean {
        return gen != generation.get() || cancel()
    }
}
