package com.glass.dining.phone

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import com.glass.dining.shared.engine.DiningSession
import com.glass.dining.shared.hud.HudUiState
import com.glass.dining.shared.mock.MockCatalog
import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.QaResult
import com.glass.dining.shared.model.Scene
import com.glass.dining.shared.model.Store
import com.glass.dining.shared.protocol.DiningMessage
import com.glass.dining.shared.protocol.DiningMsgType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PhoneUiState(
    val status: String = "本地 mock 可用，眼镜未连接",
    val scenes: List<Scene> = MockCatalog.scenes,
    val sceneId: String = MockCatalog.defaultSceneId,
    val hud: HudUiState = HudUiState(),
    val match: MatchResult? = null,
    val lastQa: QaResult? = null,
    val question: String = "",
    val devices: List<BluetoothDevice> = emptyList(),
    val sdkReady: Boolean = false,
    val btConnected: Boolean = false,
)

class DiningViewModel : ViewModel() {
    private val session = DiningSession()
    private val _ui = MutableStateFlow(PhoneUiState())
    val ui: StateFlow<PhoneUiState> = _ui.asStateFlow()

    init {
        PhoneSdkHost.onStatus = { line -> _ui.update { it.copy(status = line) } }
        PhoneSdkHost.onDevices = { list -> _ui.update { it.copy(devices = list) } }
        PhoneSdkHost.onTextMessage = { raw -> handleGlasses(raw) }
        _ui.update {
            it.copy(
                sdkReady = PhoneSdkHost.sdkReady,
                btConnected = PhoneSdkHost.btConnected,
            )
        }
    }

    fun selectScene(id: String) {
        session.setScene(id)
        _ui.update { it.copy(sceneId = id, match = null, lastQa = null, hud = HudUiState()) }
    }

    fun look(forceStoreId: String? = null) {
        val result = session.look(forceStoreId)
        publishMatch(result, echoToGlasses = true)
    }

    fun next() {
        val result = session.next()
        publishMatch(result, echoToGlasses = true)
    }

    fun ask(question: String) {
        val text = question.trim().ifBlank { return }
        val qa = session.ask(text)
        _ui.update {
            it.copy(
                lastQa = qa,
                question = text,
                hud = HudUiState.withAnswer(it.hud, qa),
            )
        }
        PhoneSdkHost.sendToGlasses(
            DiningMessage.fromQa(qa, session.sceneId, session.lastMatch?.store?.id).toJson(),
        )
    }

    fun updateQuestion(value: String) {
        _ui.update { it.copy(question = value) }
    }

    fun scanGlasses() {
        PhoneSdkHost.scan()
    }

    fun connect(device: BluetoothDevice) {
        PhoneSdkHost.connect(device)
    }

    fun currentStores(): List<Store> {
        return MockCatalog.sceneById(_ui.value.sceneId).stores
    }

    private fun publishMatch(result: MatchResult, echoToGlasses: Boolean) {
        _ui.update {
            it.copy(
                match = result,
                lastQa = null,
                hud = HudUiState.fromMatch(result),
                sdkReady = PhoneSdkHost.sdkReady,
                btConnected = PhoneSdkHost.btConnected,
            )
        }
        if (echoToGlasses) {
            PhoneSdkHost.sendToGlasses(DiningMessage.fromMatch(result).toJson())
        }
    }

    private fun handleGlasses(raw: String) {
        val msg = DiningMessage.parse(raw) ?: return
        when (msg.type) {
            DiningMsgType.STORE_RESULT -> {
                val storeId = msg.storeId ?: msg.store?.id ?: return
                msg.sceneId?.let { id ->
                    session.setScene(id)
                    _ui.update { it.copy(sceneId = id) }
                }
                publishMatch(session.select(storeId), echoToGlasses = false)
            }
            DiningMsgType.LOOK -> look(msg.storeId)
            DiningMsgType.NEXT -> next()
            DiningMsgType.SELECT -> msg.storeId?.let { look(it) }
            DiningMsgType.ASK -> ask(msg.question.orEmpty())
        }
    }
}
