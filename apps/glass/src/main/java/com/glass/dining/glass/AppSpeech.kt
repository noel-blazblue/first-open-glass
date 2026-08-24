package com.glass.dining.glass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

object AppSpeech {
    private const val TAG = "GlassDining"
    const val SAMPLE_RATE = 16_000
    private const val FRAME = SAMPLE_RATE / 10
    private const val MAX_UTTER_MS = 10_000L
    private const val MIN_UTTER_MS = 1_800L
    private const val SILENCE_FRAMES = 18

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    private var stopRequested = false

    @Volatile
    private var model: Model? = null

    @Volatile
    private var activeRecord: AudioRecord? = null

    private val main = Handler(Looper.getMainLooper())
    private val modelLock = Any()

    fun modelDir(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(root, "asr/vosk-model-small-cn-0.22")
    }

    fun preload(context: Context) {
        Thread({
            val error = ensureModel(context)
            if (error == null) {
                Log.i(TAG, "vosk model ready")
            } else {
                Log.w(TAG, "vosk preload: $error")
            }
        }, "app-asr-preload").start()
    }

    fun start(
        context: Context,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError("没有麦克风权限")
            return
        }
        running = true
        stopRequested = false
        Thread({
            listenLoop(context, onPartial, onResult, onError)
        }, "app-asr").start()
    }

    fun releaseModel() {
        synchronized(modelLock) {
            try {
                model?.close()
            } catch (_: Exception) {
            }
            if (model != null) {
                Log.i(TAG, "vosk model released")
            }
            model = null
        }
    }

    fun awaitStopped(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    fun stop() {
        Log.i(TAG, "app asr stop requested")
        stopRequested = true
        try {
            activeRecord?.stop()
        } catch (_: Exception) {
        }
    }

    private fun listenLoop(
        context: Context,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        var record: AudioRecord? = null
        var recognizer: Recognizer? = null
        try {
            val loadError = ensureModel(context)
            if (loadError != null) {
                doneError(onError, loadError)
                return
            }
            val loaded = model ?: run {
                doneError(onError, "语音模型未加载")
                return
            }
            recognizer = Recognizer(loaded, SAMPLE_RATE.toFloat())
            val rec = recognizer ?: run {
                doneError(onError, "语音识别未就绪")
                return
            }
            record = openRecorder()
            if (record == null) {
                doneError(onError, "无法打开麦克风")
                return
            }
            record.startRecording()
            activeRecord = record
            Log.i(TAG, "app asr listening")
            main.post { onPartial("") }
            val buffer = ShortArray(FRAME)
            var noise = 80.0
            var inSpeech = false
            var silence = 0
            var speechStartedAt = 0L
            var bestPartial = ""
            var frames = 0
            while (!stopRequested) {
                val count = record.read(buffer, 0, buffer.size)
                if (count <= 0) {
                    if (stopRequested) break
                    continue
                }
                val level = rms(buffer, count)
                frames += 1
                if (frames % 20 == 0) {
                    Log.i(TAG, "app asr rms=${level.toInt()}")
                }
                val threshold = max(noise * 1.6, 50.0)
                if (!inSpeech) {
                    if (level < 8) {
                        continue
                    }
                    noise = noise * 0.92 + level * 0.08
                    if (level < threshold) continue
                    inSpeech = true
                    silence = 0
                    bestPartial = ""
                    speechStartedAt = System.currentTimeMillis()
                    rec.reset()
                    Log.i(TAG, "app asr speech start rms=${level.toInt()} thr=${threshold.toInt()}")
                }
                rec.acceptWaveForm(buffer, count)
                val partial = parsePartial(rec.partialResult)
                if (partial.length > bestPartial.length) {
                    bestPartial = partial
                }
                if (partial.isNotBlank()) {
                    main.post { onPartial(partial) }
                }
                if (level < threshold) {
                    silence += 1
                } else {
                    silence = 0
                }
                val elapsed = System.currentTimeMillis() - speechStartedAt
                val tooLong = elapsed > MAX_UTTER_MS
                val finished = elapsed >= MIN_UTTER_MS && silence >= SILENCE_FRAMES
                if (!tooLong && !finished) continue
                val finalText = parseText(rec.finalResult)
                val text = listOf(finalText, bestPartial, partial).maxBy { it.length }
                Log.i(TAG, "app asr text=$text final=$finalText partial=$bestPartial")
                inSpeech = false
                silence = 0
                bestPartial = ""
                if (text.isNotBlank()) {
                    main.post { onResult(text) }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "app asr failed", error)
            doneError(onError, error.message ?: "识别失败")
        } finally {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            try {
                record?.release()
            } catch (_: Exception) {
            }
            activeRecord = null
            try {
                recognizer?.close()
            } catch (_: Exception) {
            }
            running = false
            stopRequested = false
            Log.i(TAG, "app asr stopped")
        }
    }

    private fun doneError(onError: (String) -> Unit, message: String) {
        running = false
        main.post { onError(message) }
    }

    private fun ensureModel(context: Context): String? {
        synchronized(modelLock) {
            if (model != null) return null
            val dir = modelDir(context)
            val am = File(dir, "am")
            val conf = File(dir, "conf")
            if (!am.isDirectory && !conf.isDirectory) {
                return "没有语音模型，先推到眼镜"
            }
            return try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                model = Model(dir.absolutePath)
                null
            } catch (error: Exception) {
                Log.w(TAG, "load vosk model failed", error)
                "语音模型加载失败"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val size = bufferSize()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT,
        )
        for (source in sources) {
            try {
                val builder = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(size)
                if (Build.VERSION.SDK_INT >= 30) {
                    builder.setPrivacySensitive(false)
                }
                val record = builder.build()
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "app asr source=$source")
                    return record
                }
                record.release()
            } catch (error: Exception) {
                Log.w(TAG, "AudioRecord source=$source failed", error)
            }
        }
        return null
    }

    private fun bufferSize(): Int {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return min.coerceAtLeast(FRAME * 2) * 2
    }

    private fun rms(buffer: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (index in 0 until count) {
            val sample = buffer[index].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / count)
    }

    private fun parseText(json: String?): String {
        return parseField(json, "text")
    }

    private fun parsePartial(json: String?): String {
        return parseField(json, "partial")
    }

    private fun parseField(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString(key, "").replace("\\s+".toRegex(), "")
        } catch (_: Exception) {
            json.replace("\\s+".toRegex(), "")
        }
    }
}
