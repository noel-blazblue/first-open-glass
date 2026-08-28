package com.glass.dining.phone

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.glass.dining.shared.agent.SpeechPriority
import com.glass.dining.shared.agent.StreamSpeakAction
import com.glass.dining.shared.agent.StreamingReplyAssembler
import com.glass.dining.shared.agent.TtsPlayState
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.model.QaResult

/** ViewModel 只接线：节流 HUD、通用分段、把可播片段交给 TTS。 */
class ConversationOutputCoordinator(
    private val talk: TalkTurn,
    private val main: Handler,
    private val skillLocked: () -> Boolean,
    private val publish: (HudCard?, QaResult, String) -> Unit,
    private val onBeforeTalk: () -> Unit = {},
) {
    private val reply = StreamingReplyAssembler()
    private var seq: Int = 0
    private var question: String = ""
    private var lastHudAt: Long = 0
    private var lastHudText: String = ""
    private var startedSpeak: Boolean = false
    private val idleFlush = Runnable { onIdleFlush() }

    fun begin(seq: Int, question: String) {
        this.seq = seq
        this.question = question
        reply.reset()
        lastHudAt = 0
        lastHudText = ""
        startedSpeak = false
        main.removeCallbacks(idleFlush)
        Log.i(TAG, "voice turn=$seq phase=think chars=${question.length}")
    }

    fun onModelText(seq: Int, partial: String, done: Boolean) {
        if (!talk.isCurrent(seq)) return
        if (!done) {
            if (partial.isBlank()) return
            speakChunks(reply.onPartial(partial))
            main.removeCallbacks(idleFlush)
            main.postDelayed(idleFlush, 400)
            return
        }
        main.removeCallbacks(idleFlush)
        when (val action = reply.onDone(partial)) {
            is StreamSpeakAction.Speak -> speakChunks(action.chunks)
            is StreamSpeakAction.Replace -> {
                startedSpeak = false
                speakChunks(action.chunks)
            }
        }
        PhoneTts.finishInput()
        if (!PhoneTts.speaking && PhoneTts.state == TtsPlayState.IDLE) {
            showHud(partial, force = true)
        }
    }

    fun onToolStep(seq: Int) {
        if (!talk.isCurrent(seq)) return
        main.removeCallbacks(idleFlush)
        speakChunks(reply.finishStep())
        Log.i(TAG, "voice turn=$seq phase=tool-step")
    }

    fun onPlayed(played: String) {
        if (!talk.isCurrent(seq) || played.isBlank()) return
        showHud(played + PhoneTts.lookahead, force = false)
    }

    fun onAudible() {
        if (!talk.isCurrent(seq)) return
        Log.i(TAG, "voice turn=$seq phase=hud-first chars=${PhoneTts.audibleText.length}")
    }

    fun speakDirect(text: String, tts: Boolean, priority: SpeechPriority = SpeechPriority.AGENT) {
        val spoken = text.trim()
        if (spoken.isBlank()) return
        if (!skillLocked()) onBeforeTalk()
        if (tts) {
            if (skillLocked()) {
                publish(null, QaResult(question, "ask", spoken, spoken), spoken)
            }
            PhoneTts.speak(spoken, priority)
        } else if (!skillLocked()) {
            showHud(spoken, force = true)
        }
    }

    private fun speakChunks(chunks: List<String>) {
        if (chunks.any { it.isNotBlank() }) onBeforeTalk()
        chunks.forEach { chunk ->
            if (!startedSpeak) {
                PhoneTts.speak(chunk, SpeechPriority.AGENT)
                startedSpeak = true
            } else {
                PhoneTts.speakMore(chunk)
            }
        }
    }

    private fun onIdleFlush() {
        if (!talk.isCurrent(seq)) return
        speakChunks(reply.onIdle())
    }

    private fun showHud(text: String, force: Boolean) {
        val shown = text.trim()
        if (shown.isBlank()) return
        val now = SystemClock.uptimeMillis()
        if (!force) {
            if (now - lastHudAt < HUD_MS && shown == lastHudText) return
            if (now - lastHudAt < HUD_MS && shown.startsWith(lastHudText) && shown.length - lastHudText.length < 2) return
        }
        if (shown == lastHudText && !force) return
        lastHudAt = now
        lastHudText = shown
        if (!skillLocked()) onBeforeTalk()
        val qa = QaResult(question, "ask", shown, shown)
        val card = if (skillLocked()) null else HudCard.talk(shown, pose = HudCard.POSE_SPEAK)
        publish(card, qa, shown)
    }

    companion object {
        private const val TAG = "GlassDiningPhone"
        private const val HUD_MS = 180L
    }
}
