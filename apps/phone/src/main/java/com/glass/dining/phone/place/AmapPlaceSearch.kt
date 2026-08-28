package com.glass.dining.phone.place

import android.util.Log
import com.glass.dining.phone.nav.GeoPoint
import com.glass.dining.shared.place.AmapPlaceJson
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object AmapPlaceSearch {
    private const val TAG = "GlassDiningPhone"
    private const val ENDPOINT = "https://restapi.amap.com/v5/place/around"

    fun around(
        origin: GeoPoint,
        keyword: String,
        key: String,
        radius: Int = 3_000,
    ): AmapPlaceJson.AroundResult {
        if (key.isBlank()) {
            return AmapPlaceJson.AroundResult(false, error = "no_key", message = "没配地点搜索密钥，在 ai.env 写 AMAP_WEB_KEY")
        }
        val loc = String.format(Locale.US, "%.6f,%.6f", origin.lng, origin.lat)
        val url = "$ENDPOINT?key=${enc(key)}&location=${enc(loc)}&keywords=${enc(keyword)}" +
            "&radius=$radius&sortrule=distance&page_size=10&page_num=1" +
            "&show_fields=${enc("business,indoor")}"
        val raw = get(url) ?: return AmapPlaceJson.AroundResult(
            false,
            error = "amap_error",
            message = "附近搜索没有返回",
        )
        return AmapPlaceJson.parseAround(raw)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "amap place http=$code body=${text.take(160)}")
                return text.ifBlank { null }
            }
            text
        } catch (error: Exception) {
            Log.w(TAG, "amap place ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
