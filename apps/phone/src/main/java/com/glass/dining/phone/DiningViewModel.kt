package com.glass.dining.phone

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glass.dining.shared.engine.DiningSession
import com.glass.dining.shared.engine.StoreVision
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.mock.MockCatalog
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.QaResult
import com.glass.dining.shared.model.Scene
import com.glass.dining.shared.model.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

data class PhoneUiState(
    val status: String = "点「对话」才接收眼镜语音。看店识别会用眼镜拍照。",
    val scenes: List<Scene> = MockCatalog.scenes,
    val sceneId: String = MockCatalog.defaultSceneId,
    val card: HudCard = HudCard.idle(),
    val match: MatchResult? = null,
    val lastQa: QaResult? = null,
    val question: String = "",
    val photo: Bitmap? = null,
    val recognizing: Boolean = false,
    val talking: Boolean = false,
    val asrPartial: String = "",
)

class DiningViewModel(app: Application) : AndroidViewModel(app) {
    private val session = DiningSession()
    private val _ui = MutableStateFlow(PhoneUiState())
    val ui: StateFlow<PhoneUiState> = _ui.asStateFlow()

    var onStartVoice: (() -> Unit)? = null
    var onNeedGlassAuth: (() -> Unit)? = null

    @Volatile private var asking: Boolean = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastHudAt: Long = 0
    @Volatile private var spokenChars: Int = 0
    private val lookedThisTurn = AtomicBoolean(false)

    init {
        CxrLinkHost.onStatus = { line ->
            _ui.update { state ->
                if (state.talking) state.copy(status = line) else state.copy(status = line)
            }
        }
        CxrLinkHost.onAudioPcm = { pcm -> GlassAsr.feed(pcm) }
        GlassAsr.onPartial = { text ->
            _ui.update { it.copy(asrPartial = text, status = "听到：$text") }
        }
        GlassAsr.onUtterance = { text -> onHeard(text) }
        GlassAsr.onError = { message -> setStatus(message) }
        val model = PhoneAi.statusLine
        _ui.update { it.copy(status = "点「对话」才接收眼镜语音。$model") }
    }

    override fun onCleared() {
        stopTalk(speak = false)
        CxrLinkHost.onAudioPcm = null
        GlassAsr.onPartial = null
        GlassAsr.onUtterance = null
        GlassAsr.onError = null
        super.onCleared()
    }

    fun setStatus(text: String) {
        _ui.update { it.copy(status = text) }
    }

    fun toggleTalk() {
        if (_ui.value.talking) {
            stopTalk(speak = true)
        } else {
            startTalk()
        }
    }

    fun selectScene(id: String) {
        session.setScene(id)
        val card = HudCard.idle()
        _ui.update { it.copy(sceneId = id, match = null, lastQa = null, card = card, photo = null) }
        CxrLinkHost.showCard(card)
    }

    fun look(forceStoreId: String? = null) {
        if (!forceStoreId.isNullOrBlank()) {
            publishMatch(session.look(forceStoreId), "已强制命中")
            return
        }
        startCapture()
    }

    fun next() {
        publishMatch(session.next(), "已切换下一家")
    }

    fun onHeard(spoken: String) {
        val text = spoken.trim()
        if (text.isBlank()) return
        if (asking) return
        if (!PhoneAi.ready && isLookIntent(text)) {
            startCapture()
        } else {
            ask(text)
        }
    }

    fun ask(question: String) {
        val text = question.trim().ifBlank { return }
        if (asking) return
        asking = true
        GlassAsr.muted = true
        _ui.update { state ->
            val thinking = if (state.talking) {
                HudCard.talkThinking(text)
            } else {
                HudCard.thinking(state.card)
            }
            CxrLinkHost.showCard(thinking)
            state.copy(
                question = text,
                status = "DeepSeek 思考中…",
                lastQa = QaResult(text, "ask", "思考中…", "思考中…"),
                card = thinking,
                asrPartial = "",
            )
        }
        val storeHint = session.lastMatch?.store?.let { "${it.shortName} ${it.name}" }
        spokenChars = 0
        lastHudAt = 0
        lookedThisTurn.set(false)
        viewModelScope.launch {
            val reply = if (PhoneAi.ready) {
                withContext(Dispatchers.IO) {
                    PhoneAi.ask(
                        text,
                        storeHint,
                        onDelta = { partial -> main.post { onStreamDelta(text, partial, done = false) } },
                        onLook = { lookStoreForAi() },
                    )
                }
            } else {
                val local = session.ask(text)
                streamLocal(local.answer)
                AiReply(local.answer, local.answer, false)
            }
            onStreamDelta(text, reply.speak, done = true)
            asking = false
        }
    }

    fun startVoice() {
        onStartVoice?.invoke()
    }

    fun updateQuestion(value: String) {
        _ui.update { it.copy(question = value) }
    }

    fun currentStores(): List<Store> {
        return MockCatalog.sceneById(_ui.value.sceneId).stores
    }

    private fun startTalk() {
        if (!CxrLinkHost.canTakePhoto()) {
            setStatus("眼镜未连上，无法接收眼镜语音。请先用乐奇连上这副眼镜。")
            return
        }
        if (!CxrAuth.hasMicrophone()) {
            setStatus("乐奇未授权眼镜麦克风，请在弹出页勾选麦克风")
            onNeedGlassAuth?.invoke()
            return
        }
        _ui.update { it.copy(talking = true, status = "正在打开对话…", asrPartial = "") }
        viewModelScope.launch {
            val asrError = withContext(Dispatchers.IO) { GlassAsr.start(getApplication()) }
            if (asrError != null) {
                CxrLinkHost.stopGlassMic()
                GlassAsr.stop()
                _ui.update { it.copy(talking = false, status = asrError) }
                return@launch
            }
            if (!_ui.value.talking) {
                GlassAsr.stop()
                CxrLinkHost.stopGlassMic()
                return@launch
            }
            GlassAsr.muted = false
            val mic = CxrLinkHost.startGlassMic()
            val listening = HudCard.talkListening()
            _ui.update {
                it.copy(
                    talking = true,
                    status = "$mic · ${PhoneAi.statusLine}",
                    card = listening,
                    asrPartial = "",
                )
            }
            CxrLinkHost.showCard(listening)
            PhoneTts.speak("我在听")
            Log.i(TAG, "talk on: $mic")
        }
    }

    private fun stopTalk(speak: Boolean) {
        asking = false
        CxrLinkHost.stopGlassMic()
        GlassAsr.stop()
        GlassAsr.muted = true
        PhoneTts.stop()
        val card = _ui.value.match?.let { HudCard.fromMatch(it) } ?: HudCard.idle()
        _ui.update {
            it.copy(
                talking = false,
                status = "对话已关闭，眼镜语音不再上传。",
                card = card,
                asrPartial = "",
            )
        }
        CxrLinkHost.showCard(card)
        if (speak) {
            PhoneTts.speak("对话已关闭")
        }
        Log.i(TAG, "talk off")
    }

    private fun startCapture() {
        val shooting = HudCard.shooting(_ui.value.card)
        _ui.update {
            it.copy(
                recognizing = true,
                status = "正在拍照…",
                card = shooting,
                lastQa = QaResult("看店识别", "look", "正在拍照识别店招…", "正在拍照"),
            )
        }
        CxrLinkHost.showCard(shooting)
        PhoneTts.speak("正在拍照")
        if (!CxrLinkHost.canTakePhoto()) {
            failCapture("眼镜未连上，无法拍照。请先用乐奇连上这副眼镜。")
            return
        }
        if (!CxrAuth.hasCamera()) {
            failCapture("乐奇未授权眼镜相机，请在弹出页勾选相机")
            onNeedGlassAuth?.invoke()
            return
        }
        CxrLinkHost.takePhoto { bytes, error ->
            if (bytes == null) {
                failCapture(error ?: "眼镜拍照失败")
                return@takePhoto
            }
            viewModelScope.launch {
                val decoded = withContext(Dispatchers.Default) { decodeJpeg(bytes) }
                if (decoded == null) {
                    failCapture("眼镜回了图，但不是可显示的照片")
                } else {
                    recognizePhoto(decoded, fingerprintOf(bytes), bytes, "眼镜拍照")
                }
            }
        }
    }

    private fun failCapture(reason: String) {
        Log.w(TAG, "capture failed: $reason")
        asking = false
        GlassAsr.muted = false
        val card = _ui.value.card.withExtra("拍照失败")
        _ui.update {
            it.copy(
                recognizing = false,
                status = reason,
                card = card,
                lastQa = QaResult("看店识别", "look", reason, reason),
            )
        }
        CxrLinkHost.showCard(card)
        PhoneTts.speak("拍照失败，请看手机提示")
    }

    private fun recognizePhoto(bitmap: Bitmap, fingerprint: Long, jpeg: ByteArray, source: String) {
        _ui.update { it.copy(photo = bitmap, recognizing = false) }
        viewModelScope.launch {
            if (PhoneAi.ready) {
                val vision = withContext(Dispatchers.IO) { PhoneAi.look(jpeg) }
                val hint = vision.store.ifBlank { vision.hud }.ifBlank { vision.speak }
                val scene = MockCatalog.sceneById(_ui.value.sceneId)
                val matched = StoreVision.matchStore(scene, hint)
                if (matched != null) {
                    publishMatch(session.look(visionHint = hint), "$source · DeepSeek 认店")
                } else if (vision.fromLlm) {
                    publishVision(vision)
                } else {
                    publishMatch(session.look(imageFingerprint = fingerprint), source)
                }
            } else {
                publishMatch(session.look(imageFingerprint = fingerprint), source)
            }
        }
    }

    private fun publishVision(reply: AiReply) {
        asking = false
        val title = reply.store.ifBlank { "眼前" }.take(12)
        val card = HudCard(
            title = title,
            meta = "DeepSeek 视觉",
            wait = reply.hud,
            extra = reply.speak,
        )
        _ui.update {
            it.copy(
                lastQa = QaResult("看店识别", "look", reply.speak, reply.speak),
                card = card,
                status = reply.speak,
                recognizing = false,
            )
        }
        CxrLinkHost.showCard(card)
        PhoneTts.speak(reply.speak)
    }

    private fun publishMatch(result: MatchResult, source: String) {
        asking = false
        val card = HudCard.fromMatch(result)
        val summary = "$source：${result.store.shortName}"
        _ui.update {
            it.copy(
                match = result,
                lastQa = QaResult("看店识别", "look", summary, result.tts),
                card = card,
                status = result.tts,
                recognizing = false,
            )
        }
        CxrLinkHost.showCard(card)
        PhoneTts.speak(result.tts)
        Log.i(TAG, "look ${result.store.id} $summary")
    }

    private fun lookStoreForAi(): String {
        main.post {
            val shooting = HudCard.shooting(_ui.value.card)
            _ui.update {
                it.copy(
                    recognizing = true,
                    status = "AI 要看店，正在拍照…",
                    card = shooting,
                    lastQa = QaResult("看店识别", "look", "正在拍照识别店招…", "正在拍照"),
                )
            }
            CxrLinkHost.showCard(shooting)
            PhoneTts.speak("我看一下这家店")
        }
        if (!CxrLinkHost.canTakePhoto()) {
            return failLook("眼镜未连上，无法拍照")
        }
        if (!CxrAuth.hasCamera()) {
            main.post { onNeedGlassAuth?.invoke() }
            return failLook("乐奇未授权眼镜相机")
        }
        val (bytes, error) = captureSync()
        if (bytes == null) {
            return failLook(error ?: "眼镜拍照失败")
        }
        val bitmap = decodeJpeg(bytes)
        main.post { _ui.update { it.copy(photo = bitmap, recognizing = false) } }
        val vision = PhoneAi.look(bytes)
        val hint = vision.store.ifBlank { vision.hud }.ifBlank { vision.speak }
        val scene = MockCatalog.sceneById(_ui.value.sceneId)
        val matched = StoreVision.matchStore(scene, hint)
        if (matched == null) {
            Log.i(TAG, "look_store vision unmatched store=${vision.store}")
            return JSONObject()
                .put("ok", false)
                .put("vision", vision.speak)
                .put("store", vision.store)
                .toString()
        }
        val result = session.look(visionHint = hint)
        lookedThisTurn.set(true)
        val card = HudCard.fromMatch(result)
        main.post {
            _ui.update {
                it.copy(
                    match = result,
                    card = card,
                    recognizing = false,
                    status = result.tts,
                    lastQa = QaResult("看店识别", "look", result.tts, result.tts),
                )
            }
            CxrLinkHost.showCard(card)
        }
        Log.i(TAG, "look_store matched ${result.store.id}")
        return storeFactsJson(result, vision)
    }

    private fun failLook(reason: String): String {
        Log.w(TAG, "look_store failed: $reason")
        main.post {
            val card = _ui.value.card.withExtra("拍照失败")
            _ui.update {
                it.copy(
                    recognizing = false,
                    status = reason,
                    card = card,
                )
            }
            CxrLinkHost.showCard(card)
        }
        return JSONObject().put("ok", false).put("error", reason).toString()
    }

    private fun captureSync(): Pair<ByteArray?, String?> {
        val latch = CountDownLatch(1)
        var bytes: ByteArray? = null
        var error: String? = "眼镜拍照超时"
        CxrLinkHost.takePhoto { data, err ->
            bytes = data
            error = err
            latch.countDown()
        }
        val ok = latch.await(15, TimeUnit.SECONDS)
        return if (ok) bytes to error else null to "眼镜拍照超时"
    }

    private fun storeFactsJson(result: MatchResult, vision: AiReply): String {
        val store = result.store
        val deals = store.deals.joinToString("；") { "${it.title}现价${it.price}" }
        return JSONObject()
            .put("ok", true)
            .put("store", store.name)
            .put("shortName", store.shortName)
            .put("rating", store.rating)
            .put("avgPrice", store.avgPrice)
            .put("waitMinutes", store.waitMinutes)
            .put("openNow", store.openNow)
            .put("signatures", store.signatures.joinToString("、"))
            .put("deals", deals)
            .put("address", store.address)
            .put("vision", vision.speak)
            .toString()
    }

    private suspend fun streamLocal(answer: String) {
        val step = 2
        var index = step
        while (index < answer.length) {
            onStreamDelta("", answer.take(index), done = false)
            kotlinx.coroutines.delay(50)
            index += step
        }
    }

    private fun onStreamDelta(question: String, partial: String, done: Boolean) {
        if (partial.isBlank()) return
        val now = SystemClock.uptimeMillis()
        if (!done && now - lastHudAt < 80) {
            speakNewSentences(partial, done = false)
            return
        }
        lastHudAt = now
        val match = session.lastMatch
        val card = if (lookedThisTurn.get() && match != null) {
            if (done) {
                HudCard.fromMatch(match)
            } else {
                HudCard.fromMatch(match).copy(extra = partial.takeLast(HudCard.LINE)).clipped()
            }
        } else {
            HudCard.fromTalkText(partial)
        }
        _ui.update {
            it.copy(
                card = card,
                lastQa = QaResult(it.question.ifBlank { question }, "ask", partial, partial),
                status = partial,
            )
        }
        CxrLinkHost.showCard(card)
        speakNewSentences(partial, done)
    }

    private fun speakNewSentences(partial: String, done: Boolean) {
        val unread = if (spokenChars >= partial.length) {
            ""
        } else {
            partial.substring(spokenChars)
        }
        if (unread.isBlank() && !done) return
        val ends = setOf('。', '！', '？', '；', '!', '?', '.', '\n')
        val buf = StringBuilder()
        for (ch in unread) {
            buf.append(ch)
            if (ch in ends && buf.isNotBlank()) {
                val chunk = buf.toString().trim()
                if (spokenChars == 0) PhoneTts.speak(chunk) else PhoneTts.speakMore(chunk)
                spokenChars += buf.length
                buf.clear()
            }
        }
        if (done && buf.isNotBlank()) {
            val chunk = buf.toString().trim()
            if (spokenChars == 0) PhoneTts.speak(chunk) else PhoneTts.speakMore(chunk)
            spokenChars = partial.length
        }
    }

    companion object {
        private const val TAG = "GlassDiningPhone"

        fun isLookIntent(text: String): Boolean {
            return listOf("看店", "识别", "拍照", "拍一张", "看看这家", "店招").any { text.contains(it) }
        }

        fun decodeJpeg(bytes: ByteArray): Bitmap? {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        fun fingerprintOf(bytes: ByteArray): Long {
            val crc = CRC32()
            crc.update(bytes)
            return crc.value
        }
    }
}
