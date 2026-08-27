package com.glass.dining.phone

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glass.dining.phone.agent.CommerceToolProvider
import com.glass.dining.phone.agent.DiningToolProvider
import com.glass.dining.phone.agent.EnvironmentWatcher
import com.glass.dining.phone.agent.NavigationToolProvider
import com.glass.dining.phone.agent.PhoneAgent
import com.glass.dining.phone.agent.PhoneWorld
import com.glass.dining.phone.agent.ToolRegistry
import com.glass.dining.phone.agent.VisionToolProvider
import com.glass.dining.phone.link.LinkUiState
import com.glass.dining.phone.link.VisionLinkCoordinator
import com.glass.dining.phone.indoor.IndoorNavCoordinator
import com.glass.dining.phone.nav.AmapWalking
import com.glass.dining.phone.nav.GeoPoint
import com.glass.dining.phone.nav.NavLocationService
import com.glass.dining.phone.nav.PhoneGps
import com.glass.dining.phone.nav.StorePins
import com.glass.dining.phone.nav.WalkGuide
import com.glass.dining.shared.agent.AgentContextAssembler
import com.glass.dining.shared.agent.EnvironmentMerge
import com.glass.dining.shared.agent.EnvironmentStore
import com.glass.dining.shared.agent.PlaceRef
import com.glass.dining.shared.agent.PlaceResolver
import com.glass.dining.shared.agent.SceneObservation
import com.glass.dining.shared.agent.ScenePerception
import com.glass.dining.shared.agent.WorldContext
import com.glass.dining.shared.nav.LandmarkPlanner
import com.glass.dining.shared.nav.LandmarkSignage
import com.glass.dining.shared.nav.LandmarkStage
import com.glass.dining.shared.nav.LandmarkWorld
import com.glass.dining.phone.vision.LocalVision
import com.glass.dining.phone.vision.SceneVision
import com.glass.dining.phone.vision.StreamFrame
import com.glass.dining.phone.vision.StreamVision
import com.glass.dining.phone.vision.VisionRouter
import com.glass.dining.shared.catalog.MemoryStoreCatalog
import com.glass.dining.shared.catalog.StoreGeo
import com.glass.dining.shared.engine.DiningSession
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.mock.MockCommerce
import com.glass.dining.shared.model.ActiveSkill
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.MenuItem
import com.glass.dining.shared.model.QaResult
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
import java.util.concurrent.atomic.AtomicReference

data class PhoneUiState(
    val status: String = "连上眼镜后会自动听。点按钮可暂停。",
    val storeCount: Int = 0,
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
    val agentContext: String = "",
    val agentDebug: String = "",
    val link: LinkUiState = LinkUiState(),
)

class DiningViewModel(app: Application) : AndroidViewModel(app) {
    private val session = DiningSession()
    private val _ui = MutableStateFlow(PhoneUiState())
    val ui: StateFlow<PhoneUiState> = _ui.asStateFlow()

    var onStartVoice: (() -> Unit)? = null
    var onNeedGlassAuth: (() -> Unit)? = null

    private val talk = TalkTurn()
    private val main = Handler(Looper.getMainLooper())
    private val unmuteAfterTts = Runnable {
        if (!_ui.value.talking || talk.asking || _ui.value.recognizing || PhoneTts.speaking) return@Runnable
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
    private val indoor = IndoorNavCoordinator()
    @Volatile private var lastLandmarkStage: String = ""
    @Volatile private var spokenFloor: String = ""
    @Volatile private var capturing: Boolean = false
    private val visionWait = AtomicReference<CountDownLatch?>(null)
    @Volatile private var latestObservation: SceneObservation? = null
    @Volatile private var cachedPlaces: List<PlaceRef> = emptyList()
    @Volatile private var visionBindStore: Boolean = true
    @Volatile private var autoStoreShownId: String? = null
    private val runtime = Runtime()
    private val tools = ToolRegistry().also { registry ->
        DiningToolProvider(runtime).register(registry)
        VisionToolProvider(runtime).register(registry)
        NavigationToolProvider(runtime).register(registry)
        CommerceToolProvider(runtime).register(registry)
    }
    private val link = VisionLinkCoordinator(
        app = app,
        main = main,
        onSnapshot = { snap ->
            _ui.update {
                it.copy(
                    link = snap,
                    rtcOn = snap.open,
                    rtcLine = snap.summary,
                )
            }
        },
        onStreaming = { visionWait.get()?.countDown() },
        onFailed = { visionWait.get()?.countDown() },
    )

    private val catalogObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            reloadCatalog()
        }
    }

    init {
        CxrLinkHost.onStatus = { line ->
            link.refreshLayers()
            _ui.update { it.copy(status = line) }
        }
        CxrLinkHost.onAudioPcm = { pcm -> GlassAsr.feed(pcm) }
        GlassAsr.onEndpoint = { onAsrEndpoint() }
        GlassAsr.onUtterance = { text -> onHeard(text) }
        GlassAsr.onError = { message -> setStatus(message) }
        GlassAsr.onPartial = { if (it.isBlank()) showListeningCard() }
        PhoneTts.onIdle = { onTalkIdle() }
        CxrLinkHost.onHudOpened = { onHudOpened() }
        CxrLinkHost.onGlassReady = { link.onGlassReady() }
        CxrLinkHost.onFrame = { CxrLinkHost.ackFrame() }
        CxrLinkHost.onRtc = { cmd, json -> link.onRtc(cmd, json) }
        CxrLinkHost.onPose = { pose ->
            EnvironmentWatcher.onPose(pose)
            indoor.onPose(pose)
        }
        PhoneRtc.onPose = { pose ->
            EnvironmentWatcher.onPose(pose)
            indoor.onPose(pose)
        }
        EnvironmentWatcher.onSemantic = { episode, look, raw -> indoor.onSemantic(episode, look, raw) }
        EnvironmentWatcher.onCommitted = { main.post { publishAgentContext() } }
        PhoneRtc.onSignal = { cmd, json -> CxrLinkHost.sendRtc(cmd, json) }
        PhoneRtc.onStatus = { line ->
            link.onRtcStatus(line)
            if (PhoneRtc.hasFreshFrame()) {
                visionWait.get()?.countDown()
            }
        }
        PhoneP2p.onStatus = { line ->
            link.onRtcStatus(line)
        }
        StreamVision.onSample = { frame ->
            EnvironmentWatcher.ingest(frame)
            publishAgentContext()
            if (session.activeSkill == ActiveSkill.NAV) {
                maybeLandmarkNav(frame)
            } else {
                maybeObserveScene(frame)
            }
        }
        val model = PhoneAi.statusLine
        _ui.update { it.copy(status = "连上眼镜后会自动听。$model") }
        try {
            getApplication<Application>().contentResolver.registerContentObserver(
                PhoneCatalog.uri(),
                true,
                catalogObserver,
            )
        } catch (_: Exception) {
        }
        reloadCatalog()
    }

    override fun onCleared() {
        try {
            getApplication<Application>().contentResolver.unregisterContentObserver(catalogObserver)
        } catch (_: Exception) {
        }
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
        CxrLinkHost.onGlassReady = null
        CxrLinkHost.onFrame = null
        CxrLinkHost.onRtc = null
        CxrLinkHost.onPose = null
        PhoneRtc.onPose = null
        EnvironmentWatcher.onSemantic = null
        EnvironmentWatcher.onCommitted = null
        PhoneRtc.onSignal = null
        PhoneRtc.onStatus = null
        PhoneP2p.onStatus = null
        StreamVision.onSample = null
        link.stop()
        CxrLinkHost.stopGlassApp()
        super.onCleared()
    }

    fun setStatus(text: String) {
        _ui.update { it.copy(status = text) }
    }

    fun toggleRtc() {
        if (link.open) {
            link.stop()
            return
        }
        startVisionLink()
    }

    fun startVisionLink() {
        if (link.open) return
        if (!CxrLinkHost.linkReady()) {
            setStatus("还没连上眼镜，无法开视频流")
            return
        }
        link.begin()
    }

    fun stopVisionLink() {
        if (link.open) link.stop()
    }

    private fun kickOpenVisionStream() {
        if (PhoneRtc.hasFreshFrame() || link.open) return
        if (!CxrLinkHost.linkReady()) return
        main.post {
            if (link.open || PhoneRtc.hasFreshFrame()) return@post
            link.begin()
        }
    }

    private fun maybeLandmarkNav(frame: StreamFrame) {
        if (session.activeSkill != ActiveSkill.NAV) return
        val store = session.currentStore ?: return
        if (!frame.extract.quality.ok && indoor.landmarks.stage == LandmarkStage.OUTDOOR) return
        val gpsHint = _ui.value.navHint
        val remaining = gpsHint?.remaining ?: WalkGuide.current()?.distance ?: 0
        val gpsArrive = gpsHint?.turn == "arrive"
        val hasFix = PhoneGps.last != null
        val world = LandmarkWorld(
            gpsUsable = hasFix &&
                !LandmarkPlanner.gpsLooksIndoor(PhoneGps.lastAccuracyM, hasFix) &&
                PhoneAi.amapKey.isNotBlank(),
            gpsAccuracyM = PhoneGps.lastAccuracyM,
            spokenFloor = EnvironmentStore.snapshot().usableFloor.ifBlank { spokenFloor },
        )
        val visual = indoor.observe(
            extract = frame.extract,
            goal = LandmarkPlanner.goalOf(store),
            gpsRemaining = remaining,
            gpsArrive = gpsArrive,
            world = world,
        )
        if (visual == null) {
            if (indoor.landmarks.stage == LandmarkStage.OUTDOOR) {
                viewModelScope.launch(Dispatchers.IO) { ensureOutdoorRoute() }
            }
            return
        }
        val hint = visual
        main.post {
            publishNavHint(hint)
            val stage = indoor.landmarks.stage.name
            if (stage != lastLandmarkStage) {
                lastLandmarkStage = stage
                Log.i(TAG, "indoor stage=$stage mode=${hint.mode} nodes=${indoor.liveMap().nodes.size} text=${hint.text}")
                if (hint.turn != "arrive" && hint.text.isNotBlank()) {
                    PhoneTts.speak(hint.text)
                }
            }
        }
    }

    private fun ensureOutdoorRoute() {
        if (session.activeSkill != ActiveSkill.NAV) return
        if (indoor.landmarks.stage != LandmarkStage.OUTDOOR) return
        if (WalkGuide.current() != null) return
        val origin = PhoneGps.last ?: return
        val store = session.currentStore ?: return
        val dest = StorePins.destOf(store) ?: return
        if (PhoneAi.amapKey.isBlank()) return
        val route = AmapWalking.fetch(origin, dest, store.shortName, PhoneAi.amapKey) ?: return
        WalkGuide.set(route)
        val hint = WalkGuide.update(origin) ?: return
        Log.i(TAG, "nav outdoor route distance=${route.distance}")
        main.post { publishNavHint(hint) }
    }

    private fun maybeObserveScene(frame: StreamFrame) {
        if (talk.asking || capturing) return
        when (session.activeSkill) {
            ActiveSkill.NAV, ActiveSkill.MENU, ActiveSkill.COUPON, ActiveSkill.PAY -> return
            else -> Unit
        }
        if (session.pendingPay != null || session.pendingCoupon != null) return
        if (!frame.extract.quality.ok) return
        val width = frame.extract.width
        val height = frame.extract.height
        if (width <= 0 || height <= 0) return
        val band = frame.bitmap?.let { LocalVision.signBand(it) } ?: 0f
        val next = ScenePerception.classify(width, height, frame.extract.ocr, band, latestObservation)
        val obs = ScenePerception.stabilize(latestObservation, next)
        latestObservation = obs
        val label = ScenePerception.silentLabel(obs)
        if (label.isBlank()) {
            if (_ui.value.card.skill == "observe" && !talk.asking) {
                val card = if (_ui.value.talking) HudCard.listening() else HudCard.idle()
                main.post {
                    _ui.update { it.copy(card = card, status = "连上眼镜后会自动听") }
                    CxrLinkHost.showCard(card)
                }
            }
            return
        }
        if (_ui.value.talking || PhoneTts.speaking) return
        val card = HudCard.observe(label)
        if (_ui.value.card.speech == card.speech && _ui.value.card.skill == "observe") return
        main.post {
            _ui.update { it.copy(card = card, status = label) }
            CxrLinkHost.showCard(card)
        }
        Log.i(TAG, "silent hud $label scene=${obs.scene} conf=${"%.2f".format(obs.confidence)} stab=${obs.stability}")
    }

    private fun rememberVisionStore(id: String) {
        autoStoreShownId = id
    }

    private fun rememberSpokenStore() {
        autoStoreShownId = session.currentStore?.id
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

    fun reloadCatalog() {
        val app = getApplication<Application>()
        val origin = PhoneGps.last
        val stores = StoreGeo.applyDistances(PhoneCatalog.load(app), origin?.lat, origin?.lng)
        session.replaceCatalog(MemoryStoreCatalog(stores))
        val line = if (stores.isEmpty()) {
            "还没有门店。请先用「门店录入」在店门口记录经纬度。"
        } else {
            "已加载 ${stores.size} 家门店。"
        }
        _ui.update { it.copy(storeCount = stores.size, status = line) }
    }

    fun look(forceStoreId: String? = null) {
        if (!forceStoreId.isNullOrBlank()) {
            stopNavInternal(silent = true)
            val result = session.look(forceStoreId)
            if (result == null) {
                setStatus("目录里没有这家店")
            } else {
                rememberSpokenStore()
                publishMatch(result, "已强制命中")
            }
            return
        }
        if (session.activeSkill == ActiveSkill.NAV) {
            stopNavInternal(silent = true)
        }
        startCapture()
    }

    fun next() {
        stopNavInternal(silent = true)
        val result = session.next()
        if (result == null) {
            setStatus("还没有门店，请先用门店 App 录入")
        } else {
            rememberSpokenStore()
            publishMatch(result, "已切换下一家")
        }
    }

    fun onHeard(spoken: String) {
        val text = spoken.trim()
        if (text.isBlank()) return
        ask(text)
    }

    fun ask(question: String) {
        val text = question.trim().ifBlank { return }
        val seq = talk.begin()
        PhoneTts.stop()
        main.removeCallbacks(unmuteAfterTts)
        GlassAsr.muted = false
        recommendedThisTurn = false
        selectedThisTurn = false
        rememberSpokenFloor(text)
        spokenChars = 0
        lastHudAt = 0
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
        val world = worldContext()
        publishAgentContext()
        viewModelScope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    PhoneAgent.ask(
                        text,
                        world,
                        tools,
                        onDelta = { partial ->
                            main.post {
                                if (talk.isCurrent(seq)) onStreamDelta(text, partial, done = false)
                            }
                        },
                        cancel = { !talk.isCurrent(seq) },
                    )
                }
                if (talk.isCurrent(seq)) {
                    onStreamDelta(text, reply.speak, done = true)
                }
            } finally {
                talk.finishIfCurrent(seq)
                if (talk.isCurrent(seq) && _ui.value.talking && !PhoneTts.speaking) {
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
        return session.catalog.stores()
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
            kickOpenVisionStream()
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
        talk.cancel()
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
            VisionIntent.SCAN_COUPON -> "对准券码"
            VisionIntent.READ_MENU -> "我看一下菜单"
            VisionIntent.READ_SIGN -> "我看一下"
            else -> "我看一下"
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
        talk.finishAsking()
        val card = failCard("看画面失败")
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

    private fun processVision(intent: VisionIntent, spatial: Boolean = false, bindStore: Boolean = true): String {
        if (capturing) {
            return JSONObject().put("ok", false).put("error", "正在看眼前画面").toString()
        }
        capturing = true
        visionBindStore = bindStore
        try {
            main.post { showShooting(intent) }
            val streamError = ensureStreamForVision()
            if (streamError != null) {
                return failCapture(streamError)
            }
            val stores = session.catalog.stores()
            val observe = SceneVision.observe(intent, stores, spatial, "stream") {
                grabVisionJpeg()
            }
            main.post { _ui.update { it.copy(photo = observe.bitmap, recognizing = false) } }
            if (observe.jpeg.isEmpty() && observe.outcome.needsRecapture) {
                return failCapture(observe.outcome.reason)
            }
            if (observe.outcome.needsRecapture) {
                return failCapture(observe.outcome.reason)
            }
            val jpeg = observe.jpeg
            val outcome = observe.outcome
            Log.i(TAG, "vision source=stream intent=$intent decision=${outcome.decision} route=${outcome.reason}")
            return when (intent) {
                VisionIntent.CHECKOUT -> finishCheckoutScan(outcome, "stream")
                VisionIntent.SCAN_COUPON -> finishScanCoupon(jpeg, outcome, "stream")
                VisionIntent.READ_MENU -> finishMenu(jpeg, outcome, "stream")
                VisionIntent.READ_SIGN -> finishSign(jpeg, outcome, "stream")
                VisionIntent.LOOK_STORE -> finishLookStore(jpeg, outcome, "stream")
            }
        } finally {
            capturing = false
        }
    }

    private fun ensureStreamForVision(): String? {
        if (PhoneRtc.hasFreshFrame()) return null
        if (!CxrLinkHost.linkReady()) {
            return "眼镜未连上，无法看画面。请先用乐奇连上这副眼镜。"
        }
        val latch = CountDownLatch(1)
        visionWait.set(latch)
        val t0 = System.currentTimeMillis()
        main.post {
            if (PhoneRtc.hasFreshFrame()) {
                latch.countDown()
                return@post
            }
            if (!link.open) {
                link.begin()
            }
        }
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            if (PhoneRtc.hasFreshFrame()) {
                visionWait.set(null)
                return null
            }
            val snap = link.snapshot
            if (snap.lastError.isNotBlank() && !snap.open && snap.startedAtMs >= t0) {
                visionWait.set(null)
                return snap.lastError
            }
            latch.await(200, TimeUnit.MILLISECONDS)
        }
        visionWait.set(null)
        return link.lastError.ifBlank { "Direct 没出画，没法抽帧" }
    }

    private fun grabVisionJpeg(): Pair<ByteArray?, String?> {
        val cached = StreamVision.latestJpeg()
        if (cached != null) {
            Log.i(TAG, "vision grab cache bytes=${cached.size}")
            return cached to null
        }
        val jpeg = PhoneRtc.grabJpeg(2_500)
        Log.i(TAG, "vision grab live bytes=${jpeg?.size ?: 0}")
        return jpeg to if (jpeg == null) "视频流还没出画，请对准再试" else null
    }

    private fun finishLookStore(jpeg: ByteArray, outcome: VisionOutcome, source: String): String {
        val hint = outcome.matchedStoreName.orEmpty().ifBlank { outcome.ocrText }
        if (visionBindStore && outcome.decision == VisionDecision.TEXT_ONLY && outcome.matchedStoreId != null) {
            val result = session.look(forceStoreId = outcome.matchedStoreId, visionHint = hint)
            if (result != null) {
                selectedThisTurn = true
                rememberVisionStore(result.store.id)
                publishMatch(result, "端侧OCR认店")
                return DiningToolProvider.facts(result)
                    .put("matched", true)
                    .put("is_store_fact", true)
                    .put("vision_route", outcome.reason)
                    .put("vision_source", source)
                    .toString()
            }
        }
        val vision = if (outcome.needsLlm && PhoneAi.ready) {
            PhoneAi.look(VisionRouter.bytesForLlm(jpeg, outcome), "look_store", outcome.ocrText)
        } else {
            null
        }
        val seen = vision?.store.orEmpty().ifBlank { hint }.ifBlank { outcome.ocrText }.trim()
        val talk = when {
            vision != null && vision.fromLlm && vision.speak.isNotBlank() -> vision.speak
            seen.isNotBlank() -> "画面上像是「${seen.take(24)}」。"
            else -> "这帧没读出店名。"
        }
        return JSONObject()
            .put("ok", seen.isNotBlank() || (vision?.fromLlm == true))
            .put("matched", false)
            .put("is_store_fact", false)
            .put("store", seen)
            .put("vision", talk)
            .put("message", talk)
            .put("ocr", outcome.ocrText.take(80))
            .put("confidence", (vision?.confidence ?: 0f).toDouble())
            .put("vision_route", outcome.reason)
            .put("vision_source", source)
            .put("hint", "这是观察，不是已确认门店")
            .toString()
    }

    private fun finishSign(jpeg: ByteArray, outcome: VisionOutcome, source: String): String {
        val text = if (outcome.needsLlm && PhoneAi.ready) {
            PhoneAi.look(VisionRouter.bytesForLlm(jpeg, outcome), "read_sign", outcome.ocrText).speak
        } else {
            outcome.ocrText.ifBlank { outcome.fastSpeak }.orEmpty()
        }
        val json = JSONObject()
            .put("ok", text.isNotBlank())
            .put("text", text.take(120))
            .put("vision_route", outcome.reason)
            .put("vision_source", source)
            .put("note", "路牌只作辅助，路线仍以 GPS 和高德为准")
        if (text.isBlank()) {
            json.put("ok", false).put("error", "路牌看不清")
        }
        return json.toString()
    }

    private fun finishMenu(jpeg: ByteArray, outcome: VisionOutcome, source: String): String {
        if (session.currentStore == null) {
            return JSONObject().put("ok", false).put("error", "还没确认门店，不能当事实读菜单").toString()
        }
        val items = if (outcome.decision == VisionDecision.TEXT_ONLY && outcome.ocrText.isNotBlank()) {
            parseMenuItems(outcome.ocrText).ifEmpty { MockCommerce.menuOf(session.currentStore) }
        } else if (outcome.needsLlm && PhoneAi.ready) {
            val vision = PhoneAi.look(VisionRouter.bytesForLlm(jpeg, outcome), "read_menu", outcome.ocrText)
            parseMenuItems(vision.speak + "\n" + outcome.ocrText).ifEmpty { MockCommerce.menuOf(session.currentStore) }
        } else {
            MockCommerce.menuOf(session.currentStore)
        }
        val menu = session.startMenu(items)
        val card = HudCard.fromMenu(session.currentStore?.shortName.orEmpty(), menu)
        publishSkillCard(card, ActiveSkill.MENU, "这是${session.currentStore?.shortName}的菜单", "read_menu")
        return JSONObject()
            .put("ok", true)
            .put("store", session.currentStore?.shortName)
            .put("vision_route", outcome.reason)
            .put("vision_source", source)
            .put("items", menu.joinToString("；") { "${it.name}¥${it.price}" })
            .toString()
    }

    private fun finishCheckoutScan(outcome: VisionOutcome, source: String): String {
        if (outcome.barcodeKind == "table") {
            val table = outcome.tableHint
            val storeId = outcome.merchantHint
            if (storeId.isNotBlank()) {
                val result = session.look(forceStoreId = storeId)
                if (result != null) {
                    selectedThisTurn = true
                    publishMatch(result, "桌码认店")
                }
            }
            return JSONObject()
                .put("ok", true)
                .put("kind", "table")
                .put("table", table)
                .put("store", session.currentStore?.shortName.orEmpty())
                .put("note", "这是桌码，不是付款码，不能当成核销成功")
                .put("vision_route", outcome.reason)
                .put("vision_source", source)
                .toString()
        }
        if (outcome.barcodeKind != "pay") {
            return JSONObject()
                .put("ok", false)
                .put("error", "没扫到付款码")
                .put("vision_route", outcome.reason)
                .put("vision_source", source)
                .toString()
        }
        val synthetic = when (outcome.barcodeType) {
            "weixin" -> "weixin://wxpay/bizpayurl?store=${outcome.merchantHint.ifBlank { session.currentStore?.id.orEmpty() }}"
            "alipay" -> "https://qr.alipay.com/${outcome.merchantHint.ifBlank { session.currentStore?.id.orEmpty() }}"
            else -> "mtpay://${outcome.merchantHint.ifBlank { session.currentStore?.id.orEmpty() }}"
        }
        val order = MockCommerce.payFromQr(
            payload = synthetic,
            fallback = session.currentStore,
            stores = session.catalog.stores(),
        )?.copy(qrType = outcome.barcodeType ?: "pay")
            ?: return JSONObject().put("ok", false).put("error", "付款码无法对应门店").toString()
        if (session.currentStore == null && order.merchantId.isNotBlank()) {
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
            .put("vision_source", source)
            .toString()
    }

    private fun finishScanCoupon(jpeg: ByteArray, outcome: VisionOutcome, source: String): String {
        if (outcome.barcodeKind == "table") {
            return JSONObject()
                .put("ok", true)
                .put("kind", "table")
                .put("table", outcome.tableHint)
                .put("note", "这是桌码，不是券码，不能当成核销成功")
                .put("vision_route", outcome.reason)
                .put("vision_source", source)
                .toString()
        }
        if (outcome.barcodeKind == "pay") {
            return JSONObject()
                .put("ok", false)
                .put("kind", "pay")
                .put("error", "这是付款码，请用买单而不是核销")
                .put("vision_route", outcome.reason)
                .put("vision_source", source)
                .toString()
        }
        if (session.currentStore == null && outcome.merchantHint.isNotBlank()) {
            session.look(forceStoreId = outcome.merchantHint)
        }
        if (session.currentStore == null) {
            return JSONObject()
                .put("ok", false)
                .put("error", "还没确认要服务的门店，先认店再扫券")
                .put("vision_source", source)
                .toString()
        }
        if (session.lastCoupons.isEmpty()) {
            session.listCoupons()
        }
        val titleHint = if (outcome.needsLlm && PhoneAi.ready) {
            PhoneAi.look(VisionRouter.bytesForLlm(jpeg, outcome), "scan_coupon", outcome.ocrText).speak
        } else {
            outcome.ocrText.ifBlank { outcome.fastSpeak }.orEmpty()
        }
        val picked = session.lastCoupons.firstOrNull { coupon ->
            outcome.couponHint.isNotBlank() && (
                coupon.id.contains(outcome.couponHint) || outcome.couponHint.contains(coupon.id)
            )
        } ?: session.lastCoupons.firstOrNull { coupon ->
            titleHint.isNotBlank() && (
                coupon.title.contains(titleHint.take(8)) || titleHint.contains(coupon.title.take(4))
            )
        }
        if (picked == null) {
            val card = HudCard.fromCoupons(session.currentStore?.shortName.orEmpty(), session.lastCoupons)
            publishSkillCard(card, ActiveSkill.COUPON, card.wait.ifBlank { "请选定一张券" }, "scan_coupon")
            return JSONObject()
                .put("ok", true)
                .put("kind", "coupon")
                .put("need_confirm", true)
                .put("coupons", session.lastCoupons.joinToString("；") { "${it.id}:${it.title}¥${it.price}" })
                .put("ocr", outcome.ocrText.take(80))
                .put("message", "看到券面，请选定一张再确认核销")
                .put("note", "扫码不等于核销成功")
                .put("vision_route", outcome.reason)
                .put("vision_source", source)
                .toString()
        }
        session.prepareCoupon(picked.id)
        val card = HudCard.fromCoupons(session.currentStore?.shortName.orEmpty(), listOf(picked))
        publishSkillCard(card, ActiveSkill.COUPON, "确认后才核销${picked.title}", "scan_coupon")
        return JSONObject()
            .put("ok", true)
            .put("kind", "coupon")
            .put("need_confirm", true)
            .put("coupon_id", picked.id)
            .put("title", picked.title)
            .put("price", picked.price)
            .put("store", session.currentStore?.shortName)
            .put("message", "看到${picked.title}，确认后才核销")
            .put("note", "扫码不等于核销成功")
            .put("vision_route", outcome.reason)
            .put("vision_source", source)
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

    private fun publishMatch(result: MatchResult, source: String) {
        talk.finishAsking()
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
        if (!_ui.value.talking || talk.asking || _ui.value.recognizing) return
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
        if (!_ui.value.talking || talk.asking || _ui.value.recognizing) return
        scheduleUnmute()
    }

    private fun scheduleUnmute() {
        main.removeCallbacks(unmuteAfterTts)
        main.postDelayed(unmuteAfterTts, 700)
    }

    private fun onAsrEndpoint() {
        if (!_ui.value.talking || _ui.value.recognizing) return
        val thinking = HudCard.thinking(currentMatch(), navCard = navCard())
        _ui.update { it.copy(card = thinking, asrPartial = "", status = "思考中") }
        CxrLinkHost.showCard(thinking)
    }

    private fun navCard(): HudCard? {
        val hint = _ui.value.navHint ?: return null
        if (session.activeSkill != ActiveSkill.NAV) return null
        return HudCard.fromNav(
            hint.storeName,
            hint.turn,
            hint.meters,
            hint.text,
            remaining = hint.remaining,
            mode = hint.mode,
            headingDeg = hint.headingDeg,
            elevationDeg = hint.elevationDeg,
            stage = hint.stage,
        )
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


    private fun rememberSpokenFloor(text: String) {
        val floor = LandmarkSignage.parseSpokenFloor(text)
        if (floor.isNotBlank()) spokenFloor = floor
        EnvironmentStore.replace(
            EnvironmentMerge.fromSpeech(text, EnvironmentStore.snapshot(), SystemClock.elapsedRealtime()),
        )
    }

    private fun startNavInternal(spoken: String = spokenFloor): String? {
        val store0 = session.currentStore ?: return "还没确定导航目的地"
        val app = getApplication<Application>()
        val goal = LandmarkPlanner.goalOf(store0)
        val dest = StorePins.destOf(store0)
        if (dest == null && goal.floor.isBlank()) {
            return "这个地点还没有坐标，请搜索附近或换一个目的地"
        }
        val origin = if (PhoneGps.hasPermission(app)) {
            PhoneGps.awaitFix(app, 4_000)
        } else {
            null
        }
        val hasFix = origin != null
        val outdoorFix = hasFix &&
            dest != null &&
            !LandmarkPlanner.gpsLooksIndoor(PhoneGps.lastAccuracyM, hasFix)
        if (outdoorFix && PhoneAi.amapKey.isBlank()) {
            return "没配高德步行密钥，在 ai.env 写 AMAP_WEB_KEY"
        }
        val route = if (outdoorFix && origin != null && dest != null) {
            AmapWalking.fetch(origin, dest, store0.shortName, PhoneAi.amapKey)
        } else {
            null
        }
        if (!session.startNav()) return "还没确定导航目的地"
        announcedArrive = false
        lastRerouteAt = 0
        lastLandmarkStage = ""
        indoor.start(goal, gpsUsable = outdoorFix, spokenFloor = spoken)
        if (spoken.isNotBlank()) spokenFloor = LandmarkSignage.normalizeFloor(spoken).ifBlank { spokenFloor }
        WalkGuide.clear()
        if (route != null) WalkGuide.set(route)
        PhoneGps.onUpdate = { onGps(it) }
        if (PhoneGps.hasPermission(app)) {
            try {
                NavLocationService.start(app)
            } catch (error: Exception) {
                Log.w(TAG, "nav location ${error.message}")
            }
        }
        val remaining = route?.distance ?: 0
        val hint = if (outdoorFix) {
            val walked = origin?.let { WalkGuide.update(it) }
            walked ?: NavHint(
                storeName = store0.shortName,
                text = "开始走",
                remaining = remaining,
            )
        } else {
            indoor.bootstrapHint(goal, remaining)
        }
        val card = navHud(hint)
        main.post {
            _ui.update {
                it.copy(
                    skill = ActiveSkill.NAV,
                    navHint = hint,
                    card = card,
                    match = session.lastMatch,
                    status = hint.text.ifBlank { "开始去${store0.shortName}" },
                )
            }
            CxrLinkHost.startNav(hint)
            CxrLinkHost.showCard(card)
        }
        kickOpenVisionStream()
        return null
    }

    private fun stopNavInternal(silent: Boolean) {
        val wasNav = session.activeSkill == ActiveSkill.NAV
        session.stopNav()
        PhoneGps.onUpdate = null
        WalkGuide.clear()
        announcedArrive = false
        indoor.reset()
        lastLandmarkStage = ""
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
            val visualActive = indoor.landmarks.stage != LandmarkStage.OUTDOOR &&
                indoor.landmarks.stage != LandmarkStage.ARRIVED
            if (visualActive) {
                val current = _ui.value.navHint
                if (current != null && current.visual) {
                    main.post { publishNavHint(current.copy(remaining = hint.remaining)) }
                    return@launch
                }
                if (hint.turn == "arrive") {
                    return@launch
                }
            }
            main.post { publishNavHint(hint) }
        }
    }

    private fun maybeReroute(point: GeoPoint) {
        if (indoor.landmarks.stage != LandmarkStage.OUTDOOR) return
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
            mode = hint.mode,
            headingDeg = hint.headingDeg,
            elevationDeg = hint.elevationDeg,
            stage = hint.stage,
            sessionId = hint.sessionId,
            tracking = hint.tracking,
            waypoints = hint.waypoints,
        )
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

    private fun publishAgentContext() {
        val world = worldContext()
        val text = AgentContextAssembler.format(world)
        val debug = AgentContextAssembler.debug(world)
        if (text == _ui.value.agentContext && debug == _ui.value.agentDebug) return
        _ui.update { it.copy(agentContext = text, agentDebug = debug) }
    }

    private fun worldContext(): WorldContext {
        val store = session.currentStore
        val gps = PhoneGps.last
        val env = EnvironmentStore.snapshot()
        val disambiguation = session.candidates.map { PlaceResolver.fromStore(it) }
        val search = cachedPlaces.filter { place -> disambiguation.none { it.id == place.id } }
        return WorldContext(
            skill = session.activeSkill.name.lowercase(),
            boundPlace = store?.let { PlaceResolver.fromStore(it) },
            disambiguation = disambiguation,
            recentSearch = search,
            observation = latestObservation,
            gpsPermission = PhoneGps.hasPermission(getApplication()),
            hasGpsFix = gps != null && (kotlin.math.abs(gps.lat) > 1e-6 || kotlin.math.abs(gps.lng) > 1e-6),
            gpsLat = gps?.lat,
            gpsLng = gps?.lng,
            catalogCount = session.catalog.stores().size,
            pendingPay = session.pendingPay?.let { "待付${it.amount}元给${it.storeName}" }.orEmpty(),
            pendingCoupon = session.pendingCoupon?.title.orEmpty(),
            spokenFloor = spokenFloor,
            environment = env,
            navActive = session.activeSkill == ActiveSkill.NAV,
            modelReady = PhoneAi.ready,
            capabilities = listOf("问答", "看环境", "搜附近", "导航", "到餐增强", "演示支付"),
            navArrived = _ui.value.navHint?.turn == "arrive",
        )
    }

    inner class Runtime : PhoneWorld {
        override val session get() = this@DiningViewModel.session
        override val app get() = getApplication<Application>()
        override val ui get() = _ui
        override val question get() = _ui.value.question
        override val spokenFloor get() = this@DiningViewModel.spokenFloor
        override val currentFloor get() = EnvironmentStore.snapshot().usableFloor.ifBlank { spokenFloor }
        override var recommendedThisTurn
            get() = this@DiningViewModel.recommendedThisTurn
            set(value) { this@DiningViewModel.recommendedThisTurn = value }
        override var selectedThisTurn
            get() = this@DiningViewModel.selectedThisTurn
            set(value) { this@DiningViewModel.selectedThisTurn = value }
        override val observation get() = latestObservation
        override fun gps() = PhoneGps.last
        override fun awaitGps(timeoutMs: Long) = PhoneGps.awaitFix(app, timeoutMs)
        override fun hasGpsPermission() = PhoneGps.hasPermission(app)
        override fun amapKey() = PhoneAi.amapKey
        override fun reloadCatalogWithGps() {
            if (!hasGpsPermission()) return
            val origin = gps() ?: awaitGps(4_000) ?: return
            val stores = StoreGeo.applyDistances(PhoneCatalog.load(app), origin.lat, origin.lng)
            session.replaceCatalog(MemoryStoreCatalog(stores))
            _ui.update { it.copy(storeCount = stores.size) }
        }
        override fun capture(intent: VisionIntent, spatial: Boolean, bindStore: Boolean): String {
            return processVision(intent, spatial, bindStore)
        }
        override fun startNav(spoken: String): String? = startNavInternal(spoken)
        override fun stopNav(silent: Boolean) = stopNavInternal(silent)
        override fun publishSkillCard(card: HudCard, skill: ActiveSkill, status: String, intent: String) {
            this@DiningViewModel.publishSkillCard(card, skill, status, intent)
        }
        override fun publishMatch(result: MatchResult, source: String) {
            this@DiningViewModel.publishMatch(result, source)
        }
        override fun publishTalk(speak: String, tts: Boolean) {
            val card = HudCard.talk(speak)
            main.post {
                _ui.update { it.copy(card = card, status = speak) }
                CxrLinkHost.showCard(card)
                if (tts) PhoneTts.speak(speak)
            }
        }
        override fun showCard(card: HudCard) {
            main.post { CxrLinkHost.showCard(card) }
        }
        override fun rememberSpokenStore() = this@DiningViewModel.rememberSpokenStore()
        override fun rememberVisionStore(id: String) = this@DiningViewModel.rememberVisionStore(id)
        override fun bindPlace(place: PlaceRef): MatchResult {
            val result = session.bindPlace(place)
            cachedPlaces = listOf(place) + cachedPlaces.filter { it.id != place.id }
            return result
        }
        override fun latestPlaces(): List<PlaceRef> = cachedPlaces
        override fun rememberPlaces(places: List<PlaceRef>) {
            cachedPlaces = places
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

        fun isScanCouponIntent(text: String): Boolean {
            return listOf("扫券", "核销这张", "扫一下券", "券码", "团购码", "扫码核销").any { text.contains(it) }
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
    }
}
