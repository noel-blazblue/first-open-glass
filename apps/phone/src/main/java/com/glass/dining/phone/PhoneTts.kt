package com.glass.dining.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.glass.dining.shared.agent.SpeechPriority
import com.glass.dining.shared.agent.TtsBackend
import com.glass.dining.shared.agent.TtsPlayState
import com.glass.dining.shared.agent.TtsRouter
import com.glass.dining.shared.agent.TtsSession
import java.util.Locale

object PhoneTts {
    private const val TAG = "GlassDiningPhone"
    private val main = Handler(Looper.getMainLooper())
    private val session = TtsSession()

    private var tts: TextToSpeech? = null
    @Volatile private var ready: Boolean = false
    @Volatile private var pending: String? = null
    @Volatile private var streamingDenied: Boolean = false
    @Volatile private var androidText: String = ""
    private var androidSeq: Int = 0
    @Volatile private var activeId: String = ""
    @Volatile private var streaming: NlsStreamingTtsClient? = null
    @Volatile private var backend: TtsBackend = TtsBackend.NONE
    @Volatile private var restCovered: Int = 0

    val speaking: Boolean get() = session.speaking
    val audible: Boolean get() = session.audible
    val spokenText: String get() = session.queuedText
    val echoWindow: String get() = session.echoWindow
    val audibleText: String get() = session.audibleText
    val lookahead: String get() = session.lookahead
    val state: TtsPlayState get() = session.state
    val generation: Int get() = session.generation

    var onIdle: (() -> Unit)? = null
    var onProgress: ((String) -> Unit)? = null
    var onAudible: (() -> Unit)? = null

    private val rest = RestTtsPlayer(
        currentGen = { session.generation },
        onAudible = { gen, text -> markAudible(gen, text) },
        onProgress = { gen, text -> markProgress(gen, text) },
        onFinished = { gen -> finishIfCurrent(gen) },
        onFailed = { gen, message ->
            Log.w(TAG, "nls rest tts fail gen=$gen ${message.take(80)}")
            finishIfCurrent(gen)
        },
    )

    fun init(context: Context) {
        if (NlsClient.ready) {
            Log.i(TAG, "phone TTS using nls voice=${NlsClient.voice} rate=${NlsClient.sampleRate}")
            return
        }
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val engine = tts
                val zh = engine?.setLanguage(Locale.CHINA)
                if (zh == TextToSpeech.LANG_MISSING_DATA || zh == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine?.setLanguage(Locale.CHINESE)
                }
                engine?.setOnUtteranceProgressListener(androidListener())
                Log.i(TAG, "phone TTS ready nls=${NlsClient.ready}")
                pending?.let { speak(it) }
                pending = null
            } else {
                Log.w(TAG, "phone TTS init failed status=$status")
            }
        }
    }

    fun speak(text: String, priority: SpeechPriority = SpeechPriority.AGENT) {
        enqueue(text, flush = true, priority = priority)
    }

    fun speakMore(text: String, priority: SpeechPriority = SpeechPriority.AGENT) {
        enqueue(text, flush = false, priority = priority)
    }

    fun finishInput() {
        streaming?.finishInput()
    }

    fun stop() {
        val wasSpeaking = session.speaking
        session.bump()
        clearBackends()
        activeId = ""
        tts?.stop()
        if (wasSpeaking) main.post { onIdle?.invoke() }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun enqueue(text: String, flush: Boolean, priority: SpeechPriority) {
        val spoken = text.trim()
        if (spoken.isBlank()) return
        val gen = session.begin(priority, flush) ?: return
        if (flush) {
            clearBackends()
            tts?.stop()
        }
        session.appendQueued(spoken)
        GlassAsr.holdForTts()
        Log.i(TAG, "voice ttsSession=$gen phase=enqueue chars=${spoken.length} flush=$flush pri=${priority.name}")
        when (TtsRouter.choose(NlsClient.ready, streamingDenied, backend)) {
            TtsBackend.WS -> enqueueNls(spoken, gen)
            TtsBackend.REST -> enqueueRest(gen)
            TtsBackend.ANDROID -> enqueueAndroid(spoken, flush)
            TtsBackend.NONE -> enqueueAndroid(spoken, flush)
        }
    }

    private fun enqueueNls(text: String, gen: Int) {
        val client = synchronized(this) {
            if (backend == TtsBackend.REST) {
                enqueueRest(gen)
                return
            }
            backend = TtsBackend.WS
            streaming ?: NlsStreamingTtsClient(
                generation = gen,
                currentGen = { session.generation },
                onAudible = { g, heard -> markAudible(g, heard) },
                onProgress = { g, heard -> markProgress(g, heard) },
                onFinished = { g -> finishIfCurrent(g) },
                onFailed = { g, message, heard -> onStreamFailed(g, message, heard) },
            ).also { created ->
                streaming = created
                Thread({ bootStreaming(created, gen) }, "nls-tts-ws").start()
            }
        }
        client.sendText(text)
    }

    private fun enqueueRest(gen: Int) {
        backend = TtsBackend.REST
        val queued = session.queuedText
        val (extra, covered) = TtsRouter.restSlice(queued, restCovered)
        restCovered = covered
        if (extra.isNotBlank()) rest.enqueue(extra, gen)
    }

    private fun bootStreaming(client: NlsStreamingTtsClient, gen: Int) {
        val error = client.start()
        if (gen != session.generation) {
            client.stop()
            return
        }
        if (error != null) {
            Log.w(TAG, "nls tts ws fallback rest: $error")
            fallbackRest(gen, error, client.audibleText())
        }
    }

    private fun onStreamFailed(gen: Int, message: String, heard: String) {
        if (gen != session.generation) return
        Log.w(TAG, "nls tts ws fail gen=$gen ${message.take(80)}")
        fallbackRest(gen, message, heard)
    }

    private fun fallbackRest(gen: Int, message: String, heard: String) {
        synchronized(this) {
            if (gen != session.generation) return
            if (TtsRouter.stickyDeny(message)) streamingDenied = true
            if (backend == TtsBackend.REST) return
            val leftover = TtsRouter.leftover(session.queuedText, session.audibleText, heard)
            backend = TtsBackend.REST
            val client = streaming
            streaming = null
            client?.stop()
            restCovered = session.queuedText.length
            if (leftover.isNotBlank()) rest.enqueue(leftover, gen)
            else finishIfCurrent(gen)
        }
    }

    private fun clearBackends() {
        synchronized(this) {
            streaming?.stop()
            streaming = null
            rest.stop()
            backend = TtsBackend.NONE
            restCovered = 0
        }
    }

    private fun enqueueAndroid(text: String, flush: Boolean) {
        backend = TtsBackend.ANDROID
        val engine = tts
        if (!ready || engine == null) {
            pending = text
            return
        }
        androidSeq += 1
        val id = "dining-ai-$androidSeq"
        activeId = id
        androidText = text
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, id)
    }

    private fun androidListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != activeId) return
                markAudible(session.generation, "")
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId != activeId) return
                markProgress(session.generation, androidText.take(end.coerceAtLeast(0)))
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != activeId) return
                finishIfCurrent(session.generation)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != activeId) return
                finishIfCurrent(session.generation)
            }
        }
    }

    private fun markAudible(gen: Int, text: String) {
        if (gen != session.generation) return
        val first = session.state == TtsPlayState.SYNTHESIZING
        session.markAudible(gen, text.ifBlank { session.audibleText })
        if (first) {
            Log.i(TAG, "voice ttsSession=$gen phase=audible chars=${session.audibleText.length}")
            main.post { onAudible?.invoke() }
        }
    }

    private fun markProgress(gen: Int, text: String) {
        if (gen != session.generation) return
        session.markAudible(gen, text)
        main.post { onProgress?.invoke(text) }
    }

    private fun finishIfCurrent(gen: Int) {
        if (gen != session.generation) return
        if (session.state == TtsPlayState.IDLE) return
        session.markDraining(gen)
        session.markIdle(gen)
        Log.i(TAG, "voice ttsSession=$gen phase=idle")
        main.post { onIdle?.invoke() }
    }
}
