package com.glass.dining.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.sqrt

object GlassAsr {
    private const val TAG = "GlassDiningPhone"
    const val SAMPLE_RATE = 16_000
    private const val MAX_UTTER_MS = 8_000L
    private const val MIN_UTTER_MS = 1_200L
    private const val SILENCE_FRAMES = 10
    private const val ASSET_ZIP = "asr/vosk-model-small-cn-0.22.zip"

    var onPartial: ((String) -> Unit)? = null
    var onUtterance: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile var muted: Boolean = false

    private val main = Handler(Looper.getMainLooper())
    private val modelLock = Any()
    private val running = AtomicBoolean(false)
    @Volatile private var model: Model? = null
    @Volatile private var queue: LinkedBlockingQueue<ByteArray>? = null

    fun modelDir(context: Context): File {
        val candidates = listOfNotNull(
            File(context.filesDir, "asr/vosk-model-small-cn-0.22"),
            context.getExternalFilesDir(null)?.let { File(it, "asr/vosk-model-small-cn-0.22") },
        )
        return candidates.firstOrNull(::isModelDir) ?: candidates.first()
    }

    private fun isModelDir(dir: File): Boolean {
        return File(dir, "am").isDirectory && File(dir, "conf").isDirectory
    }

    fun preload(context: Context) {
        Thread({
            val error = ensureModel(context)
            if (error == null) {
                Log.i(TAG, "glass asr model ready")
            } else {
                Log.w(TAG, "glass asr preload: $error")
            }
        }, "glass-asr-preload").start()
    }

    fun start(context: Context): String? {
        val loadError = ensureModel(context)
        if (loadError != null) return loadError
        if (!running.compareAndSet(false, true)) return null
        val next = LinkedBlockingQueue<ByteArray>(48)
        queue = next
        Thread({ listenLoop(next) }, "glass-asr").start()
        Log.i(TAG, "glass asr started")
        return null
    }

    fun stop() {
        running.set(false)
        queue?.clear()
        queue = null
    }

    fun feed(pcm: ByteArray) {
        if (!running.get() || muted) return
        if (pcm.isEmpty()) return
        val copy = pcm.copyOf()
        val q = queue ?: return
        if (!q.offer(copy)) {
            q.poll()
            q.offer(copy)
        }
    }

    private fun listenLoop(incoming: LinkedBlockingQueue<ByteArray>) {
        var recognizer: Recognizer? = null
        try {
            val loaded = model ?: return
            recognizer = Recognizer(loaded, SAMPLE_RATE.toFloat())
            val rec = recognizer ?: return
            var noise = 80.0
            var inSpeech = false
            var silence = 0
            var speechStartedAt = 0L
            var bestPartial = ""
            var pending = ByteArray(0)
            while (running.get()) {
                val chunk = incoming.poll(80, TimeUnit.MILLISECONDS) ?: continue
                if (muted) {
                    if (inSpeech) {
                        inSpeech = false
                        rec.reset()
                    }
                    continue
                }
                pending = pending + chunk
                if (pending.size < 640) continue
                val even = pending.size - (pending.size % 2)
                val shorts = toShorts(pending, even)
                if (even < pending.size) {
                    pending = pending.copyOfRange(even, pending.size)
                } else {
                    pending = ByteArray(0)
                }
                val level = rms(shorts)
                val ttsPlaying = PhoneTts.speaking
                val threshold = if (ttsPlaying) {
                    max(noise * 2.4, 420.0)
                } else {
                    max(noise * 1.55, 180.0)
                }
                if (!inSpeech) {
                    if (level < 20) continue
                    if (!ttsPlaying) {
                        noise = noise * 0.94 + level * 0.06
                    }
                    if (level < threshold) continue
                    if (ttsPlaying) {
                        Log.i(TAG, "glass asr barge-in rms=${level.toInt()} thr=${threshold.toInt()}")
                        main.post { PhoneTts.stop() }
                    }
                    inSpeech = true
                    silence = 0
                    bestPartial = ""
                    speechStartedAt = System.currentTimeMillis()
                    rec.reset()
                    Log.i(TAG, "glass asr speech start rms=${level.toInt()} thr=${threshold.toInt()}")
                }
                rec.acceptWaveForm(shorts, shorts.size)
                val partial = parsePartial(rec.partialResult)
                if (partial.length > bestPartial.length) {
                    bestPartial = partial
                }
                if (partial.isNotBlank()) {
                    main.post { onPartial?.invoke(partial) }
                }
                if (level < threshold) {
                    silence += 1
                } else {
                    silence = 0
                }
                val elapsed = System.currentTimeMillis() - speechStartedAt
                val finished = elapsed >= MIN_UTTER_MS && silence >= SILENCE_FRAMES
                val tooLong = elapsed > MAX_UTTER_MS
                if (!tooLong && !finished) continue
                val finalText = parseText(rec.finalResult)
                val text = listOf(finalText, bestPartial, partial).maxBy { it.length }
                Log.i(TAG, "glass asr text=$text")
                inSpeech = false
                silence = 0
                bestPartial = ""
                if (text.isNotBlank()) {
                    muted = true
                    main.post { onUtterance?.invoke(text) }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "glass asr failed", error)
            main.post { onError?.invoke(error.message ?: "识别失败") }
        } finally {
            try {
                recognizer?.close()
            } catch (_: Exception) {
            }
            running.set(false)
            Log.i(TAG, "glass asr stopped")
        }
    }

    private fun ensureModel(context: Context): String? {
        synchronized(modelLock) {
            if (model != null) return null
            var dir = modelDir(context)
            if (!isModelDir(dir)) {
                val unpackError = unpackBundledModel(context)
                dir = modelDir(context)
                if (!isModelDir(dir)) {
                    Log.w(TAG, "glass asr missing model dir=${dir.absolutePath} unpack=$unpackError")
                    return "正在准备语音模型失败，请检查网络后重装应用。"
                }
            }
            Log.i(TAG, "glass asr using ${dir.absolutePath}")
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

    private fun unpackBundledModel(context: Context): String? {
        val destRoot = File(context.filesDir, "asr")
        destRoot.mkdirs()
        return try {
            context.assets.open(ASSET_ZIP).use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (name.contains("..")) {
                            zip.closeEntry()
                            continue
                        }
                        val out = File(destRoot, name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { dest -> zip.copyTo(dest) }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val unpacked = File(destRoot, "vosk-model-small-cn-0.22")
            if (isModelDir(unpacked)) {
                Log.i(TAG, "glass asr unpacked ${unpacked.absolutePath}")
                null
            } else {
                "bundled zip missing am/conf"
            }
        } catch (error: Exception) {
            Log.w(TAG, "unpack vosk failed", error)
            error.message ?: "unpack failed"
        }
    }

    private fun toShorts(bytes: ByteArray, length: Int): ShortArray {
        val count = length / 2
        val out = ShortArray(count)
        var index = 0
        var pos = 0
        while (index < count) {
            val lo = bytes[pos].toInt() and 0xFF
            val hi = bytes[pos + 1].toInt()
            out[index] = ((hi shl 8) or lo).toShort()
            index += 1
            pos += 2
        }
        return out
    }

    private fun rms(buffer: ShortArray): Double {
        if (buffer.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in buffer) {
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / buffer.size)
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
