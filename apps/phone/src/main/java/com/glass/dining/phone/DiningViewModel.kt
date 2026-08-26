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
import com.glass.dining.phone.vision.VisionRouter
import com.glass.dining.shared.engine.DiningSession
import com.glass.dining.shared.engine.StoreVision
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.mock.MockCatalog
import com.glass.dining.shared.mock.MockCommerce
import com.glass.dining.shared.model.ActiveSkill
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.MenuItem
import com.glass.dining.shared.model.QaResult
import com.glass.dining.shared.model.Scene
import com.glass.dining.shared.model.Store
import com.glass.dining.shared.nav.NavHint
import com.glass.dining.shared.nav.NavProtocol
import com.glass.dining.shared.vision.VisionDecision
import com.glass.dining.shared.vision.VisionIntent
import com.glass.dining.shared.vision.VisionOutcome
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
    val rtcOn: Boolean = false,
    val rtcLine: String = "",
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
    @Volatile private var capturing: Boolean = false

    init {
        CxrLinkHost.onStatus = { line ->
            _ui.update { state ->
                if (state.talking) state.copy(status = line) else state.copy(status = line)
            }
        }
        CxrLinkHost.onAudioPcm = { pcm -> GlassAsr.feed(pcm) }
        GlassAsr.onEndpoint = { onAsrEndpoint() }
        GlassAsr.onUtterance = { text -> onHeard(text) }
        GlassAsr.onError = { message -> setStatus(message) }
        GlassAsr.onPartial = { if (it.isBlank()) showListeningCard() }
        PhoneTts.onIdle = { onTalkIdle() }
        CxrLinkHost.onHudOpened = { onHudOpened() }
        CxrLinkHost.onFrame = { CxrLinkHost.ackFrame() }
        CxrLinkHost.onRtc = { cmd, json -> onRtc(cmd, json) }
        PhoneRtc.onSignal = { cmd, json -> CxrLinkHost.sendRtc(cmd, json) }
        PhoneRtc.onStatus = { line ->
            _ui.update { it.copy(status = line, rtcLine = line) }
        }
        val model = PhoneAi.statusLine
        _ui.update { it.copy(status = "连上眼镜后会自动听。说想吃什么。$model") }
    }

    override fun onCleared() {
        main.removeCallbacks(unmuteAfterTts)
        stopTalk(speak = false)
        stopNavInternal(silent = true)
        CxrLinkHost.onAudioPcm = null
        GlassAsr.onPartial = null
        GlassAsr.onEndpoint = null
        GlassAsr.onUtterance = null
        GlassAsr.onError = null
        PhoneTts.onIdle = null
        CxrLinkHost.onHudOpened = null
        CxrLinkHost.onFrame = null
        CxrLinkHost.onRtc = null
        PhoneRtc.onSignal = null
        PhoneRtc.onStatus = null
        stopRtcInternal()
        super.onCleared()
    }

    fun setStatus(text: String) {
        _ui.update { it.copy(status = text) }
    }

    fun toggleRtc() {
        if (_ui.value.rtcOn) {
            stopRtcInternal()
            setStatus("已关视频流")
            return
        }
        if (!CxrLinkHost.linkReady()) {
            setStatus("还没连上眼镜，无法开视频流")
            return
        }
        _ui.update { it.copy(rtcOn = true, rtcLine = "正在开视频流…", status = "正在开视频流…") }
        viewModelScope.launch(Dispatchers.IO) {
            PhoneRtc.prepare(getApplication())
            main.post {
                CxrLinkHost.sendRtc(NavProtocol.CMD_RTC_START)
                main.removeCallbacks(rtcReadyTimeout)
                main.postDelayed(rtcReadyTimeout, 8_000)
            }
        }
    }

    private val rtcReadyTimeout = Runnable {
        if (_ui.value.rtcOn && !PhoneRtc.active) {
            setStatus("眼镜 8s 没 rtc.ready。需要重装眼镜 APK，且眼镜要能上网（同一 Wi-Fi 或热点）")
        }
    }

    private fun onRtc(cmd: String, json: String?) {
        when (cmd) {
            NavProtocol.CMD_RTC_READY -> {
                main.removeCallbacks(rtcReadyTimeout)
                PhoneRtc.startOffer()
            }
            NavProtocol.CMD_RTC_SDP -> PhoneRtc.onRemoteSdp(json)
            NavProtocol.CMD_RTC_ICE -> PhoneRtc.onRemoteIce(json)
            NavProtocol.CMD_RTC_STAT -> {
                val line = json?.take(80).orEmpty()
                if (line.isNotBlank()) {
                    _ui.update { it.copy(rtcLine = line) }
                }
            }
        }
    }

    private fun stopRtcInternal() {
        main.removeCallbacks(rtcReadyTimeout)
        PhoneRtc.stop()
        CxrLinkHost.sendRtc(NavProtocol.CMD_RTC_STOP)
        _ui.update { it.copy(rtcOn = false) }
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
                    status = "$mic · ${PhoneAi.statusLine} · ${NlsClient.statusLine}",
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

    private fun startCapture(intent: VisionIntent = VisionIntent.LOOK_STORE) {
        showShooting(intent)
        PhoneTts.speak(shootSpeak(intent))
        viewModelScope.launch {
            withContext(Dispatchers.IO) { processVision(intent) }
        }
    }

    private fun shootSpeak(intent: VisionIntent): String {
        return when (intent) {
            VisionIntent.CHECKOUT -> "对准付款码"
            VisionIntent.READ_MENU -> "我看一下菜单"
            VisionIntent.READ_SIGN -> "我看一下"
            else -> "正在拍照"
        }
    }

    private fun showShooting(intent: VisionIntent) {
        val shooting = HudCard.shooting(currentMatch(), _ui.value.card)
        _ui.update {
            it.copy(
                recognizing = true,
                status = shootSpeak(intent),
                card = if (session.activeSkill == ActiveSkill.NAV) it.card else shooting,
                lastQa = QaResult("视觉", intent.name.lowercase(), shootSpeak(intent), shootSpeak(intent)),
            )
        }
        if (session.activeSkill != ActiveSkill.NAV) {
            CxrLinkHost.showCard(shooting)
        }
    }

    private fun failCapture(reason: String): String {
        Log.w(TAG, "capture failed: $reason")
        asking = false
        val card = failCard("拍照失败")
        main.post {
            _ui.update {
                it.copy(
                    recognizing = false,
                    status = reason,
                    card = if (session.activeSkill == ActiveSkill.NAV) it.card else card,
                    lastQa = QaResult("视觉", "look", reason, reason),
                )
            }
            if (session.activeSkill != ActiveSkill.NAV) {
                CxrLinkHost.showCard(card)
            }
        }
        main.post { PhoneTts.speak(reason.take(24)) }
        return JSONObject().put("ok", false).put("error", reason).toString()
    }

    private fun processVision(intent: VisionIntent, spatial: Boolean = false): String {
        if (capturing) {
            return JSONObject().put("ok", false).put("error", "正在拍照").toString()
        }
        capturing = true
        try {
            if (_ui.value.rtcOn || PhoneRtc.active) {
                stopRtcInternal()
                Thread.sleep(800)
            }
            if (intent == VisionIntent.LOOK_STORE && session.activeSkill == ActiveSkill.NAV) {
                val current = session.lastMatch
                if (current != null) {
                    return storeFactsJson(current, null)
                        .put("note", "正在导航，没有再拍照，这是当前目的店")
                        .toString()
                }
            }
            main.post { showShooting(intent) }
            if (!CxrLinkHost.canTakePhoto()) {
                return failCapture("眼镜未连上，无法拍照。请先用乐奇连上这副眼镜。")
            }
            val (bytes, error) = captureSync()
            if (bytes == null) {
                return failCapture(error ?: "眼镜拍照失败")
            }
            val scene = MockCatalog.sceneById(_ui.value.sceneId)
            val (bitmap, outcome) = VisionRouter.analyze(bytes, intent, scene, spatial)
            main.post { _ui.update { it.copy(photo = bitmap, recognizing = false) } }
            if (outcome.needsRecapture) {
                return failCapture(outcome.reason)
            }
            return when (intent) {
                VisionIntent.CHECKOUT -> finishCheckoutScan(outcome)
                VisionIntent.READ_MENU -> finishMenu(bytes, outcome)
                VisionIntent.READ_SIGN -> finishSign(bytes, outcome)
                VisionIntent.LOOK_STORE -> finishLookStore(bytes, outcome)
            }
        } finally {
            capturing = false
        }
    }

    private fun finishLookStore(jpeg: ByteArray, outcome: VisionOutcome): String {
        val hint = outcome.matchedStoreName.orEmpty().ifBlank { outcome.ocrText }
        if (outcome.decision == VisionDecision.TEXT_ONLY && outcome.matchedStoreId != null) {
            val result = session.look(forceStoreId = outcome.matchedStoreId, visionHint = hint)
            selectedThisTurn = true
            publishMatch(result, "端侧OCR认店")
            return storeFactsJson(result, null)
                .put("vision_route", outcome.reason)
                .toString()
        }
        val vision = if (outcome.needsLlm && PhoneAi.ready) {
            PhoneAi.look(VisionRouter.bytesForLlm(jpeg, outcome), "look_store", outcome.ocrText)
        } else {
            null
        }
        val mergedHint = vision?.store.orEmpty().ifBlank { hint }.ifBlank { vision?.speak.orEmpty() }
        val scene = MockCatalog.sceneById(_ui.value.sceneId)
        val matched = StoreVision.matchStore(scene, mergedHint)
        return if (matched != null) {
            val result = session.look(visionHint = mergedHint)
            selectedThisTurn = true
            publishMatch(result, "视觉认店")
            storeFactsJson(result, vision)
                .put("vision_route", outcome.reason)
                .toString()
        } else if (vision != null && vision.fromLlm) {
            publishVision(vision)
            JSONObject()
                .put("ok", false)
                .put("vision", vision.speak)
                .put("store", vision.store)
                .put("vision_route", outcome.reason)
                .toString()
        } else if (outcome.ocrText.isNotBlank()) {
            val talk = "看到「${outcome.ocrText.take(16)}」，还对不上店。"
            publishVision(AiReply(talk, talk.take(16), false))
            JSONObject()
                .put("ok", false)
                .put("ocr", outcome.ocrText.take(80))
                .put("vision_route", outcome.reason)
                .toString()
        } else {
            val result = session.look(imageFingerprint = fingerprintOf(jpeg))
            publishMatch(result, "指纹回退")
            storeFactsJson(result, null).put("vision_route", "fingerprint_fallback").toString()
        }
    }

    private fun finishSign(jpeg: ByteArray, outcome: VisionOutcome): String {
        val text = if (outcome.needsLlm && PhoneAi.ready) {
            PhoneAi.look(VisionRouter.bytesForLlm(jpeg, outcome), "read_sign", outcome.ocrText).speak
        } else {
            outcome.ocrText.ifBlank { outcome.fastSpeak }.orEmpty()
        }
        val json = JSONObject()
            .put("ok", text.isNotBlank())
            .put("text", text.take(120))
            .put("vision_route", outcome.reason)
            .put("note", "路牌只作辅助，路线仍以 GPS 和高德为准")
        if (session.activeSkill != ActiveSkill.NAV && outcome.matchedStoreId != null) {
            val result = session.look(forceStoreId = outcome.matchedStoreId)
            selectedThisTurn = true
            publishMatch(result, "路牌认店")
            return storeFactsJson(result, null)
                .put("text", text.take(120))
                .put("vision_route", outcome.reason)
                .toString()
        }
        if (text.isBlank()) {
            json.put("ok", false).put("error", "路牌看不清")
        }
        return json.toString()
    }

    private fun finishMenu(jpeg: ByteArray, outcome: VisionOutcome): String {
        if (session.currentStore == null && outcome.matchedStoreId != null) {
            session.look(forceStoreId = outcome.matchedStoreId)
        }
        if (session.currentStore == null) {
            return JSONObject().put("ok", false).put("error", "还没选定门店，先认店或推荐").toString()
        }
        val items = if (outcome.decision == VisionDecision.TEXT_ONLY && outcome.ocrText.isNotBlank()) {
            parseMenuItems(outcome.ocrText).ifEmpty { MockCommerce.menuOf(session.currentStore!!.id) }
        } else if (outcome.needsLlm && PhoneAi.ready) {
            val vision = PhoneAi.look(VisionRouter.bytesForLlm(jpeg, outcome), "read_menu", outcome.ocrText)
            parseMenuItems(vision.speak + "\n" + outcome.ocrText).ifEmpty { MockCommerce.menuOf(session.currentStore!!.id) }
        } else {
            MockCommerce.menuOf(session.currentStore!!.id)
        }
        val menu = session.startMenu(items)
        val card = HudCard.fromMenu(session.currentStore?.shortName.orEmpty(), menu)
        publishSkillCard(card, ActiveSkill.MENU, "这是${session.currentStore?.shortName}的菜单", "read_menu")
        return JSONObject()
            .put("ok", true)
            .put("store", session.currentStore?.shortName)
            .put("vision_route", outcome.reason)
            .put("items", menu.joinToString("；") { "${it.name}¥${it.price}" })
            .toString()
    }

    private fun finishCheckoutScan(outcome: VisionOutcome): String {
        if (outcome.barcodeKind == "table") {
            val table = outcome.tableHint
            val storeId = outcome.merchantHint
            if (storeId.isNotBlank()) {
                val result = session.look(forceStoreId = storeId)
                selectedThisTurn = true
                publishMatch(result, "桌码认店")
            }
            return JSONObject()
                .put("ok", true)
                .put("kind", "table")
                .put("table", table)
                .put("store", session.currentStore?.shortName.orEmpty())
                .put("note", "这是桌码，不是付款码，不能当成核销成功")
                .put("vision_route", outcome.reason)
                .toString()
        }
        if (outcome.barcodeKind != "pay") {
            return JSONObject()
                .put("ok", false)
                .put("error", "没扫到付款码")
                .put("vision_route", outcome.reason)
                .toString()
        }
        val synthetic = when (outcome.barcodeType) {
            "weixin" -> "weixin://wxpay/bizpayurl?store=${outcome.merchantHint.ifBlank { session.currentStore?.id.orEmpty() }}"
            "alipay" -> "https://qr.alipay.com/${outcome.merchantHint.ifBlank { session.currentStore?.id.orEmpty() }}"
            else -> "mtpay://${outcome.merchantHint.ifBlank { session.currentStore?.id.orEmpty() }}"
        }
        val order = MockCommerce.payFromQr(
            payload = synthetic,
            fallbackStoreId = session.currentStore?.id ?: outcome.merchantHint,
        )?.copy(qrType = outcome.barcodeType ?: "pay")
            ?: return JSONObject().put("ok", false).put("error", "付款码无法对应门店").toString()
        if (session.currentStore == null && order.merchantId != "unknown_merchant") {
            session.look(forceStoreId = order.merchantId)
        }
        session.preparePay(order)
        val card = HudCard.fromPayConfirm(order.storeName, order.amount, order.qrType)
        publishSkillCard(card, ActiveSkill.PAY, "应付${order.amount}元，确认才付款", "checkout")
        return JSONObject()
            .put("ok", true)
            .put("kind", "pay")
            .put("qr_type", order.qrType)
            .put("merchant_id", order.merchantId)
            .put("store", order.storeName)
            .put("amount", order.amount)
            .put("need_confirm", true)
            .put("note", "mock 支付，未向支付机构下单")
            .put("vision_route", outcome.reason)
            .toString()
    }

    private fun parseMenuItems(text: String): List<MenuItem> {
        val price = Regex("""(.+?)[¥￥]\s*(\d+)|(.+?)\s+(\d+)\s*元""")
        return text.lineSequence().mapNotNull { line ->
            val match = price.find(line.trim()) ?: return@mapNotNull null
            val name = match.groupValues[1].ifBlank { match.groupValues[3] }.trim()
            val value = match.groupValues[2].ifBlank { match.groupValues[4] }.toIntOrNull() ?: return@mapNotNull null
            if (name.length < 2) null else MenuItem(name.take(12), value)
        }.take(8).toList()
    }

    private fun publishSkillCard(card: HudCard, skill: ActiveSkill, status: String, intent: String) {
        main.post {
            _ui.update {
                it.copy(
                    card = card,
                    skill = skill,
                    status = status,
                    recognizing = false,
                    match = session.lastMatch,
                    lastQa = QaResult(status, intent, status, status),
                )
            }
            CxrLinkHost.showCard(card)
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
        return processVision(VisionIntent.LOOK_STORE)
    }

    private fun readSignForAi(): String {
        return processVision(VisionIntent.READ_SIGN, spatial = isSpatial(_ui.value.question))
    }

    private fun readMenuForAi(): String {
        return processVision(VisionIntent.READ_MENU)
    }

    private fun checkoutForAi(args: JSONObject): String {
        if (args.optBoolean("confirm", false)) {
            val done = session.confirmPay()
                ?: return JSONObject().put("ok", false).put("error", "还没有待确认的付款").put("need_confirm", true).toString()
            val card = HudCard.fromPayResult(done.storeName, done.amount)
            publishSkillCard(card, session.activeSkill, "已付${done.amount}元，这是演示", "checkout")
            return JSONObject()
                .put("ok", true)
                .put("mock", true)
                .put("paid", true)
                .put("amount", done.amount)
                .put("store", done.storeName)
                .put("message", "演示已付${done.amount}元，不是真扣款")
                .toString()
        }
        return processVision(VisionIntent.CHECKOUT)
    }

    private fun listCouponsForAi(): String {
        if (session.currentStore == null) {
            return JSONObject().put("ok", false).put("error", "还没选定门店，先认店或推荐").toString()
        }
        val coupons = session.listCoupons()
        val card = HudCard.fromCoupons(session.currentStore?.shortName.orEmpty(), coupons)
        publishSkillCard(card, ActiveSkill.COUPON, card.wait.ifBlank { "这店暂无券" }, "list_coupons")
        return JSONObject()
            .put("ok", true)
            .put("store", session.currentStore?.shortName)
            .put("coupons", coupons.joinToString("；") { "${it.id}:${it.title}¥${it.price}" })
            .put("need_confirm", true)
            .put("note", "mock 券列表，确认后才核销")
            .toString()
    }

    private fun redeemCouponForAi(args: JSONObject): String {
        if (session.currentStore == null) {
            return JSONObject().put("ok", false).put("error", "还没选定门店").toString()
        }
        if (session.lastCoupons.isEmpty()) {
            session.listCoupons()
        }
        val couponId = args.optString("coupon_id")
        val title = args.optString("title")
        val picked = when {
            couponId.isNotBlank() -> session.prepareCoupon(couponId)
            title.isNotBlank() -> session.lastCoupons.firstOrNull { it.title.contains(title) }?.also {
                session.prepareCoupon(it.id)
            }
            else -> session.pendingCoupon ?: session.lastCoupons.firstOrNull()?.also { session.prepareCoupon(it.id) }
        }
        if (picked == null) {
            return JSONObject().put("ok", false).put("error", "这店没有可核销的券").toString()
        }
        if (!args.optBoolean("confirm", false)) {
            val card = HudCard.fromCoupons(session.currentStore?.shortName.orEmpty(), listOf(picked))
            publishSkillCard(card, ActiveSkill.COUPON, "确认后才核销${picked.title}", "redeem_coupon")
            return JSONObject()
                .put("ok", true)
                .put("need_confirm", true)
                .put("coupon_id", picked.id)
                .put("title", picked.title)
                .put("price", picked.price)
                .put("message", "请用户确认是否核销${picked.title}")
                .toString()
        }
        val receipt = session.redeemCoupon()
        val card = HudCard.fromRedeem(receipt.title, receipt.ok)
        publishSkillCard(card, session.activeSkill, receipt.message, "redeem_coupon")
        return JSONObject()
            .put("ok", receipt.ok)
            .put("mock", true)
            .put("coupon_id", receipt.couponId)
            .put("title", receipt.title)
            .put("sequence_id", receipt.sequenceId)
            .put("message", receipt.message + "。这是演示回执，没有调美团验券接口")
            .toString()
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
            session.activeSkill == ActiveSkill.MENU ||
            session.activeSkill == ActiveSkill.COUPON ||
            session.activeSkill == ActiveSkill.PAY ||
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
            setStatus("$mic · ${PhoneAi.statusLine} · ${NlsClient.statusLine}")
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

    private fun onAsrEndpoint() {
        if (!_ui.value.talking || asking || _ui.value.recognizing) return
        val thinking = HudCard.thinking(currentMatch(), navCard = navCard())
        _ui.update { it.copy(card = thinking, asrPartial = "", status = "思考中") }
        CxrLinkHost.showCard(thinking)
    }

    private fun navCard(): HudCard? {
        val hint = _ui.value.navHint ?: return null
        if (session.activeSkill != ActiveSkill.NAV) return null
        return HudCard.fromNav(hint.storeName, hint.turn, hint.meters, hint.text, remaining = hint.remaining)
    }

    private fun currentHud(): HudCard {
        return navCard()
            ?: session.pendingPay?.let { HudCard.fromPayConfirm(it.storeName, it.amount, it.qrType) }
            ?: session.pendingCoupon?.let { HudCard.fromCoupons(session.currentStore?.shortName.orEmpty(), listOf(it)) }
            ?: if (session.activeSkill == ActiveSkill.COUPON) {
                HudCard.fromCoupons(session.currentStore?.shortName.orEmpty(), session.lastCoupons)
            } else {
                null
            }
            ?: if (session.activeSkill == ActiveSkill.MENU) {
                HudCard.fromMenu(session.currentStore?.shortName.orEmpty(), session.lastMenu)
            } else {
                null
            }
            ?: currentMatch()?.let { HudCard.fromMatch(it) }
            ?: HudCard.idle()
    }

    private fun sessionHint(question: String): String {
        val store = session.currentStore
        val candidates = session.candidates.joinToString("、") { it.shortName }.ifBlank { "无" }
        val skill = session.activeSkill.name.lowercase()
        val storeLine = if (store == null) "无" else "${store.shortName}（${store.id}）"
        val menu = session.lastMenu.take(3).joinToString("、") { it.name }.ifBlank { "无" }
        val pay = session.pendingPay?.let { "待付${it.amount}元给${it.storeName}" } ?: "无"
        val coupon = session.pendingCoupon?.title ?: session.lastCoupons.firstOrNull()?.title ?: "无"
        return "【会话】技能=$skill 当前店=$storeLine 候选=$candidates 菜单=$menu 待支付=$pay 待核销=$coupon\n用户：$question"
    }

    private fun runTool(name: String, args: JSONObject): String {
        return when (name) {
            "look_store" -> lookStoreForAi()
            "read_sign" -> readSignForAi()
            "read_menu" -> readMenuForAi()
            "recommend" -> recommendForAi(args)
            "select_store" -> selectStoreForAi(args)
            "start_nav" -> startNavForAi(args)
            "stop_nav" -> stopNavForAi()
            "list_coupons" -> listCouponsForAi()
            "redeem_coupon" -> redeemCouponForAi(args)
            "checkout" -> checkoutForAi(args)
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
                startCapture(VisionIntent.LOOK_STORE)
                AiReply("我看一下眼前这家店。", "看店", false)
            }
            isSignIntent(q) -> {
                startCapture(VisionIntent.READ_SIGN)
                AiReply("我看一下路牌。", "路牌", false)
            }
            isMenuIntent(q) -> {
                if (session.currentStore == null) {
                    val raw = recommendForAi(JSONObject().put("query", q))
                    AiReply("先选一家再看菜单。" + JSONObject(raw).optString("message"), "去哪家", false)
                } else {
                    startCapture(VisionIntent.READ_MENU)
                    AiReply("我看一下菜单。", "菜单", false)
                }
            }
            isCouponIntent(q) -> {
                val raw = if (isConfirmIntent(q) && session.pendingCoupon != null) {
                    redeemCouponForAi(JSONObject().put("confirm", true))
                } else if (isConfirmIntent(q)) {
                    redeemCouponForAi(JSONObject().put("confirm", true))
                } else {
                    listCouponsForAi()
                }
                val speak = JSONObject(raw).optString("message").ifBlank { JSONObject(raw).optString("error") }
                AiReply(speak, speak, false)
            }
            isCheckoutIntent(q) -> {
                val raw = if (isConfirmIntent(q)) {
                    checkoutForAi(JSONObject().put("confirm", true))
                } else {
                    checkoutForAi(JSONObject())
                }
                val speak = JSONObject(raw).optString("message").ifBlank { JSONObject(raw).optString("error").ifBlank { "对准付款码" } }
                AiReply(speak, speak, false)
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

        fun isSignIntent(text: String): Boolean {
            return listOf("路牌", "入口", "门口", "这是不是店").any { text.contains(it) }
        }

        fun isMenuIntent(text: String): Boolean {
            return listOf("菜单", "菜谱", "点什么", "有什么菜").any { text.contains(it) }
        }

        fun isCheckoutIntent(text: String): Boolean {
            return listOf("买单", "结账", "付款", "扫码付", "付钱").any { text.contains(it) }
        }

        fun isCouponIntent(text: String): Boolean {
            return listOf("美团券", "用券", "核销", "验券").any { text.contains(it) }
        }

        fun isConfirmIntent(text: String): Boolean {
            return listOf("确认", "确定", "好的付", "付钱吧", "核销吧").any { text.contains(it) }
        }

        fun isSpatial(text: String): Boolean {
            return listOf("左边", "右边", "前面", "那个", "哪边", "入口").any { text.contains(it) }
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
