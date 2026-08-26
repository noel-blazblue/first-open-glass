package com.glass.dining.phone

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

data class AiReply(
    val speak: String,
    val hud: String,
    val fromLlm: Boolean,
    val store: String = "",
)

object PhoneAi {
    private const val TAG = "GlassDiningPhone"
    private const val DEFAULT_BASE = "https://api.deepseek.com"
    private const val DEFAULT_CHAT = "deepseek-v4-flash"
    private const val DEFAULT_VISION = "deepseek-v4-flash-vision-exp"
    private const val SYSTEM = """你是乐奇 AI 眼镜上的到餐 Agent。用户只跟你说话，你按意图调用技能。用口语简短回答，一两句即可。
直接输出要说给用户听的中文，不要 JSON，不要 markdown，不要标题。不要教用户说固定口令。

技能规则：
- 用户想吃什么、附近有什么、不要排队：调用 recommend。
- 认眼前这家店、看店招、问「这是哪家」：调用 look_store。已经有当前门店且只是问排队/人均/菜单/包间，直接回答，不要拍照。
- 用户选定一家（第一家、第二家、去某店名）：调用 select_store。若同一句话已经要出发，接着调用 start_nav。
- 有当前门店、且用户要出发（走、出发、现在去、好、导航、带我去等任意去意）：调用 start_nav。用户说了店名（如去海底捞）时，start_nav 必须带上 name，或先 select_store 再 start_nav。不要把上一张店（例如喜茶）当成目的地。没选店就先 recommend 或追问「去哪家」，禁止空目的开导航。刚推荐还没选店时不要 start_nav。
- 只是在问排队/人均/包间，不要 start_nav。
- 取消、到了、换一家：先 stop_nav（如果正在导航），换一家再 recommend。
- 导航中问店，直接答，不要 stop_nav，也不要再 start_nav。
- 闲聊、听不清、无关餐饮，不要乱调工具。
调用工具后，用工具返回的数据做一两句口语；选店后确认店名即可；开始导航说「开始去…」。"""
    private const val VISION_SYSTEM = """你是乐奇 AI 眼镜上的到店餐饮助手。用户拍了眼前画面。
只输出一个 JSON，不要 markdown：
{"speak":"口语一两句，不超过80字","hud":"镜片短句，不超过16字","store":"店名或品牌，看不清就空字符串"}"""

    @Volatile private var token: String = ""
    @Volatile private var baseUrl: String = DEFAULT_BASE
    @Volatile private var chatModel: String = DEFAULT_CHAT
    @Volatile private var visionModel: String = DEFAULT_VISION
    @Volatile var amapKey: String = ""
        private set
    private val history = CopyOnWriteArrayList<Pair<String, String>>()

    val ready: Boolean
        get() = token.isNotBlank() && baseUrl.isNotBlank()

    val statusLine: String
        get() = if (ready) {
            "DeepSeek $chatModel"
        } else {
            "未配置 DEEPSEEK_API_KEY"
        }

    fun load(context: Context) {
        val dirs = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
        val files = dirs.flatMap { dir ->
            listOf(File(dir, "ai.env"), File(dir, ".env"))
        }
        for (file in files) {
            if (!file.exists()) continue
            applyEnv(file.readText())
            Log.i(TAG, "loaded ${file.name} keys token=${token.isNotBlank()} amap=${amapKey.isNotBlank()} base=$baseUrl model=$chatModel")
            if (ready) return
        }
        Log.w(TAG, "workspace .env 还没推到手机，或缺少 DEEPSEEK_API_KEY")
    }

    fun ask(
        question: String,
        storeHint: String? = null,
        onDelta: ((String) -> Unit)? = null,
        onTool: ((String, JSONObject) -> String)? = null,
    ): AiReply {
        val text = question.trim()
        if (text.isBlank()) {
            return AiReply("我没听清，再说一次。", "再说一次", false)
        }
        val prompt = if (storeHint.isNullOrBlank()) {
            text
        } else {
            storeHint
        }
        val reply = if (ready) {
            chat(prompt, onDelta, onTool)
        } else {
            null
        }
        val result = reply ?: fallback(text).also { fallback ->
            if (onDelta != null) {
                onDelta(fallback.speak)
            }
        }
        history.add("user" to text)
        history.add("assistant" to result.speak)
        while (history.size > 24) {
            history.removeAt(0)
        }
        return result
    }

    fun look(jpeg: ByteArray): AiReply {
        if (!ready) {
            return AiReply("还没配 DeepSeek 密钥，先看手机提示。", "未配置密钥", false)
        }
        if (jpeg.isEmpty()) {
            return AiReply("眼镜没拍到图。", "拍照失败", false)
        }
        return vision(jpeg) ?: AiReply("这张店招我这次没认出来。", "未认出店招", false)
    }

    private fun fallback(text: String): AiReply {
        val heard = text.take(16)
        val speak = if (ready) {
            "我听到了「$heard」，但 DeepSeek 这次没回上。"
        } else {
            "手机还没配 DeepSeek 密钥。在应用目录放 ai.env，写入 DEEPSEEK_API_KEY。"
        }
        return AiReply(speak, heard, false)
    }

    private fun chat(question: String, onDelta: ((String) -> Unit)?, onTool: ((String, JSONObject) -> String)?): AiReply? {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM))
        for ((role, content) in history.takeLast(12)) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))
        val tools = if (onTool != null) diningTools() else null
        val first = completeMessage(chatModel, messages, 25_000, jsonMode = false, tools = tools) ?: return null
        val calls = first.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0 && onTool != null) {
            val assistant = JSONObject().put("role", "assistant")
            val content = first.optString("content")
            if (content.isBlank()) {
                assistant.put("content", JSONObject.NULL)
            } else {
                assistant.put("content", content)
            }
            messages.put(assistant.put("tool_calls", calls))
            val ordered = orderedToolCalls(calls)
            for (index in 0 until ordered.length()) {
                val call = ordered.optJSONObject(index) ?: continue
                val id = call.optString("id")
                val fn = call.optJSONObject("function")
                val name = fn?.optString("name").orEmpty()
                val args = parseArgs(fn?.optString("arguments"))
                Log.i(TAG, "tool $name args=$args")
                val result = try {
                    onTool(name, args)
                } catch (error: Exception) {
                    JSONObject().put("ok", false).put("error", error.message ?: name).toString()
                }
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result),
                )
            }
            return streamChat(messages, onDelta, tools)
        }
        val text = first.optString("content").ifBlank { first.optString("reasoning_content") }
        if (text.isNotBlank()) {
            onDelta?.invoke(text)
        }
        return plainReply(text) ?: streamChat(messages, onDelta, tools)
    }

    private fun vision(jpeg: ByteArray): AiReply? {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val userContent = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "这是眼镜拍到的店招，帮我认店并简短介绍。"))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put("url", "data:image/jpeg;base64,$b64")
                            .put("detail", "low"),
                    ),
            )
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", VISION_SYSTEM))
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
            .put("max_tokens", 280)
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

    private fun diningTools(): JSONArray {
        return JSONArray()
            .put(fn("look_store", "用眼镜拍眼前店招并识别是哪家店。仅在用户要认店、看店招、问这是哪家时调用。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("reason", JSONObject().put("type", "string").put("description", "为什么要拍照认店")))))
            .put(fn("recommend", "按用户口味或条件推荐附近 2～3 家餐厅。用户说附近火锅、不要排队、有什么好吃的时调用。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("query", JSONObject().put("type", "string").put("description", "用户的口味或条件，如火锅、不要排队"))
                    .put("avoid_queue", JSONObject().put("type", "boolean").put("description", "是否只要少排队的店")))))
            .put(fn("select_store", "从刚才的推荐里选定一家，作为当前门店。用户说第一家、第二家、去某店名时调用。若用户同时要出发，接着调用 start_nav。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("index", JSONObject().put("type", "integer").put("description", "1 表示第一家，2 第二家，3 第三家"))
                    .put("name", JSONObject().put("type", "string").put("description", "店名或简称"))
                    .put("store_id", JSONObject().put("type", "string").put("description", "门店 id")))))
            .put(fn("start_nav", "开始到店步行导航。用户要去某家店或出发时调用。若用户说了店名，传入 name。没选店且没给店名时不要调用。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "string").put("description", "目的店名，如海底捞"))
                    .put("store_id", JSONObject().put("type", "string").put("description", "门店 id"))
                    .put("index", JSONObject().put("type", "integer").put("description", "1 第一家，2 第二家")))))
            .put(fn("stop_nav", "停止导航技能。用户说取消、到了、换一家时调用。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())))
    }

    private fun fn(name: String, description: String, parameters: JSONObject): JSONObject {
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
    }

    private fun orderedToolCalls(calls: JSONArray): JSONArray {
        val rank = mapOf(
            "stop_nav" to 0,
            "look_store" to 1,
            "recommend" to 2,
            "select_store" to 3,
            "start_nav" to 4,
        )
        val list = (0 until calls.length()).mapNotNull { calls.optJSONObject(it) }
            .sortedBy { rank[it.optJSONObject("function")?.optString("name")] ?: 9 }
        val ordered = JSONArray()
        list.forEach { ordered.put(it) }
        return ordered
    }

    private fun parseArgs(raw: String?): JSONObject {
        if (raw.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun streamChat(messages: JSONArray, onDelta: ((String) -> Unit)?, tools: JSONArray? = null): AiReply? {
        val body = JSONObject()
            .put("model", chatModel)
            .put("messages", messages)
            .put("max_tokens", 280)
            .put("stream", true)
            .put("thinking", JSONObject().put("type", "disabled"))
        if (tools != null) {
            body.put("tools", tools)
            body.put("tool_choice", "none")
        }
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

    private fun chatUrl(): String {
        val base = baseUrl.trimEnd('/')
        return "$base/chat/completions"
    }

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
            AiReply(speak.take(80), hud, true, store)
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
