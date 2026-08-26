package com.glass.dining.phone

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

object PhoneTts {
    private const val TAG = "GlassDiningPhone"
    private var tts: TextToSpeech? = null
    @Volatile private var ready: Boolean = false
    @Volatile private var pending: String? = null
    @Volatile var speaking: Boolean = false
        private set
    var onIdle: (() -> Unit)? = null
    @Volatile private var activeId: String = ""
    private var seq: Int = 0

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val engine = tts
                val zh = engine?.setLanguage(Locale.CHINA)
                if (zh == TextToSpeech.LANG_MISSING_DATA || zh == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine?.setLanguage(Locale.CHINESE)
                }
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId != activeId) return
                        speaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        finishIfCurrent(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        finishIfCurrent(utteranceId)
                    }
                })
                Log.i(TAG, "phone TTS ready")
                pending?.let { speak(it) }
                pending = null
            } else {
                Log.w(TAG, "phone TTS init failed status=$status")
            }
        }
    }

    fun speak(text: String) {
        enqueue(text, flush = true)
    }

    fun speakMore(text: String) {
        enqueue(text, flush = false)
    }

    private fun enqueue(text: String, flush: Boolean) {
        val spoken = text.trim()
        if (spoken.isBlank()) return
        val engine = tts
        if (!ready || engine == null) {
            pending = spoken
            return
        }
        seq += 1
        val id = "dining-ai-$seq"
        activeId = id
        speaking = true
        GlassAsr.holdForTts()
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(spoken, mode, null, id)
    }

    private fun finishIfCurrent(utteranceId: String?) {
        if (utteranceId != activeId) return
        speaking = false
        onIdle?.invoke()
    }

    fun stop() {
        activeId = ""
        tts?.stop()
        speaking = false
    }

    fun shutdown() {
        activeId = ""
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        speaking = false
    }
}
