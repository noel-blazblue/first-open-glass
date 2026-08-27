package com.glass.dining.phone

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阿里云 NLS 实时转写（WebSocket SpeechTranscriber）。
 * https://help.aliyun.com/zh/isi/developer-reference/websocket
 */
class NlsTranscriber {
    var onPartial: ((String) -> Unit)? = null
    var onSentence: ((String) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    private val live = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val startLatch = CountDownLatch(1)
    @Volatile private var failMessage: String = ""
    @Volatile private var socket: WebSocket? = null
    private val taskId = hexId()

    fun start(): String? {
        val auth = NlsClient.ensureToken()
        if (auth != null) return auth
        val token = NlsClient.currentToken()
        if (token.isBlank()) return "NLS Token 为空"
        live.set(true)
        val request = Request.Builder()
            .url(NlsClient.websocketUrl())
            .header("X-NLS-Token", token)
            .build()
        socket = http.newWebSocket(request, Listener())
        val ok = startLatch.await(8, TimeUnit.SECONDS)
        if (!ok || !started.get()) {
            stop()
            return failMessage.ifBlank { "NLS 流式识别未就绪" }
        }
        return null
    }

    fun send(pcm: ByteArray): Boolean {
        if (!live.get() || failed.get()) return false
        val ws = socket ?: return false
        if (pcm.isEmpty()) return true
        return ws.send(pcm.toByteString())
    }

    fun stop() {
        live.set(false)
        val ws = socket
        socket = null
        if (ws != null && started.get()) {
            try {
                ws.send(command("StopTranscription"))
            } catch (_: Exception) {
            }
            ws.close(1000, "stop")
        } else {
            ws?.cancel()
        }
        started.set(false)
        startLatch.countDown()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val sent = webSocket.send(startCommand())
            if (!sent) {
                fail("StartTranscription 发送失败")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleEvent(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(t.message ?: "websocket failed")
        }
    }

    private fun handleEvent(raw: String) {
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        val header = json.optJSONObject("header") ?: return
        val name = header.optString("name")
        val status = header.optInt("status")
        if (status != 0 && status != SUCCESS) {
            fail(header.optString("status_text").ifBlank { "nls status=$status" })
            return
        }
        val payload = json.optJSONObject("payload")
        val result = payload?.optString("result").orEmpty().replace("\\s+".toRegex(), "")
        when (name) {
            "TranscriptionStarted" -> {
                started.set(true)
                startLatch.countDown()
                Log.i(TAG, "nls ws started task=$taskId")
            }
            "TranscriptionResultChanged" -> {
                if (result.isNotBlank()) onPartial?.invoke(result)
            }
            "SentenceEnd" -> {
                if (result.isNotBlank()) onSentence?.invoke(result)
            }
            "TranscriptionCompleted" -> live.set(false)
            "TaskFailed" -> fail(header.optString("status_text").ifBlank { "TaskFailed" })
        }
    }

    private fun startCommand(): String {
        val header = JSONObject()
            .put("appkey", NlsClient.appkey)
            .put("message_id", hexId())
            .put("task_id", taskId)
            .put("namespace", "SpeechTranscriber")
            .put("name", "StartTranscription")
        val payload = JSONObject()
            .put("format", "pcm")
            .put("sample_rate", 16_000)
            .put("enable_intermediate_result", true)
            .put("enable_punctuation_prediction", true)
            .put("enable_inverse_text_normalization", true)
            .put("max_sentence_silence", 800)
        return JSONObject().put("header", header).put("payload", payload).toString()
    }

    private fun command(name: String): String {
        val header = JSONObject()
            .put("appkey", NlsClient.appkey)
            .put("message_id", hexId())
            .put("task_id", taskId)
            .put("namespace", "SpeechTranscriber")
            .put("name", name)
        return JSONObject().put("header", header).toString()
    }

    private fun fail(message: String) {
        if (!failed.compareAndSet(false, true)) return
        failMessage = message
        live.set(false)
        startLatch.countDown()
        Log.w(TAG, "nls ws fail: $message")
        onFailed?.invoke(message)
    }

    companion object {
        private const val TAG = "GlassDiningPhone"
        private const val SUCCESS = 20000000
        private val http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        private fun hexId(): String {
            return UUID.randomUUID().toString().replace("-", "")
        }
    }
}
