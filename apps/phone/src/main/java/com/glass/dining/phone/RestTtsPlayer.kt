package com.glass.dining.phone

import android.util.Log
import com.glass.dining.shared.agent.TtsJobQueue
import com.glass.dining.shared.agent.TtsRouter
import com.glass.dining.shared.agent.TtsTimeline
import java.util.concurrent.atomic.AtomicBoolean

/**
 * REST 句级 TTS：最多 2 段并行合成，严格按序播放。
 * 旧 generation 的 HTTP 返回不能复活播放。
 */
class RestTtsPlayer(
    private val currentGen: () -> Int,
    private val onAudible: (Int, String) -> Unit,
    private val onProgress: (Int, String) -> Unit,
    private val onFinished: (Int) -> Unit,
    private val onFailed: (Int, String) -> Unit = { _, _ -> },
    private val synthesize: (String) -> ByteArray? = { NlsClient.synthesize(it) },
) {
    private val queue = TtsJobQueue(maxPrefetch = 2)
    private val running = AtomicBoolean(false)
    @Volatile private var playback: PcmPlayback? = null
    @Volatile private var playedAny: Boolean = false
    @Volatile private var hadFailure: Boolean = false
    private val lock = Any()
    private val heard = StringBuilder()

    fun enqueue(text: String, generation: Int) {
        if (text.isBlank() || generation != currentGen()) return
        synchronized(lock) { queue.enqueue(text) }
        startWorker(generation)
    }

    fun remainingText(): String = synchronized(lock) { queue.unplayedText() }

    fun stop() {
        playback?.release()
        playback = null
        synchronized(lock) {
            queue.clear()
            heard.clear()
        }
        playedAny = false
        hadFailure = false
        running.set(false)
    }

    private fun startWorker(generation: Int) {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                loop(generation)
            } finally {
                running.set(false)
                if (generation == currentGen()) {
                    val more = synchronized(lock) { queue.pendingPlay > 0 && !queue.finished() }
                    if (more && generation == currentGen()) startWorker(generation)
                    else if (generation == currentGen()) {
                        if (!playedAny && hadFailure) onFailed(generation, "rest tts empty")
                        else onFinished(generation)
                    }
                }
            }
        }, "nls-tts-rest").start()
    }

    private fun loop(generation: Int) {
        while (generation == currentGen()) {
            val toSynth = synchronized(lock) { queue.toSynthesize() }
            toSynth.forEach { job ->
                job.synthesizing = true
                Thread({
                    var pcm: ByteArray? = null
                    var attempt = 0
                    while (attempt < TtsRouter.REST_SYNTH_ATTEMPTS && generation == currentGen()) {
                        pcm = synthesize(job.text)
                        if (TtsRouter.acceptPcm(pcm)) break
                        attempt += 1
                    }
                    synchronized(lock) {
                        if (generation != currentGen()) return@synchronized
                        if (!TtsRouter.acceptPcm(pcm)) job.failed = true else job.pcm = pcm
                    }
                }, "nls-tts-synth-${job.index}").start()
            }
            val playable = synchronized(lock) { queue.nextPlayable() }
            if (playable == null) {
                val pending = synchronized(lock) { queue.pendingPlay }
                if (pending == 0) break
                Thread.sleep(30)
                continue
            }
            if (playable.failed) {
                hadFailure = true
                Log.w(TAG, "nls rest tts empty index=${playable.index} chars=${playable.text.length}")
                synchronized(lock) { queue.markPlayed(playable.index) }
                continue
            }
            val pcm = playable.pcm ?: continue
            play(playable.text, pcm, generation)
            playedAny = true
            heard.append(playable.text)
            synchronized(lock) { queue.markPlayed(playable.index) }
        }
    }

    private fun play(text: String, pcm: ByteArray, generation: Int) {
        if (generation != currentGen()) return
        val track = playback ?: PcmPlayback(NlsClient.sampleRate, generation, currentGen).also { playback = it }
        val totalFrames = pcm.size / 2
        var offset = 0
        val chunk = 4096
        val prefix = heard.toString()
        val startHead = track.playedFrames()
        while (offset < pcm.size && generation == currentGen()) {
            val end = minOf(offset + chunk, pcm.size)
            val slice = pcm.copyOfRange(offset, end)
            val ok = track.write(slice)
            if (ok && track.firstFrame) onAudible(generation, prefix + TtsTimeline.estimatePrefix(1, totalFrames, text).ifBlank { text.take(1) })
            val played = (track.playedFrames() - startHead).coerceAtLeast(0)
            onProgress(generation, prefix + TtsTimeline.estimatePrefix(played, totalFrames, text))
            offset = end
        }
        track.drain()
        onProgress(generation, prefix + text)
    }

    companion object {
        private const val TAG = "GlassDiningPhone"
    }
}
