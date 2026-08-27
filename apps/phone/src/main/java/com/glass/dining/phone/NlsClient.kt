package com.glass.dining.phone

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云智能语音交互（NLS）一句话识别 + 语音合成。
 *
 * Token：HTTPS POP CreateToken，文档
 * https://help.aliyun.com/zh/isi/getting-started/use-http-or-https-to-obtain-an-access-token
 * ASR：POST /stream/v1/asr ，PCM 16 kHz 单声道
 * TTS：POST /stream/v1/tts ，JSON 文本，返回 PCM
 */
object NlsClient {
    private const val TAG = "GlassDiningPhone"
    private const val TOKEN_HOST = "https://nls-meta.cn-shanghai.aliyuncs.com/"
    private const val DEFAULT_GATEWAY = "https://nls-gateway-cn-shanghai.aliyuncs.com"
    private const val REGION = "cn-shanghai"
    private const val API_VERSION = "2019-02-28"
    private const val SUCCESS = 20000000
    private const val TOKEN_SKEW_SEC = 300L
    private const val TTS_MAX_CHARS = 280
    private val utcStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneOffset.UTC)

    @Volatile var akId: String = ""
        private set
    @Volatile var appkey: String = ""
        private set
    @Volatile private var akSecret: String = ""
    @Volatile private var staticToken: String = ""
    @Volatile var gateway: String = DEFAULT_GATEWAY
        private set
    @Volatile var voice: String = "ruoxi"
        private set
    @Volatile var sampleRate: Int = 24_000
        private set
    @Volatile var volume: Int = 50
        private set

    @Volatile private var token: String = ""
    @Volatile private var tokenExpireSec: Long = 0
    private val tokenLock = Any()

    val ready: Boolean
        get() = appkey.isNotBlank() && (
            staticToken.isNotBlank() || (akId.isNotBlank() && akSecret.isNotBlank())
        )

    fun currentToken(): String = token

    fun websocketUrl(): String {
        val host = gateway.trimEnd('/')
        val scheme = when {
            host.startsWith("https://") -> "wss://" + host.removePrefix("https://")
            host.startsWith("http://") -> "ws://" + host.removePrefix("http://")
            else -> "wss://$host"
        }
        return "$scheme/ws/v1"
    }

    val statusLine: String
        get() = if (ready) "阿里云NLS" else "未配置阿里云语音"

    fun applyEnv(raw: String) {
        for (line in raw.lineSequence()) {
            val stripped = line.trim()
            if (stripped.isEmpty() || stripped.startsWith("#") || "=" !in stripped) continue
            val key = stripped.substringBefore("=").trim()
            val value = stripped.substringAfter("=").trim().trim('"').trim('\'')
            if (value.isBlank()) continue
            when (key) {
                "ALIYUN_AK_ID", "ALIYUN_ACCESS_KEY_ID" -> akId = value
                "ALIYUN_AK_SECRET", "ALIYUN_ACCESS_KEY_SECRET" -> akSecret = value
                "ALIYUN_NLS_APPKEY", "NLS_APPKEY", "ALIYUN_APPKEY" -> appkey = value
                "ALIYUN_NLS_TOKEN", "NLS_TOKEN" -> staticToken = value
                "ALIYUN_NLS_GATEWAY" -> gateway = value.trimEnd('/')
                "ALIYUN_NLS_VOICE" -> voice = value
                "ALIYUN_NLS_SAMPLE_RATE" -> sampleRate = value.toIntOrNull() ?: sampleRate
                "ALIYUN_NLS_VOLUME" -> volume = value.toIntOrNull()?.coerceIn(0, 100) ?: volume
            }
        }
        if (ready) {
            Log.i(TAG, "nls ready appkey=${appkey.take(4)}*** gateway=$gateway voice=$voice rate=$sampleRate")
        }
    }

    fun ensureToken(): String? {
        if (!ready) {
            return "未配置阿里云语音。在 ai.env 写 ALIYUN_NLS_APPKEY，以及 ALIYUN_AK_ID/ALIYUN_AK_SECRET，或控制台临时 ALIYUN_NLS_TOKEN。"
        }
        synchronized(tokenLock) {
            if (staticToken.isNotBlank()) {
                token = staticToken
                tokenExpireSec = Instant.now().epochSecond + 24 * 3600
                return null
            }
            val now = Instant.now().epochSecond
            if (token.isNotBlank() && now < tokenExpireSec - TOKEN_SKEW_SEC) return null
            return refreshTokenLocked()
        }
    }

    fun recognize(pcm: ByteArray): String? {
        if (pcm.isEmpty()) return null
        val authError = ensureToken()
        if (authError != null) {
            Log.w(TAG, "nls asr token: $authError")
            return null
        }
        val query = listOf(
            "appkey" to appkey,
            "format" to "pcm",
            "sample_rate" to "16000",
            "enable_punctuation_prediction" to "true",
            "enable_inverse_text_normalization" to "true",
            "enable_voice_detection" to "true",
        ).joinToString("&") { (k, v) -> "${percentEncode(k)}=${percentEncode(v)}" }
        val url = "$gateway/stream/v1/asr?$query"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("X-NLS-Token", token)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Host", URL(gateway).host)
            setFixedLengthStreamingMode(pcm.size)
        }
        return try {
            connection.outputStream.use { it.write(pcm) }
            val code = connection.responseCode
            val body = readBody(connection)
            val json = JSONObject(body.ifBlank { "{}" })
            val status = json.optInt("status")
            if (code !in 200..299 || status != SUCCESS) {
                Log.w(TAG, "nls asr http=$code status=$status msg=${json.optString("message").take(120)}")
                return null
            }
            json.optString("result").replace("\\s+".toRegex(), "").ifBlank { null }
        } catch (error: Exception) {
            Log.w(TAG, "nls asr ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    fun synthesize(text: String): ByteArray? {
        val spoken = text.trim()
        if (spoken.isBlank()) return null
        val authError = ensureToken()
        if (authError != null) {
            Log.w(TAG, "nls tts token: $authError")
            return null
        }
        val payload = JSONObject()
            .put("appkey", appkey)
            .put("text", spoken.take(TTS_MAX_CHARS))
            .put("token", token)
            .put("format", "pcm")
            .put("sample_rate", sampleRate)
            .put("voice", voice)
            .put("volume", volume)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val url = "$gateway/stream/v1/tts"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-NLS-Token", token)
            setRequestProperty("Host", URL(gateway).host)
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val type = connection.contentType.orEmpty()
            val data = readBytes(connection)
            if (code !in 200..299 || type.contains("json", ignoreCase = true)) {
                val message = try {
                    JSONObject(String(data, Charsets.UTF_8)).optString("message")
                } catch (_: Exception) {
                    String(data, Charsets.UTF_8).take(120)
                }
                Log.w(TAG, "nls tts http=$code type=$type msg=${message.take(120)}")
                return null
            }
            if (data.isEmpty()) null else data
        } catch (error: Exception) {
            Log.w(TAG, "nls tts ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun refreshTokenLocked(): String? {
        val params = TreeMap<String, String>()
        params["AccessKeyId"] = akId
        params["Action"] = "CreateToken"
        params["Format"] = "JSON"
        params["RegionId"] = REGION
        params["SignatureMethod"] = "HMAC-SHA1"
        params["SignatureNonce"] = UUID.randomUUID().toString()
        params["SignatureVersion"] = "1.0"
        params["Timestamp"] = utcStamp.format(Instant.now())
        params["Version"] = API_VERSION
        val query = canonicalQuery(params)
        val stringToSign = "GET&${percentEncode("/")}&${percentEncode(query)}"
        val signature = percentEncode(hmacSha1(stringToSign, "$akSecret&"))
        val url = "$TOKEN_HOST?Signature=$signature&$query"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            val body = readBody(connection)
            val json = try {
                JSONObject(body.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject()
            }
            val errMsg = json.optString("ErrMsg").ifBlank { json.optString("Message") }
            val errCode = json.opt("ErrCode")?.toString().orEmpty().ifBlank { json.optString("Code") }
            if (code !in 200..299) {
                Log.w(TAG, "nls token http=$code code=$errCode msg=${errMsg.take(120)}")
                return "阿里云 Token 获取失败：$errCode ${errMsg.take(40)}".trim()
            }
            val obj = json.optJSONObject("Token")
            val id = obj?.optString("Id").orEmpty().ifBlank { obj?.optString("id").orEmpty() }
            val expire = obj?.optLong("ExpireTime") ?: 0L
            if (id.isBlank()) {
                Log.w(TAG, "nls token missing id code=$errCode msg=${errMsg.take(120)}")
                return nlsPermissionHint(errMsg.ifBlank { errCode.ifBlank { "无 Token" } })
            }
            token = id
            tokenExpireSec = if (expire > 0L) expire else Instant.now().epochSecond + 36 * 3600
            Log.i(TAG, "nls token ok expire=$tokenExpireSec")
            null
        } catch (error: Exception) {
            Log.w(TAG, "nls token ${error.javaClass.simpleName}: ${error.message}")
            "阿里云 Token 获取失败"
        } finally {
            connection.disconnect()
        }
    }

    private fun nlsPermissionHint(detail: String): String {
        val lower = detail.lowercase()
        return if (lower.contains("permission") || lower.contains("denied") || detail.contains("权限")) {
            "阿里云无 CreateToken 权限（$detail）。给该 AccessKey 的 RAM 用户授予 AliyunNLSFullAccess，并确认已开通智能语音交互。"
        } else {
            "阿里云 Token 失败：$detail"
        }
    }

    private fun canonicalQuery(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${percentEncode(k)}=${percentEncode(v)}"
        }
    }

    private fun percentEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }

    private fun hmacSha1(source: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val raw = mac.doFinal(source.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
    }

    private fun readBytes(connection: HttpURLConnection): ByteArray {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return stream?.readBytes() ?: ByteArray(0)
    }
}
