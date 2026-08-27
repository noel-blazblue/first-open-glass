package com.glass.dining.phone

import android.content.Context
import android.util.Base64
import android.util.Log
import com.glass.dining.shared.agent.AgentPrompts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AiReply(
    val speak: String,
    val hud: String,
    val fromLlm: Boolean,
    val store: String = "",
    val isStore: Boolean = true,
    val confidence: Float = 0f,
)

/** 模型 Provider：鉴权、补全、视觉。Agent 循环在 PhoneAgent。 */
object PhoneAi {
    private const val TAG = "GlassDiningPhone"
    private const val DEFAULT_BASE = "https://api.deepseek.com"
    private const val DEFAULT_CHAT = "deepseek-v4-flash"
    private const val DEFAULT_VISION = "deepseek-v4-flash-vision-exp"

    @Volatile private var token: String = ""
    @Volatile private var baseUrl: String = DEFAULT_BASE
    @Volatile private var chatModel: String = DEFAULT_CHAT
    @Volatile private var visionModel: String = DEFAULT_VISION
    @Volatile var amapKey: String = ""
        private set

    val ready: Boolean
        get() = token.isNotBlank() && baseUrl.isNotBlank()

    val statusLine: String
        get() = if (ready) "DeepSeek $chatModel" else "未配置 DEEPSEEK_API_KEY"

    fun load(context: Context) {
        val dirs = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
        val files = dirs.flatMap { dir -> listOf(File(dir, "ai.env"), File(dir, ".env")) }
        for (file in files) {
            if (!file.exists()) continue
            val raw = file.readText()
            applyEnv(raw)
            NlsClient.applyEnv(raw)
            Log.i(
                TAG,
                "loaded ${file.name} keys token=${token.isNotBlank()} amap=${amapKey.isNotBlank()} nls=${NlsClient.ready} base=$baseUrl model=$chatModel",
            )
        }
        if (!ready) {
            Log.w(TAG, "workspace .env 还没推到手机，或缺少 DEEPSEEK_API_KEY")
        }
        if (!NlsClient.ready) {
            Log.w(TAG, "未配置阿里云语音，识别将回退 Vosk，播报将回退系统 TTS")
        }
    }

    fun look(jpeg: ByteArray, task: String = "look_store", ocrText: String = ""): AiReply {
        if (!ready) {
            return AiReply("还没配 DeepSeek 密钥，先看手机提示。", "未配置密钥", false)
        }
        if (jpeg.isEmpty()) {
            return AiReply("没拿到眼镜画面。", "没画面", false)
        }
        return vision(jpeg, task, ocrText) ?: AiReply("这张画面我这次没认出来。", "未认出", false)
    }

    fun envLook(
        jpeg: ByteArray,
        ocrText: String = "",
        previous: String = "",
        eventThread: String = "",
    ): String? {
        if (!ready || jpeg.isEmpty()) return null
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val prompt = buildString {
            append("根据画面输出 JSON，必须包含 observation、navigation、eventProposal 三个子对象。")
            append("observation.sceneBrief 只写现在看着什么，不要把楼层写进场景正文。")
            append("只有看见楼层/电梯/导览标识时才填 navigation.floorCandidate。")
            append("navigation 只写画面里能看见的空间和导视，没有就留空，不要编路线。")
            append("看见可通行的门、出口、通道就填 spaceType=entrance 和 exits.dir，没看见不要编。")
            append("eventProposal 只描述活动生命周期，不要发明输入里没有的事件 id。")
            if (previous.isNotBlank()) {
                append("\n上一份环境：").append(previous.take(300))
            }
            if (eventThread.isNotBlank()) {
                append("\n上一活动线程：").append(eventThread.take(360))
            }
            if (ocrText.isNotBlank()) {
                append("\n端侧OCR参考：").append(ocrText.take(240))
            }
        }
        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put("url", "data:image/jpeg;base64,$b64")
                            .put("detail", "high"),
                    ),
            )
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", AgentPrompts.ENVIRONMENT))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val raw = completeMessage(visionModel, messages, 45_000, jsonMode = true, tools = null)
            ?.optString("content")
            .orEmpty()
            .trim()
        return raw.ifBlank { null }
    }

    fun complete(messages: JSONArray, tools: JSONArray?, timeoutMs: Int = 25_000): JSONObject? {
        return completeMessage(chatModel, messages, timeoutMs, jsonMode = false, tools = tools)
    }

    fun stream(messages: JSONArray, onDelta: ((String) -> Unit)?): AiReply? {
        return streamChat(messages, onDelta)
    }

    private fun vision(jpeg: ByteArray, task: String, ocrText: String): AiReply? {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val prompt = buildString {
            append("任务：$task。")
            when (task) {
                "read_menu" -> append("这是菜单，抽出菜名和价格，不要编造没有的菜。")
                "read_sign" -> append("这是路牌或入口，只读看到的字，不要判断能不能过马路。")
                "scan_coupon" -> append("这是团购券或券码，只读标题和是否像可用券，不要输出完整券码，不要说已经核销。")
                else -> append("读出眼前看到的标识。不确定就描述，不要猜品牌。黄色横幅和广告不是店。")
            }
            if (ocrText.isNotBlank()) {
                append(" 端侧OCR：").append(ocrText.take(400))
            }
        }
        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put("url", "data:image/jpeg;base64,$b64")
                            .put("detail", if (task == "look_store") "high" else "low"),
                    ),
            )
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", AgentPrompts.VISION))
            .put(JSONObject().put("role", "user").put("content", userContent))
        return parseReply(completeMessage(visionModel, messages, 45_000, jsonMode = true, tools = null)?.optString("content").orEmpty())
    }

    private fun completeMessage(
        model: String,
        messages: JSONArray,
        timeoutMs: Int,
        jsonMode: Boolean,
        tools: JSONArray?,
    ): JSONObject? {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", 400)
            .put("stream", false)
            .put("thinking", JSONObject().put("type", "disabled"))
        if (jsonMode) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        if (tools != null) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }
        val raw = postJson(chatUrl(), body.toString(), timeoutMs) ?: return null
        return JSONObject(raw)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
    }

    private fun streamChat(messages: JSONArray, onDelta: ((String) -> Unit)?): AiReply? {
        val body = JSONObject()
            .put("model", chatModel)
            .put("messages", messages)
            .put("max_tokens", 280)
            .put("stream", true)
            .put("thinking", JSONObject().put("type", "disabled"))
        val connection = (URL(chatUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "text/event-stream")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
                Log.w(TAG, "LLM stream http=$code body=${err.take(180)}")
                return null
            }
            val acc = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val payload = when {
                    line.startsWith("data:") -> line.removePrefix("data:").trim()
                    else -> return@forEachLine
                }
                if (payload.isEmpty() || payload == "[DONE]") return@forEachLine
                val delta = JSONObject(payload)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?: return@forEachLine
                val piece = delta.optString("content")
                if (piece.isBlank()) return@forEachLine
                acc.append(piece)
                onDelta?.invoke(acc.toString())
            }
            plainReply(acc.toString())
        } catch (error: Exception) {
            Log.w(TAG, "LLM stream ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun plainReply(raw: String): AiReply? {
        val speak = raw.trim().replace(Regex("[\\r\\n]+"), "")
        if (speak.isBlank()) return null
        return AiReply(speak, speak, true)
    }

    private fun chatUrl(): String = "${baseUrl.trimEnd('/')}/chat/completions"

    private fun parseReply(raw: String): AiReply? {
        val match = Regex("""\{[\s\S]*\}""").find(raw) ?: run {
            val speak = raw.trim().take(80)
            if (speak.isBlank()) return null
            return AiReply(speak, speak.take(16), true)
        }
        return try {
            val json = JSONObject(match.value)
            val speak = json.optString("speak").ifBlank { json.optString("answer") }.trim()
            if (speak.isBlank()) return null
            val hud = json.optString("hud").ifBlank { speak }.replace("\\s".toRegex(), "").take(16)
            val store = json.optString("store").trim()
            val confidence = json.optDouble("confidence", 0.0).toFloat()
            AiReply(speak.take(80), hud, true, store, store.isNotBlank(), confidence)
        } catch (_: Exception) {
            null
        }
    }

    private fun postJson(url: String, body: String, timeoutMs: Int): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "LLM http=$code model-call body=${text.take(180)}")
                return null
            }
            text
        } catch (error: Exception) {
            Log.w(TAG, "LLM ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun applyEnv(raw: String) {
        for (line in raw.lineSequence()) {
            val stripped = line.trim()
            if (stripped.isEmpty() || stripped.startsWith("#") || "=" !in stripped) continue
            val key = stripped.substringBefore("=").trim()
            val value = stripped.substringAfter("=").trim().trim('"').trim('\'')
            if (value.isBlank()) continue
            when (key) {
                "DEEPSEEK_API_KEY" -> token = value
                "DEEPSEEK_BASE_URL" -> baseUrl = value.trimEnd('/')
                "DEEPSEEK_MODEL", "DEEPSEEK_CHAT_MODEL" -> chatModel = value
                "DEEPSEEK_VISION_MODEL" -> visionModel = value
                "AMAP_WEB_KEY", "AMAP_KEY" -> amapKey = value
                "OPENAI_API_KEY" -> if (token.isBlank()) token = value
                "OPENAI_BASE_URL" -> if (baseUrl == DEFAULT_BASE) baseUrl = value.trimEnd('/')
                "OPENAI_MODEL" -> if (chatModel == DEFAULT_CHAT) chatModel = value
            }
        }
    }
}
