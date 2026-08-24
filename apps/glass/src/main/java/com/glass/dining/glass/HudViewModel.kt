package com.glass.dining.glass

import androidx.lifecycle.ViewModel
import com.glass.dining.shared.engine.DiningSession
import com.glass.dining.shared.hud.HudUiState
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.protocol.DiningMessage
import com.glass.dining.shared.protocol.DiningMsgType

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HudViewModel : ViewModel() {
    private val session = DiningSession()
    private val _ui = MutableStateFlow(
        HudUiState(
            idleHint = "直接说话问我",
            statusLine = "我在听",
        ),
    )
    val ui: StateFlow<HudUiState> = _ui.asStateFlow()

    init {
        GlassSdkHost.onTextMessage = { raw -> handleIncoming(raw) }
        GlassSdkHost.onLook = { look() }
        GlassSdkHost.onNext = { next() }
        GlassSdkHost.onAsk = { ask(it) }
        GlassSdkHost.onListen = {
            _ui.value = _ui.value.copy(listening = true, statusLine = "正在听，请说话")
        }
        GlassSdkHost.onListenState = { listening ->
            _ui.value = _ui.value.copy(listening = listening)
        }
        GlassSdkHost.onAsrPartial = { text ->
            _ui.value = _ui.value.copy(listening = true, statusLine = text)
        }
        GlassSdkHost.onAsrText = { text -> onHeard(text) }
        GlassSdkHost.onAsrError = { message ->
            _ui.value = _ui.value.copy(listening = false, statusLine = message)
        }
        GlassSdkHost.onAiReply = { applyAi(it) }
        GlassSdkHost.onSdkReady = { sdkReady ->
            if (sdkReady && _ui.value.store == null && !_ui.value.recognizing && !_ui.value.listening) {
                _ui.value = _ui.value.copy(statusLine = "直接说话问我")
            }
        }
    }

    fun look() {
        if (_ui.value.recognizing) return
        _ui.value = _ui.value.copy(
            recognizing = true,
            listening = false,
            idleHint = "对准店招",
            statusLine = "正在拍照识别…",
        )
        GlassSdkHost.captureAndRecognize(
            onResult = { hint, fingerprint ->
                val result = session.look(visionHint = hint, imageFingerprint = fingerprint)
                publishMatch(result, spoken = true)
                if (hint.isNullOrBlank() && !GlassSdkHost.ready) {
                    _ui.value = _ui.value.copy(statusLine = "已拍照，按店招指纹匹配附近门店")
                }
            },
            onFailed = { reason ->
                _ui.value = _ui.value.copy(
                    recognizing = false,
                    statusLine = reason,
                )
            },
        )
    }

    fun next() {
        val result = session.next()
        publishMatch(result, spoken = true)
    }

    fun ask(question: String) {
        val text = question.trim()
        if (text.isBlank()) return
        val qa = session.ask(text)
        _ui.value = HudUiState.withAnswer(_ui.value, qa).copy(listening = false, recognizing = false)
        GlassSdkHost.speak(qa.tts)
        GlassSdkHost.sendText(
            DiningMessage(
                type = DiningMsgType.ASK,
                sceneId = session.sceneId,
                storeId = session.lastMatch?.store?.id,
                question = text,
            ).toJson(),
        )
    }

    fun showStatus(text: String) {
        _ui.value = _ui.value.copy(listening = true, statusLine = text)
    }

    private fun applyAi(reply: InboxClient.Reply?) {
        if (reply == null) {
            _ui.value = _ui.value.copy(listening = true, statusLine = "电脑AI没连上")
            return
        }
        if (reply.hud.isNotBlank()) {
            _ui.value = _ui.value.copy(listening = true, statusLine = reply.hud)
        }
        val runCmd = {
            when (reply.cmd) {
                "look" -> look()
                "next" -> next()
            }
        }
        if (reply.audioUrl.isNotBlank()) {
            GlassSdkHost.speak(reply.speak, reply.audioUrl, runCmd)
        } else {
            GlassSdkHost.speak(reply.speak)
            runCmd()
        }
    }

    private fun onHeard(text: String) {
        val spoken = text.replace("\\s+".toRegex(), "")
        if (spoken.isBlank() || spoken == "[unk]") {
            _ui.value = _ui.value.copy(listening = true, statusLine = "没听清，请再说")
            return
        }
        _ui.value = _ui.value.copy(listening = true, statusLine = spoken)
        GlassSdkHost.forwardUtterance(spoken)
    }

    private fun publishMatch(result: MatchResult, spoken: Boolean) {
        _ui.value = HudUiState.fromMatch(result).copy(recognizing = false, listening = false)
        if (spoken) {
            GlassSdkHost.speak(result.tts)
        }
        GlassSdkHost.sendText(DiningMessage.fromMatch(result).toJson())
    }

    private fun handleIncoming(raw: String) {
        val msg = DiningMessage.parse(raw) ?: return
        when (msg.type) {
            DiningMsgType.STORE_RESULT -> {
                msg.store?.let { store ->
                    msg.sceneId?.let { session.setScene(it) }
                    val alreadyShown = _ui.value.store?.id == store.id
                    session.select(store.id)
                    _ui.value = HudUiState(
                        store = store,
                        candidates = msg.candidates.orEmpty(),
                        confidence = msg.confidence,
                        tts = msg.tts,
                        statusLine = msg.tts.orEmpty(),
                        recognizing = false,
                    )
                    if (!alreadyShown) {
                        msg.tts?.let { GlassSdkHost.speak(it) }
                    }
                }
            }
            DiningMsgType.ANSWER, DiningMsgType.TTS -> {
                val text = msg.tts ?: msg.answer ?: return
                _ui.value = _ui.value.copy(
                    answer = msg.answer ?: text,
                    tts = text,
                    statusLine = text,
                    listening = false,
                )
                GlassSdkHost.speak(text)
            }
        }
    }
}
