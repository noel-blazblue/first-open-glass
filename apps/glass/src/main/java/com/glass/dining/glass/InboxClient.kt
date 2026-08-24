package com.glass.dining.glass

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Posts raw ASR to the AI host and reads HUD / command / speech back. */
object InboxClient {
    private const val TAG = "GlassDining"
    private const val DEFAULT_URL = "http://192.168.0.122:18765/utterance"

    data class Reply(
        val hud: String,
        val cmd: String,
        val question: String,
        val speak: String,
        val audioUrl: String,
    )

    fun send(context: Context, text: String, onReply: (Reply?) -> Unit) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        Thread({
            try {
                val photo = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "look.jpg",
                )
                val body = JSONObject()
                    .put("text", spoken)
                    .put("ts", System.currentTimeMillis())
                    .put("hasPhoto", photo.exists() && photo.length() > 0)
                val endpoint = inboxUrl(context)
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 2_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.doInput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { stream ->
                    stream.write(body.toString().toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.readText()
                    .orEmpty()
                connection.disconnect()
                Log.i(TAG, "inbox posted $code text=$spoken")
                onReply(parseReply(raw, endpoint))
            } catch (error: Exception) {
                Log.w(TAG, "inbox post failed", error)
                onReply(null)
            }
        }, "inbox-post").start()
    }

    private fun parseReply(raw: String, endpoint: String): Reply? {
        if (raw.isBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val base = endpoint.substringBeforeLast("/")
            val audio = obj.optBoolean("audio")
            Reply(
                hud = obj.optString("hud"),
                cmd = obj.optString("cmd"),
                question = obj.optString("question"),
                speak = obj.optString("speak"),
                audioUrl = if (audio) "$base/tts.wav?t=${System.currentTimeMillis()}" else "",
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun inboxUrl(context: Context): String {
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "inbox-url.txt")
        val fromFile = if (file.exists()) file.readText().trim() else ""
        return fromFile.ifBlank { DEFAULT_URL }
    }
}
