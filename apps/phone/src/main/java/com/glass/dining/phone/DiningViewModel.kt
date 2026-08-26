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
import com.glass.dining.phone.nav.AmapWalking
import com.glass.dining.phone.nav.GeoPoint
import com.glass.dining.phone.nav.NavLocationService
import com.glass.dining.phone.nav.PhoneGps
import com.glass.dining.phone.nav.StorePins
import com.glass.dining.phone.nav.WalkGuide
import com.glass.dining.shared.engine.DiningSession
import com.glass.dining.shared.engine.StoreVision
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.mock.MockCatalog
import com.glass.dining.shared.model.ActiveSkill
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.QaResult
import com.glass.dining.shared.model.Scene
import com.glass.dining.shared.model.Store
import com.glass.dining.shared.nav.NavHint
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
import java.util.zip.CRC32

data class PhoneUiState(
    val status: String = "连上眼镜后会自动听。点按钮可暂停。",
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
    val skill: ActiveSkill = ActiveSkill.NONE,
    val navHint: NavHint? = null,
)

class DiningViewModel(app: Application) : AndroidViewModel(app) {
    private val session = DiningSession()
    private val _ui = MutableStateFlow(PhoneUiState())
    val ui: StateFlow<PhoneUiState> = _ui.asStateFlow()

    var onStartVoice: (() -> Unit)? = null
    var onNeedGlassAuth: (() -> Unit)? = null

    @Volatile private var asking: Boolean = false
    private val main = Handler(Looper.getMainLooper())
    private val unmuteAfterTts = Runnable {
        if (!_ui.value.talking || asking || _ui.value.recognizing || PhoneTts.speaking) return@Runnable
        GlassAsr.muted = false
        showListeningCard()
    }
    @Volatile private var lastHudAt: Long = 0
    @Volatile private var spokenChars: Int = 0
    @Volatile private var talkPausedByUser: Boolean = false
    @Volatile private var recommendedThisTurn: Boolean = false
    @Volatile private var selectedThisTurn: Boolean = false
    @Volatile private var announcedArrive: Boolean = false
    @Volatile private var lastRerouteAt: Long = 0

    init {
        CxrLinkHost.onStatus = { line ->
            _ui.update { state ->
                if (state.talking) state.copy(status = line) else state.copy(status = line)
            }
        }
        CxrLinkHost.onAudioPcm = { pcm -> GlassAsr.feed(pcm) }
        GlassAsr.onPartial = { text -> onAsrPartial(text) }
        GlassAsr.onUtterance = { text -> onHeard(text) }
        GlassAsr.onError = { message -> setStatus(message) }
        PhoneTts.onIdle = { onTalkIdle() }
        CxrLinkHost.onHudOpened = { onHudOpened() }
        CxrLinkHost.onFrame = { CxrLinkHost.ackFrame() }
        val model = PhoneAi.statusLine
        _ui.update { it.copy(status = "连上眼镜后会自动听。说想吃什么。$model") }
    }

    override fun onCleared() {
        main.removeCallbacks(unmuteAfterTts)
        stopTalk(speak = false)
        stopNavInternal(silent = true)
        CxrLinkHost.onAudioPcm = null
        GlassAsr.onPartial = null
        GlassAsr.onUtterance = null
        GlassAsr.onError = null
        PhoneTts.onIdle = null
        CxrLinkHost.onHudOpened = null
        CxrLinkHost.onFrame = null
        super.onCleared()
    }

    fun setStatus(text: String) {
        _ui.update { it.copy(status = text) }
    }

    fun toggleTalk() {
        if (_ui.value.talking) {
            talkPausedByUser = true
            stopTalk(speak = true)
        } else {
            talkPausedByUser = false
            startTalk()
        }
    }

    fun selectScene(id: String) {
        stopNavInternal(silent = true)
        session.setScene(id)
        val card = HudCard.idle()
        _ui.update {
            it.copy(
                sceneId = id,
                match = null,
                lastQa = null,
                card = card,
                photo = null,
                skill = ActiveSkill.NONE,
                navHint = null,
            )
        }
        CxrLinkHost.showCard(card)
    }

    fun look(forceStoreId: String? = null) {
        if (!forceStoreId.isNullOrBlank()) {
            stopNavInternal(silent = true)
            publishMatch(session.look(forceStoreId), "已强制命中")
            return
        }
        if (session.activeSkill == ActiveSkill.NAV) {
            stopNavInternal(silent = true)
        }
        startCapture()
    }

    fun next() {
        stopNavInternal(silent = true)
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
        main.removeCallbacks(unmuteAfterTts)
        GlassAsr.muted = true
        recommendedThisTurn = false
        selectedThisTurn = false
        val thinking = HudCard.thinking(currentMatch(), text, navCard())
        _ui.update { state ->
            CxrLinkHost.showCard(thinking)
            state.copy(
                question = text,
                status = "DeepSeek 思考中…",
                lastQa = QaResult(text, "ask", "思考中…", "思考中…"),
                card = thinking,
                asrPartial = "",
            )
        }
        val storeHint = sessionHint(text)
        spokenChars = 0
        lastHudAt = 0
        viewModelScope.launch {
            try {
                val reply = if (PhoneAi.ready) {
                    withContext(Dispatchers.IO) {
                        PhoneAi.ask(
                            text,
                            storeHint,
                            onDelta = { partial -> main.post { onStreamDelta(text, partial, done = false) } },
                            onTool = { name, args -> runTool(name, args) },
                        )
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        val local = localAgent(text)
                        streamLocal(local.speak)
                        local
                    }
                }
                onStreamDelta(text, reply.speak, done = true)
            } finally {
                asking = false
                if (_ui.value.talking && !PhoneTts.speaking) {
                    scheduleUnmute()
                }
            }
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
        if (_ui.value.talking) return
        if (!CxrLinkHost.canTakePhoto()) {
            setStatus("眼镜未连上，无法接收眼镜语音。请先用乐奇连上这副眼镜。")
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
            val listening = HudCard.listening(currentMatch(), navCard = navCard())
            CxrLinkHost.showCard(listening)
            val mic = CxrLinkHost.startGlassMic()
            _ui.update {
                it.copy(
                    talking = true,
                    status = "$mic · ${PhoneAi.statusLine}",
                    card = listening,
                    asrPartial = "",
                )
            }
            Log.i(TAG, "talk on: $mic")
            main.postDelayed({
                if (!_ui.value.talking) return@postDelayed
                if (CxrLinkHost.pcmPackets > 0) return@postDelayed
                Log.w(TAG, "no pcm after mic.on, retry")
                CxrLinkHost.startGlassMic()
                setStatus("还没听到眼镜麦，正在重试…")
            }, 2_500)
        }
    }

    private fun stopTalk(speak: Boolean) {
        asking = false
        main.removeCallbacks(unmuteAfterTts)
        CxrLinkHost.stopGlassMic()
        GlassAsr.stop()
        GlassAsr.muted = true
        val card = currentHud()
        _ui.update { it.copy(talking = false) }
        PhoneTts.stop()
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
        val shooting = HudCard.shooting(currentMatch(), _ui.value.card)
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
        val card = failCard("拍照失败")
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
        val card = HudCard.talk(reply.speak)
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
                skill = ActiveSkill.BROWSE,
                navHint = null,
            )
        }
        CxrLinkHost.showCard(card)
        PhoneTts.speak(result.tts)
        Log.i(TAG, "look ${result.store.id} $summary")
    }

    private fun lookStoreForAi(): String {
        if (session.activeSkill == ActiveSkill.NAV) {
            val current = session.lastMatch
            if (current != null) {
                return storeFactsJson(current, null)
                    .put("note", "正在导航，没有再拍照，这是当前目的店")
                    .toString()
            }
        }
        main.post {
            val shooting = HudCard.shooting(currentMatch(), _ui.value.card)
            _ui.update {
                it.copy(
                    recognizing = true,
                    status = "AI 要看店，正在拍照…",
                    card = shooting,
                    lastQa = QaResult("看店识别", "look", "正在拍照识别店招…", "正在拍照"),
                )
            }
            CxrLinkHost.showCard(shooting)
            PhoneTts.speak("我看一下")
        }
        if (!CxrLinkHost.canTakePhoto()) {
            return failLook("眼镜未连上，无法拍照")
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
        selectedThisTurn = true
        val card = HudCard.fromMatch(result)
        main.post {
            _ui.update {
                it.copy(
                    match = result,
                    card = card,
                    recognizing = false,
                    status = result.tts,
                    lastQa = QaResult("看店识别", "look", result.tts, result.tts),
                    skill = ActiveSkill.BROWSE,
                    navHint = null,
                )
            }
            CxrLinkHost.showCard(card)
        }
        Log.i(TAG, "look_store matched ${result.store.id}")
        return storeFactsJson(result, vision).toString()
    }

    private fun failLook(reason: String): String {
        Log.w(TAG, "look_store failed: $reason")
        main.post {
            val card = failCard("拍照失败")
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

    private fun storeFactsJson(result: MatchResult, vision: AiReply?): JSONObject {
        val store = result.store
        val deals = store.deals.joinToString("；") { "${it.title}现价${it.price}" }
        val json = JSONObject()
            .put("ok", true)
            .put("store_id", store.id)
            .put("store", store.name)
            .put("shortName", store.shortName)
            .put("rating", store.rating)
            .put("avgPrice", store.avgPrice)
            .put("waitMinutes", store.waitMinutes)
            .put("openNow", store.openNow)
            .put("signatures", store.signatures.joinToString("、"))
            .put("deals", deals)
            .put("address", store.address)
            .put("candidates", result.candidates.joinToString("、") { it.shortName })
        if (vision != null) {
            json.put("vision", vision.speak)
        }
        return json
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
        val skillLocked = session.activeSkill == ActiveSkill.NAV ||
            recommendedThisTurn ||
            selectedThisTurn
        if (skillLocked) {
            _ui.update {
                it.copy(
                    lastQa = QaResult(it.question.ifBlank { question }, "ask", partial, partial),
                    status = partial,
                )
            }
            if (done) {
                val card = navCard() ?: _ui.value.card
                CxrLinkHost.showCard(card)
            }
            speakNewSentences(partial, done)
            return
        }
        val card = HudCard.talk(partial)
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

    private fun currentMatch(): MatchResult? {
        return _ui.value.match ?: session.lastMatch
    }

    private fun failCard(line: String): HudCard {
        val match = currentMatch()
        return if (match != null) {
            HudCard.fromMatch(match).copy(extra = line.take(HudCard.LINE)).clipped()
        } else {
            _ui.value.card.withExtra(line)
        }
    }

    private fun showListeningCard() {
        if (!_ui.value.talking || asking || _ui.value.recognizing) return
        val card = HudCard.listening(currentMatch(), navCard = navCard())
        _ui.update { it.copy(card = card) }
        CxrLinkHost.showCard(card)
    }

    private fun onHudOpened() {
        if (talkPausedByUser) return
        if (_ui.value.talking) {
            val mic = CxrLinkHost.startGlassMic()
            setStatus("$mic · ${PhoneAi.statusLine}")
            return
        }
        startTalk()
    }

    private fun onTalkIdle() {
        if (!_ui.value.talking || asking || _ui.value.recognizing) return
        scheduleUnmute()
    }

    private fun scheduleUnmute() {
        main.removeCallbacks(unmuteAfterTts)
        main.postDelayed(unmuteAfterTts, 700)
    }

    private fun onAsrPartial(text: String) {
        val now = SystemClock.uptimeMillis()
        _ui.update { it.copy(asrPartial = text, status = "听到：$text") }
        if (!_ui.value.talking || asking || _ui.value.recognizing) return
        if (now - lastHudAt < 80) return
        lastHudAt = now
        val card = HudCard.listening(currentMatch(), text, navCard())
        _ui.update { it.copy(card = card, asrPartial = text) }
        CxrLinkHost.showCard(card)
    }

    private fun navCard(): HudCard? {
        val hint = _ui.value.navHint ?: return null
        if (session.activeSkill != ActiveSkill.NAV) return null
        return HudCard.fromNav(hint.storeName, hint.turn, hint.meters, hint.text, remaining = hint.remaining)
    }

    private fun currentHud(): HudCard {
        return navCard()
            ?: currentMatch()?.let { HudCard.fromMatch(it) }
            ?: HudCard.idle()
    }

    private fun sessionHint(question: String): String {
        val store = session.currentStore
        val candidates = session.candidates.joinToString("、") { it.shortName }.ifBlank { "无" }
        val skill = session.activeSkill.name.lowercase()
        val storeLine = if (store == null) "无" else "${store.shortName}（${store.id}）"
        return "【会话】技能=$skill 当前店=$storeLine 候选=$candidates\n用户：$question"
    }

    private fun runTool(name: String, args: JSONObject): String {
        return when (name) {
            "look_store" -> lookStoreForAi()
            "recommend" -> recommendForAi(args)
            "select_store" -> selectStoreForAi(args)
            "start_nav" -> startNavForAi(args)
            "stop_nav" -> stopNavForAi()
            else -> JSONObject().put("ok", false).put("error", "未知工具 $name").toString()
        }
    }

    private fun recommendForAi(args: JSONObject): String {
        if (session.activeSkill == ActiveSkill.NAV) {
            stopNavInternal(silent = true)
        }
        val query = args.optString("query").ifBlank { "附近好吃的" }
        val avoid = args.optBoolean("avoid_queue", false)
        val text = if (avoid) "$query 不要排队" else query
        val result = session.recommend(text)
        recommendedThisTurn = true
        val card = HudCard.fromRecommend(result)
        main.post {
            _ui.update {
                it.copy(
                    match = result,
                    card = card,
                    skill = ActiveSkill.BROWSE,
                    navHint = null,
                    status = result.tts,
                    lastQa = QaResult(query, "recommend", result.tts, result.tts),
                )
            }
            CxrLinkHost.showCard(card)
        }
        return storeFactsJson(result, null)
            .put("message", result.tts)
            .toString()
    }

    private fun selectStoreForAi(args: JSONObject): String {
        if (session.activeSkill == ActiveSkill.NAV) {
            stopNavInternal(silent = true)
        }
        val storeId = args.optString("store_id")
        val name = args.optString("name")
        val index = args.optInt("index", 0)
        val result = when {
            storeId.isNotBlank() -> session.select(storeId)
            index in 1..9 -> {
                val picked = session.candidates.getOrNull(index - 1)
                    ?: return JSONObject().put("ok", false).put("error", "没有第${index}家").toString()
                session.select(picked.id)
            }
            name.isNotBlank() -> {
                val fromCand = session.candidates.firstOrNull { store ->
                    store.shortName.contains(name) || store.name.contains(name)
                }
                val matched = fromCand
                    ?: StoreVision.matchStore(MockCatalog.sceneById(session.sceneId), name)
                    ?: return JSONObject().put("ok", false).put("error", "没找到$name").toString()
                session.select(matched.id)
            }
            else -> session.lastMatch
                ?: return JSONObject().put("ok", false).put("error", "还没有候选店").toString()
        }
        selectedThisTurn = true
        val card = HudCard.fromStore(result.store)
        main.post {
            _ui.update {
                it.copy(
                    match = result,
                    card = card,
                    skill = ActiveSkill.BROWSE,
                    navHint = null,
                    status = "已选${result.store.shortName}",
                    lastQa = QaResult("选定", "select", "已选${result.store.shortName}", "已选${result.store.shortName}"),
                )
            }
            CxrLinkHost.showCard(card)
        }
        return storeFactsJson(result, null)
            .put("message", "已选${result.store.shortName}")
            .toString()
    }

    private fun bindStoreFromSpeech(spoken: String) {
        val q = spoken.trim()
        if (q.isBlank()) return
        val scene = MockCatalog.sceneById(session.sceneId)
        val hit = scene.stores.firstOrNull { store ->
            q.contains(store.shortName) || q.contains(store.name) ||
                brandIn(q, store)
        } ?: return
        if (hit.id == session.currentStore?.id) return
        selectStoreForAi(JSONObject().put("store_id", hit.id))
    }

    private fun brandIn(q: String, store: Store): Boolean {
        val brands = listOf("海底捞", "喜茶", "巴奴", "西贝", "奈雪", "麦当劳", "春风十里", "屋头")
        return brands.any { brand -> q.contains(brand) && (store.shortName.contains(brand) || store.name.contains(brand)) }
    }

    private fun startNavForAi(args: JSONObject): String {
        val named = args.optString("name")
        val storeId = args.optString("store_id")
        val index = args.optInt("index", 0)
        if (storeId.isNotBlank() || named.isNotBlank() || index in 1..9) {
            val picked = JSONObject(selectStoreForAi(args))
            if (!picked.optBoolean("ok")) {
                return picked.toString()
            }
        } else {
            bindStoreFromSpeech(_ui.value.question)
        }
        val store = session.currentStore
        if (store == null) {
            return JSONObject()
                .put("ok", false)
                .put("error", "no_store")
                .put("message", "还没选定门店，禁止开导航")
                .toString()
        }
        if (recommendedThisTurn && !selectedThisTurn) {
            return JSONObject()
                .put("ok", false)
                .put("error", "no_store")
                .put("message", "先问去哪家，选定后才能导航")
                .toString()
        }
        val error = startNavInternal()
        if (error != null) {
            return JSONObject()
                .put("ok", false)
                .put("error", "nav_failed")
                .put("message", error)
                .toString()
        }
        return JSONObject()
            .put("ok", true)
            .put("store", store.name)
            .put("shortName", store.shortName)
            .put("message", "开始去${store.shortName}")
            .toString()
    }

    private fun stopNavForAi(): String {
        val wasNav = session.activeSkill == ActiveSkill.NAV
        stopNavInternal(silent = true)
        return JSONObject()
            .put("ok", true)
            .put("stopped", wasNav)
            .put("message", if (wasNav) "导航停了" else "当时没在导航")
            .toString()
    }

    private fun startNavInternal(): String? {
        val store0 = session.currentStore ?: return "还没选定门店"
        val app = getApplication<Application>()
        if (!PhoneGps.hasPermission(app)) {
            return "没有定位权限，请打开位置信息"
        }
        if (PhoneAi.amapKey.isBlank()) {
            return "没配高德步行密钥，在 ai.env 写 AMAP_WEB_KEY"
        }
        val origin = PhoneGps.awaitFix(app, 8_000) ?: return "还没有 GPS，请到开阔处再试"
        val store = StorePins.pin(store0, origin)
        session.updateStore(store)
        val dest = StorePins.destOf(store) ?: return "目的店没有坐标"
        val route = AmapWalking.fetch(origin, dest, store.shortName, PhoneAi.amapKey)
            ?: return "步行路线失败，请检查高德密钥和网络"
        if (!session.startNav()) return "还没选定门店"
        announcedArrive = false
        lastRerouteAt = 0
        WalkGuide.set(route)
        PhoneGps.onUpdate = { onGps(it) }
        try {
            NavLocationService.start(app)
        } catch (error: Exception) {
            WalkGuide.clear()
            PhoneGps.onUpdate = null
            session.stopNav()
            return "定位服务启动失败"
        }
        val hint = WalkGuide.update(origin) ?: NavHint(
            storeName = store.shortName,
            text = "开始走",
            remaining = route.distance,
        )
        val card = navHud(hint)
        main.post {
            _ui.update {
                it.copy(
                    skill = ActiveSkill.NAV,
                    navHint = hint,
                    card = card,
                    match = session.lastMatch,
                    status = "开始去${store.shortName}",
                )
            }
            CxrLinkHost.startNav(hint)
            CxrLinkHost.showCard(card)
        }
        return null
    }

    private fun stopNavInternal(silent: Boolean) {
        val wasNav = session.activeSkill == ActiveSkill.NAV
        session.stopNav()
        PhoneGps.onUpdate = null
        WalkGuide.clear()
        announcedArrive = false
        try {
            NavLocationService.stop(getApplication())
        } catch (_: Exception) {
        }
        if (wasNav) {
            CxrLinkHost.stopNav()
        }
        val card = currentMatch()?.let { HudCard.fromMatch(it) } ?: HudCard.idle()
        val skill = session.activeSkill
        main.post {
            _ui.update {
                it.copy(
                    skill = skill,
                    navHint = null,
                    card = card,
                    status = if (wasNav) "导航停了" else it.status,
                )
            }
            CxrLinkHost.showCard(card)
        }
        if (wasNav && !silent) {
            PhoneTts.speak("导航停了")
        }
    }

    private fun onGps(point: GeoPoint) {
        if (session.activeSkill != ActiveSkill.NAV) return
        viewModelScope.launch(Dispatchers.IO) {
            maybeReroute(point)
            val hint = WalkGuide.update(point) ?: return@launch
            main.post { publishNavHint(hint) }
        }
    }

    private fun maybeReroute(point: GeoPoint) {
        if (!WalkGuide.offRoute(point)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRerouteAt < 8_000) return
        val store = session.currentStore ?: return
        val dest = StorePins.destOf(store) ?: return
        lastRerouteAt = now
        val route = AmapWalking.fetch(point, dest, store.shortName, PhoneAi.amapKey) ?: return
        WalkGuide.set(route)
        Log.i(TAG, "nav reroute distance=${route.distance}")
    }

    private fun publishNavHint(hint: NavHint) {
        if (session.activeSkill != ActiveSkill.NAV) return
        val card = navHud(hint)
        _ui.update {
            it.copy(
                navHint = hint,
                card = card,
                skill = ActiveSkill.NAV,
                status = hint.text,
            )
        }
        CxrLinkHost.updateHint(hint)
        CxrLinkHost.showCard(card)
        if (hint.turn == "arrive" && !announcedArrive) {
            announcedArrive = true
            PhoneTts.speak("到了")
        }
    }

    private fun navHud(hint: NavHint): HudCard {
        return HudCard.fromNav(
            storeName = hint.storeName,
            turn = hint.turn,
            meters = hint.meters,
            text = hint.text,
            remaining = hint.remaining,
        )
    }

    private fun localAgent(text: String): AiReply {
        val q = text.trim()
        return when {
            isStopNavIntent(q) -> {
                val was = session.activeSkill == ActiveSkill.NAV
                stopNavInternal(silent = true)
                AiReply(if (was) "导航停了。" else "现在没在走。", "导航停了", false)
            }
            isRecommendIntent(q) -> {
                val raw = recommendForAi(JSONObject().put("query", q))
                val speak = JSONObject(raw).optString("message").ifBlank { session.lastMatch?.tts.orEmpty() }
                AiReply(speak, speak, false)
            }
            isSelectIntent(q) -> {
                val raw = selectStoreForAi(selectArgsFromSpeech(q))
                val obj = JSONObject(raw)
                if (obj.optBoolean("ok")) {
                    AiReply(obj.optString("message"), obj.optString("shortName"), false)
                } else {
                    AiReply(obj.optString("error").ifBlank { "去哪家？" }, "去哪家", false)
                }
            }
            isStartNavIntent(q) -> {
                if (session.currentStore == null) {
                    val raw = recommendForAi(JSONObject().put("query", q))
                    val speak = "先选一家。" + JSONObject(raw).optString("message")
                    AiReply(speak, "去哪家", false)
                } else {
                    val raw = startNavForAi(JSONObject())
                    AiReply(JSONObject(raw).optString("message"), "开始走", false)
                }
            }
            isLookIntent(q) -> {
                startCapture()
                AiReply("我看一下眼前这家店。", "看店", false)
            }
            else -> {
                val local = session.ask(q)
                AiReply(local.answer, local.answer, false)
            }
        }
    }

    private fun selectArgsFromSpeech(text: String): JSONObject {
        val args = JSONObject()
        when {
            text.contains("第一") || text.contains("壹") -> args.put("index", 1)
            text.contains("第二") -> args.put("index", 2)
            text.contains("第三") -> args.put("index", 3)
        }
        session.candidates.plus(session.currentStore).filterNotNull().forEach { store ->
            if (text.contains(store.shortName) || text.contains(store.name)) {
                args.put("store_id", store.id)
            }
        }
        return args
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
            return listOf("看店", "识别", "拍照", "拍一张", "看看这家", "店招", "这是哪家").any { text.contains(it) }
        }

        fun isRecommendIntent(text: String): Boolean {
            if (text.contains("招牌")) return false
            return listOf("附近", "有什么好吃", "推荐几家", "不要排队", "别排队", "想吃", "火锅", "烧烤").any { text.contains(it) }
        }

        fun isStartNavIntent(text: String): Boolean {
            val t = text.trim()
            if (listOf("带我去", "走吧", "开始导航", "开始走", "带路", "出发", "现在去").any { t.contains(it) }) {
                return true
            }
            if (t == "走") return true
            return t.contains("导航") && !t.contains("取消") && !t.contains("停止")
        }

        fun isStopNavIntent(text: String): Boolean {
            return listOf("取消导航", "停止导航", "到了", "停下", "不用走了").any { text.contains(it) } ||
                (text == "取消")
        }

        fun isSelectIntent(text: String): Boolean {
            return listOf("第一家", "第二家", "第三家", "去第一", "去第二", "就这家", "选这家").any { text.contains(it) }
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
