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
    val isStore: Boolean = true,
    val confidence: Float = 0f,
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
- 认眼前这家店、看店招、看眼前图片/屏幕里的店、问「这是哪家」：调用 look_store。画面可以是店门口，也可以是照片或屏幕。视频流里端侧已经认出的店会自动设为当前门店，问排队/人均直接答。已经有当前门店且只是问排队/人均/菜单/包间，不要再看画面。
- look_store 若带回店名或 vision 文案，直接告诉用户看到的店。没对上已录入目录也可以说店名。禁止说「请到真实门店」「请先录入」「无法识别请到店」。只有用户要导航或问排队/人均等目录事实、且目录没有这家时，才说目录里没有、无法带路。
- 导航中问入口、路牌、这是不是店门口：调用 read_sign。从眼镜画面抽一帧辅助，不要改路线，也不要 stop_nav。
- 看看菜单、点什么：调用 read_menu。没有当前门店就先认店或推荐。
- 用户选定一家（第一家、第二家、去某店名）：调用 select_store。若同一句话已经要出发，接着调用 start_nav。
- 有当前门店、且用户要出发（走、出发、现在去、好、导航、带我去等任意去意）：调用 start_nav。用户说了店名（如去海底捞）时，start_nav 必须带上 name，或先 select_store 再 start_nav。不要把上一张店（例如喜茶）当成目的地。没选店就先 recommend 或追问「去哪家」，禁止空目的开导航。刚推荐还没选店时不要 start_nav。
- 只是在问排队/人均/包间，不要 start_nav。
- 扫码买单、付款、结账：先 checkout（不要带 confirm）。工具会给出金额和商户，必须再问用户确认；用户明确说确认/付钱后再 checkout，confirm=true。没确认禁止付款。
- 核销这张券、扫券码：调用 scan_coupon。工具只识别画面，不等于核销成功。用户选定并确认后再 redeem_coupon 且 confirm=true。没确认不要核销。扫到桌码不是核销成功。
- 用美团券、列出有哪些券：先 list_coupons。用户选定一张后 redeem_coupon 且 confirm=true。
- 取消、到了、换一家：先 stop_nav（如果正在导航），换一家再 recommend。
- 导航中问店，直接答，不要 stop_nav，也不要再 start_nav。
- 闲聊、听不清、无关餐饮，不要乱调工具。
调用工具后，用工具返回的数据做一两句口语；选店后确认店名即可；开始导航说「开始去…」。支付和券都是 mock，可以说「演示已付/已核销」，不要说已经真的扣款。"""
    private const val VISION_SYSTEM = """你是乐奇 AI 眼镜上的到店餐饮助手。下面是端侧抽到的文字，以及一张画面（可能是店招、照片或屏幕）。
只输出一个 JSON，不要 markdown：
{"speak":"口语一两句，不超过80字","hud":"镜片短句，不超过16字","store":"店名或品牌；能认出就写，不要因为是照片/屏幕就留空","scene":"store_sign|street_sign|menu|coupon|unknown","confidence":0.0}
招牌字能读就读；艺术字、英文 logo、照片里的店也要给出最可能的品牌。只有完全没有可用画面才说看不清。不要要求用户去真实门店，不要提录入 App。评分、排队、营业不要猜。不要输出二维码原文或完整券码。不要说已经付款或已经核销。"""
    private const val PROBE_SYSTEM = """你只判断画面里有没有餐饮店的门头/店招/店名。
只输出一个 JSON，不要 markdown：
{"is_store":false,"store":"","confidence":0.0,"speak":"","hud":""}
规则：
- 能看到店招、门头、店名 logo，或屏幕/照片/网页里的店招图：is_store=true。store 写品牌或店名（如巴奴毛肚火锅）；speak 口语一两句，不超过80字；hud 不超过16字。
- 旁边有键盘、显示器边框、IDE、网页控件，只要画面里仍有店招，仍是 true，不要判 false。
- 满屏键盘、纯代码/IDE、桌面图标、路人、没有店招：is_store=false。此时 store、speak、hud 必须都是空字符串。
- 艺术字读不全时，按最可能的品牌填写 store，不要因为 OCR 是 logitech 就说不是店。
- 禁止说「请到真实门店」「请先录入」。不要编排队和人均。"""

    @Volatile private var token: String = ""
    @Volatile private var baseUrl: String = DEFAULT_BASE
    @Volatile private var chatModel: String = DEFAULT_CHAT
    @Volatile private var visionModel: String = DEFAULT_VISION
    @Volatile var amapKey: String = ""
        private set
    private val history = CopyOnWriteArrayList<Pair<String, String>>()
    private val harness = DiningAgentHarness(maxSteps = 3)

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
            chat(prompt, text, onDelta, onTool)
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

    fun look(jpeg: ByteArray, task: String = "look_store", ocrText: String = ""): AiReply {
        if (!ready) {
            return AiReply("还没配 DeepSeek 密钥，先看手机提示。", "未配置密钥", false)
        }
        if (jpeg.isEmpty()) {
            return AiReply("没拿到眼镜画面。", "没画面", false)
        }
        return vision(jpeg, task, ocrText) ?: AiReply("这张画面我这次没认出来。", "未认出", false)
    }

    fun probeStore(jpeg: ByteArray, ocrText: String = ""): AiReply? {
        if (!ready || jpeg.isEmpty()) return null
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val prompt = buildString {
            append("任务：probe_store。判断这张图是不是店的门头/店招/店的照片。")
            if (ocrText.isNotBlank()) {
                append(" 端侧OCR可能是键盘或屏幕噪点，仅供参考，不要据此认定不是店：")
                append(ocrText.take(80))
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
            .put(JSONObject().put("role", "system").put("content", PROBE_SYSTEM))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val raw = completeMessage(visionModel, messages, 25_000, jsonMode = true, tools = null)
            ?.optString("content")
            .orEmpty()
        return parseProbe(raw)
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

    private fun chat(
        question: String,
        userText: String,
        onDelta: ((String) -> Unit)?,
        onTool: ((String, JSONObject) -> String)?,
    ): AiReply? {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM))
        for ((role, content) in history.takeLast(12)) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))
        if (onTool == null) {
            return streamChat(messages, onDelta)
        }

        val tools = diningTools()
        val outcome = harness.run(
            messages = messages,
            tools = tools,
            userText = userText,
            requestModel = { currentMessages, currentTools ->
                completeMessage(chatModel, currentMessages, 25_000, jsonMode = false, tools = currentTools)
            },
            orderCalls = ::orderedToolCalls,
            executeTool = onTool,
        )
        if (outcome.failed) return null
        if (outcome.reachedLimit) {
            return streamChat(messages, onDelta, tools)
        }
        val final = outcome.finalMessage ?: return null
        val text = final.optString("content").ifBlank { final.optString("reasoning_content") }
        if (text.isNotBlank()) {
            onDelta?.invoke(text)
        }
        return plainReply(text)
    }

    private fun vision(jpeg: ByteArray, task: String, ocrText: String): AiReply? {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val prompt = buildString {
            append("任务：$task。")
            when (task) {
                "read_menu" -> append("这是菜单，抽出菜名和价格，不要编造没有的菜。")
                "read_sign" -> append("这是路牌或入口，只读看到的字，不要判断能不能过马路。")
                "scan_coupon" -> append("这是团购券或券码，只读标题和是否像可用券，不要输出完整券码，不要说已经核销。")
                else -> append("这是眼镜抽到的一帧。可能是店门口，也可能是照片或屏幕上的店。读出店名或品牌并简短介绍；不确定也给出最可能的名字，写进 store。不要说请到真实门店。")
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
            .put(fn("look_store", "从眼镜当前画面抽一帧认店。画面可以是店招、照片或屏幕里的店。认出店名即可，不必要求用户站在店门口。用户要认店、看店招、看眼前这张图是哪家店时调用。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("reason", JSONObject().put("type", "string").put("description", "为什么要看眼前这家店")))))
            .put(fn("read_sign", "从眼镜画面抽一帧读路牌或入口。导航中问入口、路牌时调用。不要据此改路线。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("reason", JSONObject().put("type", "string")))))
            .put(fn("read_menu", "从眼镜画面抽一帧读菜单，抽出菜名和价格。用户说看看菜单、点什么时调用。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("reason", JSONObject().put("type", "string")))))
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
            .put(fn("list_coupons", "列出当前门店可核销的 mock 美团券。用户说用券、有哪些券时调用。不要看画面。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())))
            .put(fn("scan_coupon", "从眼镜画面抽一帧识别券码或券面。用户说核销这张券、扫券时调用。扫到不等于核销成功。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("reason", JSONObject().put("type", "string")))))
            .put(fn("redeem_coupon", "核销一张 mock 美团券。必须用户明确确认后才把 confirm 设为 true。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("coupon_id", JSONObject().put("type", "string").put("description", "券 id"))
                    .put("title", JSONObject().put("type", "string").put("description", "券标题"))
                    .put("confirm", JSONObject().put("type", "boolean").put("description", "用户是否明确确认核销")))))
            .put(fn("checkout", "从眼镜画面抽一帧扫码买单（mock）。先不要带 confirm，只识别付款码并展示金额。用户明确确认后再 checkout，confirm=true。", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("confirm", JSONObject().put("type", "boolean").put("description", "用户是否明确确认付款")))))
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
            "read_sign" to 2,
            "read_menu" to 3,
            "scan_coupon" to 4,
            "recommend" to 5,
            "select_store" to 6,
            "list_coupons" to 7,
            "checkout" to 8,
            "redeem_coupon" to 9,
            "start_nav" to 10,
        )
        val list = (0 until calls.length()).mapNotNull { calls.optJSONObject(it) }
            .sortedBy { rank[it.optJSONObject("function")?.optString("name")] ?: 9 }
        val ordered = JSONArray()
        list.forEach { ordered.put(it) }
        return ordered
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

    private fun parseProbe(raw: String): AiReply? {
        val match = Regex("""\{[\s\S]*\}""").find(raw) ?: return null
        return try {
            val json = JSONObject(match.value)
            val isStore = jsonIsTrue(json.opt("is_store"))
            val store = if (isStore) json.optString("store").trim() else ""
            val speak = if (isStore) json.optString("speak").trim() else ""
            val hudRaw = if (isStore) {
                json.optString("hud").ifBlank { store.ifBlank { speak } }
            } else {
                ""
            }
            val hud = hudRaw.replace("\\s".toRegex(), "").take(16)
            val confidence = json.optDouble("confidence", 0.0).toFloat()
            AiReply(speak.take(80), hud, true, store, isStore, confidence)
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonIsTrue(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
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
