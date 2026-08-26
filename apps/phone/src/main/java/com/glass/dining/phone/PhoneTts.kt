package com.glass.dining.phone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

object PhoneTts {
    private const val TAG = "GlassDiningPhone"
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    @Volatile private var ready: Boolean = false
    @Volatile private var pending: String? = null
    @Volatile var speaking: Boolean = false
        private set
    var onIdle: (() -> Unit)? = null
    @Volatile private var activeId: String = ""
    private var seq: Int = 0

    private val cloudQueue = LinkedBlockingQueue<String>(16)
    private val cloudRunning = AtomicBoolean(false)
    @Volatile private var cloudPlayback: AudioTrack? = null
    @Volatile private var cloudPlaySeq: Int = 0

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
                Log.i(TAG, "phone TTS ready nls=${NlsClient.ready}")
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
        if (NlsClient.ready) {
            enqueueCloud(spoken, flush)
            return
        }
        enqueueAndroid(spoken, flush)
    }

    private fun enqueueCloud(text: String, flush: Boolean) {
        if (flush) {
            cloudQueue.clear()
            stopCloudPlayback()
        }
        if (!cloudQueue.offer(text)) {
            cloudQueue.poll()
            cloudQueue.offer(text)
        }
        speaking = true
        GlassAsr.holdForTts()
        startCloudWorker()
    }

    private fun enqueueAndroid(text: String, flush: Boolean) {
        val engine = tts
        if (!ready || engine == null) {
            pending = text
            return
        }
        seq += 1
        val id = "dining-ai-$seq"
        activeId = id
        speaking = true
        GlassAsr.holdForTts()
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, id)
    }

    private fun startCloudWorker() {
        if (!cloudRunning.compareAndSet(false, true)) return
        Thread({
            try {
                while (true) {
                    val chunk = cloudQueue.poll(80, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (cloudQueue.isEmpty()) break
                        continue
                    }
                    speaking = true
                    GlassAsr.holdForTts()
                    val pcm = NlsClient.synthesize(chunk)
                    if (pcm == null) {
                        Log.w(TAG, "nls tts empty, skip audio")
                        continue
                    }
                    playPcm(pcm)
                }
            } finally {
                cloudRunning.set(false)
                if (cloudQueue.isEmpty()) {
                    speaking = false
                    main.post { onIdle?.invoke() }
                } else {
                    startCloudWorker()
                }
            }
        }, "nls-tts").start()
    }

    private fun playPcm(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val rate = NlsClient.sampleRate
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val seq = cloudPlaySeq
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        cloudPlayback = track
        try {
            track.play()
            var offset = 0
            while (offset < pcm.size && seq == cloudPlaySeq) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) break
                offset += written
            }
            val frames = pcm.size / 2
            while (seq == cloudPlaySeq && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                if (track.playbackHeadPosition >= frames) break
                Thread.sleep(20)
            }
        } catch (error: Exception) {
            Log.w(TAG, "nls tts play failed", error)
        } finally {
            if (cloudPlayback === track) {
                cloudPlayback = null
            }
            try {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopCloudPlayback() {
        cloudPlaySeq += 1
        val track = cloudPlayback
        cloudPlayback = null
        if (track == null) return
        try {
            track.pause()
            track.flush()
            track.stop()
        } catch (_: Exception) {
        }
    }

    private fun finishIfCurrent(utteranceId: String?) {
        if (utteranceId != activeId) return
        speaking = false
        onIdle?.invoke()
    }

    fun stop() {
        activeId = ""
        cloudQueue.clear()
        stopCloudPlayback()
        tts?.stop()
        speaking = false
    }

    fun shutdown() {
        activeId = ""
        cloudQueue.clear()
        stopCloudPlayback()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        speaking = false
    }
}
