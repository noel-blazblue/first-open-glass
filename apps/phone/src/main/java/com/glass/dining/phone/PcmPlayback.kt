package com.glass.dining.phone

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max

/** 单会话 PCM 播放。WebSocket 完成不等于播放完成，须 drain。 */
class PcmPlayback(
    private val sampleRate: Int,
    private val generation: Int,
    private val currentGen: () -> Int,
) {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var writtenFrames: Int = 0
    @Volatile var firstFrame: Boolean = false
        private set

    fun write(pcm: ByteArray): Boolean {
        if (pcm.isEmpty() || stale()) return false
        val audio = ensureTrack() ?: return false
        var offset = 0
        while (offset < pcm.size && !stale()) {
            val written = audio.write(pcm, offset, pcm.size - offset)
            if (written <= 0) break
            offset += written
            writtenFrames += written / 2
            if (!firstFrame && written > 0) firstFrame = true
        }
        return offset > 0
    }

    fun playedMs(): Long {
        val audio = track ?: return 0L
        return audio.playbackHeadPosition.toLong() * 1000L / sampleRate.coerceAtLeast(1)
    }

    fun playedFrames(): Int = track?.playbackHeadPosition ?: 0

    fun drain() {
        val audio = track ?: return
        while (!stale() && audio.playState == AudioTrack.PLAYSTATE_PLAYING) {
            if (audio.playbackHeadPosition >= writtenFrames) break
            Thread.sleep(20)
        }
    }

    fun release() {
        val audio = track
        track = null
        if (audio == null) return
        try {
            audio.pause()
            audio.flush()
            audio.stop()
            audio.release()
        } catch (_: Exception) {
        }
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        if (stale()) return null
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val audio = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audio.play()
        track = audio
        return audio
    }

    private fun stale(): Boolean = generation != currentGen()
}
