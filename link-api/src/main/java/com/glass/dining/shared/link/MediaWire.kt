package com.glass.dining.shared.link

import org.json.JSONObject
import kotlin.math.max

object MediaWire {
    const val PCM_SAMPLE_RATE = 16_000
    const val FRAME_INTERVAL_MS = 5_000L
    const val JPEG_WIDTH = 1280
    const val JPEG_HEIGHT = 720
    const val JPEG_QUALITY = 72
    const val SDP_CHUNK = 2400
    const val RTC_WIDTH = 1280
    const val RTC_HEIGHT = 720
    const val RTC_FPS = 15
    const val RTC_MAX_BITRATE = 2_500_000
    const val RTC_MIN_BITRATE = 1_200_000
    const val POSE_WIFI_TICK_MS = 200L

    fun rtcSdpChunks(type: String, sdp: String, size: Int = SDP_CHUNK): List<String> {
        val total = max(1, (sdp.length + size - 1) / size)
        return (0 until total).map { index ->
            val start = index * size
            val end = kotlin.math.min(sdp.length, start + size)
            JSONObject()
                .put("t", type)
                .put("i", index)
                .put("n", total)
                .put("p", sdp.substring(start, end))
                .toString()
        }
    }

    fun rtcIceJson(mid: String?, index: Int, candidate: String): String {
        return JSONObject()
            .put("mid", mid ?: "")
            .put("index", index)
            .put("candidate", candidate)
            .toString()
    }

    fun parseRtcIce(raw: String?): Triple<String, Int, String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            Triple(
                obj.optString("mid"),
                obj.optInt("index", 0),
                obj.optString("candidate"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun p2pOfferJson(offer: P2pOffer): String {
        return JSONObject()
            .put("ssid", offer.ssid)
            .put("pass", offer.passphrase)
            .put("goIp", offer.goIp)
            .put("mac", offer.goMac)
            .put("name", offer.goName)
            .put("attemptId", offer.attemptId)
            .toString()
    }

    fun parseP2pOffer(raw: String?): P2pOffer? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            val ssid = obj.optString("ssid")
            val pass = obj.optString("pass")
            if (ssid.isBlank() || pass.isBlank()) {
                null
            } else {
                P2pOffer(
                    ssid = ssid,
                    passphrase = pass,
                    goIp = obj.optString("goIp"),
                    goMac = obj.optString("mac"),
                    goName = obj.optString("name"),
                    attemptId = obj.optString("attemptId"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun p2pReadyJson(ip: String, attemptId: String = ""): String {
        return JSONObject()
            .put("ip", ip)
            .put("attemptId", attemptId)
            .toString()
    }

    fun parseP2pReady(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            JSONObject(raw).optString("ip")
        } catch (_: Exception) {
            ""
        }
    }

    fun parseP2pAttemptId(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            JSONObject(raw).optString("attemptId")
        } catch (_: Exception) {
            ""
        }
    }

    fun p2pFailJson(reason: String, attemptId: String = ""): String {
        return JSONObject()
            .put("reason", reason)
            .put("attemptId", attemptId)
            .toString()
    }

    fun parseP2pFail(raw: String?): Pair<String, String> {
        if (raw.isNullOrBlank()) return "" to ""
        return try {
            val obj = JSONObject(raw)
            val reason = obj.optString("reason").ifBlank { raw }
            reason to obj.optString("attemptId")
        } catch (_: Exception) {
            raw to ""
        }
    }

    class SdpAssembler {
        private var type: String = ""
        private var total: Int = 0
        private var parts: Array<String?> = emptyArray()

        @Synchronized
        fun push(raw: String?): Pair<String, String>? {
            if (raw.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(raw)
                val nextType = obj.getString("t")
                val index = obj.getInt("i")
                val nextTotal = obj.getInt("n")
                val piece = obj.getString("p")
                if (nextType != type || nextTotal != total) {
                    type = nextType
                    total = nextTotal
                    parts = arrayOfNulls(nextTotal)
                }
                if (index !in 0 until total) return null
                parts[index] = piece
                if (parts.any { it == null }) return null
                val sdp = parts.joinToString("")
                type = ""
                total = 0
                parts = emptyArray()
                nextType to sdp
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class P2pOffer(
    val ssid: String,
    val passphrase: String,
    val goIp: String = "",
    val goMac: String = "",
    val goName: String = "",
    val attemptId: String = "",
)
