package com.glass.dining.phone

import android.util.Log
import com.glass.dining.shared.agent.StreamingSynthGate
import com.glass.dining.shared.agent.SubtitleWord
import com.glass.dining.shared.agent.TtsTimeline
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阿里云 FlowingSpeechSynthesizer。握手失败由调用方回退 REST。
 * WebSocket 完成不等于播放完成。
 */
class NlsStreamingTtsClient(
    private val generation: Int,
    private val currentGen: () -> Int,
    private val onAudible: (Int, String) -> Unit,
    private val onProgress: (Int, String) -> Unit,
    private val onFinished: (Int) -> Unit,
    private val onFailed: (Int, String, String) -> Unit,
) {
    private val live = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val gate = StreamingSynthGate()
    private val startLatch = CountDownLatch(1)
    private val doneLatch = CountDownLatch(1)
    private val taskId = NlsClient.hexId()
    private val pending = ArrayList<String>()
    private val lock = Any()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var playback: PcmPlayback? = null
    @Volatile private var failMessage: String = ""
    @Volatile private var confirmed = ""
    private val words = ArrayList<SubtitleWord>()
    @Volatile private var sentenceText = ""

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
        if (!ok || !gate.started) {
            stop()
            return failMessage.ifBlank { "NLS 流式合成未就绪" }
        }
        return null
    }

    fun sendText(text: String) {
        if (text.isBlank() || !gate.acceptText() || generation != currentGen()) return
        synchronized(lock) {
            if (!gate.started) {
                pending.add(text)
                return
            }
        }
        runSynthesis(text)
    }

    fun finishInput() {
        if (!gate.requestStop()) return
        sendStop()
    }

    fun audibleText(): String = audibleNow()

    fun remainingText(): String = synchronized(lock) {
        pending.joinToString("")
    }

    fun stop() {
        live.set(false)
        val ws = socket
        socket = null
        playback?.release()
        playback = null
        ws?.cancel()
        startLatch.countDown()
        doneLatch.countDown()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!webSocket.send(startCommand())) fail("StartSynthesis 发送失败")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleEvent(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (stale() || bytes.size == 0) return
            val track = playback ?: PcmPlayback(NlsClient.sampleRate, generation, currentGen).also { playback = it }
            track.write(bytes.toByteArray())
            if (track.firstFrame) {
                onAudible(generation, audibleNow())
            }
            reportProgress(track)
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
            fail(header.optString("status_message").ifBlank { header.optString("status_text").ifBlank { "nls status=$status" } })
            return
        }
        val payload = json.optJSONObject("payload")
        when (name) {
            "SynthesisStarted" -> {
                val stopNow = gate.onStarted()
                startLatch.countDown()
                Log.i(TAG, "nls tts ws started task=$taskId")
                val queued = synchronized(lock) {
                    val copy = ArrayList(pending)
                    pending.clear()
                    copy
                }
                queued.forEach { runSynthesis(it) }
                if (stopNow) sendStop()
            }
            "SentenceBegin" -> {
                words.clear()
                sentenceText = ""
            }
            "SentenceSynthesis" -> applySubtitles(payload)
            "SentenceEnd" -> {
                applySubtitles(payload)
                synchronized(lock) {
                    if (sentenceText.isNotBlank()) confirmed += sentenceText
                    sentenceText = ""
                    words.clear()
                }
            }
            "SynthesisCompleted" -> {
                gate.onCompleted()
                live.set(false)
                Thread({
                    playback?.drain()
                    if (generation == currentGen() && !failed.get()) {
                        onProgress(generation, audibleNow().ifBlank { confirmed })
                        onFinished(generation)
                    }
                    doneLatch.countDown()
                }, "nls-tts-drain").start()
            }
            "TaskFailed" -> fail(header.optString("status_message").ifBlank { "TaskFailed" })
        }
    }

    private fun applySubtitles(payload: JSONObject?) {
        val parsed = parseSubtitles(payload)
        if (parsed.isEmpty()) return
        synchronized(lock) {
            words.clear()
            words.addAll(parsed)
            sentenceText = parsed.filter { !it.sentence }.joinToString("") { it.text }
        }
        playback?.let { reportProgress(it) }
    }

    private fun reportProgress(track: PcmPlayback) {
        val prefix = synchronized(lock) {
            TtsTimeline.audiblePrefix(track.playedMs(), words.toList(), sentenceText)
        }
        val heard = confirmed + prefix
        if (heard.isNotBlank()) onProgress(generation, heard)
    }

    private fun audibleNow(): String {
        val prefix = synchronized(lock) {
            TtsTimeline.audiblePrefix(playback?.playedMs() ?: 0L, words.toList(), sentenceText)
        }
        return confirmed + prefix
    }

    private fun sendStop() {
        socket?.send(command("StopSynthesis"))
    }

    private fun runSynthesis(text: String) {
        if (!gate.acceptText()) return
        val ws = socket ?: return
        val header = JSONObject()
            .put("appkey", NlsClient.appkey)
            .put("message_id", NlsClient.hexId())
            .put("task_id", taskId)
            .put("namespace", NS)
            .put("name", "RunSynthesis")
        val payload = JSONObject().put("text", text)
        ws.send(JSONObject().put("header", header).put("payload", payload).toString())
    }

    private fun startCommand(): String {
        val header = JSONObject()
            .put("appkey", NlsClient.appkey)
            .put("message_id", NlsClient.hexId())
            .put("task_id", taskId)
            .put("namespace", NS)
            .put("name", "StartSynthesis")
        val payload = JSONObject()
            .put("voice", NlsClient.voice)
            .put("format", "pcm")
            .put("sample_rate", NlsClient.sampleRate)
            .put("volume", NlsClient.volume)
            .put("enable_subtitle", true)
        return JSONObject().put("header", header).put("payload", payload).toString()
    }

    private fun command(name: String): String {
        val header = JSONObject()
            .put("appkey", NlsClient.appkey)
            .put("message_id", NlsClient.hexId())
            .put("task_id", taskId)
            .put("namespace", NS)
            .put("name", name)
        return JSONObject().put("header", header).toString()
    }

    private fun fail(message: String) {
        if (!failed.compareAndSet(false, true)) return
        failMessage = message
        live.set(false)
        startLatch.countDown()
        gate.onFailed()
        Log.w(TAG, "nls tts ws fail: $message")
        if (gate.started) {
            onFailed(generation, message, audibleNow())
        }
        doneLatch.countDown()
    }

    private fun stale(): Boolean = generation != currentGen() || failed.get()

    companion object {
        private const val TAG = "GlassDiningPhone"
        private const val NS = "FlowingSpeechSynthesizer"
        private const val SUCCESS = 20000000
        private val http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        fun parseSubtitles(payload: JSONObject?): List<SubtitleWord> {
            val arr = payload?.optJSONArray("subtitles") ?: return emptyList()
            val out = ArrayList<SubtitleWord>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                out.add(
                    SubtitleWord(
                        text = item.optString("text"),
                        beginMs = item.optInt("begin_time"),
                        endMs = item.optInt("end_time"),
                        beginIndex = item.optInt("begin_index"),
                        endIndex = item.optInt("end_index"),
                        sentence = item.optBoolean("sentence"),
                    ),
                )
            }
            return out
        }
    }
}
