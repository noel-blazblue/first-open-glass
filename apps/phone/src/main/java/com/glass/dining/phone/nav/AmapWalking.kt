package com.glass.dining.phone.nav

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class WalkStep(
    val instruction: String,
    val distance: Int,
    val turn: String,
    val points: List<GeoPoint>,
)

data class WalkRoute(
    val storeName: String,
    val destination: GeoPoint,
    val distance: Int,
    val steps: List<WalkStep>,
    val points: List<GeoPoint>,
)

object AmapWalking {
    private const val TAG = "GlassDiningPhone"
    private const val ENDPOINT = "https://restapi.amap.com/v3/direction/walking"

    fun fetch(origin: GeoPoint, dest: GeoPoint, storeName: String, key: String): WalkRoute? {
        if (key.isBlank()) return null
        val originStr = fmt(origin)
        val destStr = fmt(dest)
        val url = "$ENDPOINT?origin=${enc(originStr)}&destination=${enc(destStr)}&key=${enc(key)}"
        val raw = get(url) ?: return null
        return try {
            parse(raw, dest, storeName)
        } catch (error: Exception) {
            Log.w(TAG, "amap parse ${error.message}")
            null
        }
    }

    private fun parse(raw: String, dest: GeoPoint, storeName: String): WalkRoute? {
        val json = JSONObject(raw)
        if (json.optString("status") != "1") {
            Log.w(TAG, "amap walking status=${json.optString("status")} info=${json.optString("info")}")
            return null
        }
        val path = json.optJSONObject("route")
            ?.optJSONArray("paths")
            ?.optJSONObject(0)
            ?: return null
        val stepsJson = path.optJSONArray("steps") ?: return null
        val steps = ArrayList<WalkStep>()
        val points = ArrayList<GeoPoint>()
        for (i in 0 until stepsJson.length()) {
            val step = stepsJson.optJSONObject(i) ?: continue
            val instruction = step.optString("instruction")
            val pts = parsePolyline(step.optString("polyline"))
            if (pts.isEmpty()) continue
            if (points.isNotEmpty() && pts.first() == points.last()) {
                points.addAll(pts.drop(1))
            } else {
                points.addAll(pts)
            }
            steps.add(
                WalkStep(
                    instruction = instruction,
                    distance = step.optString("distance").toIntOrNull() ?: 0,
                    turn = turnOf(instruction),
                    points = pts,
                ),
            )
        }
        if (points.size < 2 || steps.isEmpty()) return null
        val distance = path.optString("distance").toIntOrNull()
            ?: Geo.haversineMeters(points.first(), dest).toInt()
        return WalkRoute(
            storeName = storeName,
            destination = dest,
            distance = distance,
            steps = steps,
            points = points,
        )
    }

    fun turnOf(instruction: String): String {
        return when {
            instruction.contains("到达") || instruction.contains("目的地") -> "arrive"
            instruction.contains("左转") || instruction.contains("向左") -> "left"
            instruction.contains("右转") || instruction.contains("向右") -> "right"
            else -> "straight"
        }
    }

    private fun parsePolyline(raw: String): List<GeoPoint> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { pair ->
            val parts = pair.split(",")
            if (parts.size < 2) return@mapNotNull null
            val lng = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            GeoPoint(lat, lng)
        }
    }

    private fun fmt(p: GeoPoint): String {
        return String.format(Locale.US, "%.6f,%.6f", p.lng, p.lat)
    }

    private fun enc(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

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
                Log.w(TAG, "amap http=$code body=${text.take(160)}")
                return null
            }
            text
        } catch (error: Exception) {
            Log.w(TAG, "amap ${error.javaClass.simpleName}: ${error.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
