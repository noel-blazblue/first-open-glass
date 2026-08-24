package com.glass.dining.glass

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

object AppTts {
    private const val TAG = "GlassDining"

    @Volatile
    private var player: MediaPlayer? = null

    fun play(url: String, onDone: () -> Unit) {
        stop()
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        media.setOnCompletionListener {
            Log.i(TAG, "app tts done")
            stop()
            onDone()
        }
        media.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "app tts error what=$what extra=$extra")
            stop()
            onDone()
            true
        }
        try {
            media.setDataSource(url)
            media.setOnPreparedListener { prepared ->
                Log.i(TAG, "app tts play $url")
                prepared.start()
            }
            media.prepareAsync()
        } catch (error: Exception) {
            Log.w(TAG, "app tts failed", error)
            stop()
            onDone()
        }
    }

    fun stop() {
        val media = player
        player = null
        if (media == null) return
        try {
            media.stop()
        } catch (_: Exception) {
        }
        try {
            media.release()
        } catch (_: Exception) {
        }
    }
}
