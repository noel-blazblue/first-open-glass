package com.glass.dining.shared.link

import org.json.JSONArray
import org.json.JSONObject

data class HudDrawOp(
    val type: String,
    val d: String = "",
    val stroke: Float = 2f,
    val gray: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val size: Float = 18f,
    val align: String = "c",
    val text: String = "",
)

data class HudDrawScene(
    val seq: Long,
    val ops: List<HudDrawOp>,
)

sealed class HudPathSeg {
    data class Move(val x: Float, val y: Float) : HudPathSeg()
    data class Line(val x: Float, val y: Float) : HudPathSeg()
    data class Cubic(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val x: Float,
        val y: Float,
    ) : HudPathSeg()
    data class Quad(val x1: Float, val y1: Float, val x: Float, val y: Float) : HudPathSeg()
    data object Close : HudPathSeg()
}

object HudDraw {
    const val WIDTH = 480f
    const val HEIGHT = 640f
    const val MAX_OPS = 32
    const val MAX_PATH = 400
    const val MAX_TEXT = 40
    const val MAX_JSON = 2048

    fun encode(scene: HudDrawScene): String {
        val ops = JSONArray()
        scene.ops.forEach { op ->
            val item = JSONObject().put("t", op.type).put("g", op.gray.toDouble())
            if (op.type == "path") {
                item.put("d", op.d).put("w", op.stroke.toDouble())
            } else {
                item.put("x", op.x.toDouble())
                    .put("y", op.y.toDouble())
                    .put("s", op.size.toDouble())
                    .put("a", op.align)
                    .put("v", op.text)
            }
            ops.put(item)
        }
        return JSONObject().put("seq", scene.seq).put("ops", ops).toString()
    }

    fun parse(raw: String?): HudDrawScene? {
        if (raw.isNullOrBlank() || raw.length > MAX_JSON) return null
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("ops") ?: return null
            val kept = ArrayList<HudDrawOp>(arr.length().coerceAtMost(MAX_OPS))
            for (i in 0 until arr.length()) {
                if (kept.size >= MAX_OPS) break
                val item = arr.optJSONObject(i) ?: continue
                sanitize(item)?.let(kept::add)
            }
            if (kept.none { it.type == "path" }) return null
            HudDrawScene(seq = obj.optLong("seq"), ops = kept)
        } catch (_: Exception) {
            null
        }
    }

    fun parsePath(raw: String): List<HudPathSeg> {
        if (raw.isBlank() || raw.length > MAX_PATH) return emptyList()
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return emptyList()
        val out = ArrayList<HudPathSeg>()
        var i = 0
        var cmd = ""
        var x = 0f
        var y = 0f
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.length == 1 && token[0].isLetter()) {
                cmd = token
                i++
            }
            if (cmd.isEmpty()) return emptyList()
            when (cmd) {
                "M", "m" -> {
                    val n = nums(tokens, i, 2) ?: return emptyList()
                    x = clampX(n[0])
                    y = clampY(n[1])
                    out.add(HudPathSeg.Move(x, y))
                    i += 2
                }
                "L", "l" -> {
                    val n = nums(tokens, i, 2) ?: return emptyList()
                    x = clampX(n[0])
                    y = clampY(n[1])
                    out.add(HudPathSeg.Line(x, y))
                    i += 2
                }
                "H", "h" -> {
                    val n = nums(tokens, i, 1) ?: return emptyList()
                    x = clampX(n[0])
                    out.add(HudPathSeg.Line(x, y))
                    i += 1
                }
                "V", "v" -> {
                    val n = nums(tokens, i, 1) ?: return emptyList()
                    y = clampY(n[0])
                    out.add(HudPathSeg.Line(x, y))
                    i += 1
                }
                "C", "c" -> {
                    val n = nums(tokens, i, 6) ?: return emptyList()
                    x = clampX(n[4])
                    y = clampY(n[5])
                    out.add(
                        HudPathSeg.Cubic(
                            clampX(n[0]), clampY(n[1]),
                            clampX(n[2]), clampY(n[3]),
                            x, y,
                        ),
                    )
                    i += 6
                }
                "Q", "q" -> {
                    val n = nums(tokens, i, 4) ?: return emptyList()
                    x = clampX(n[2])
                    y = clampY(n[3])
                    out.add(HudPathSeg.Quad(clampX(n[0]), clampY(n[1]), x, y))
                    i += 4
                }
                "Z", "z" -> {
                    out.add(HudPathSeg.Close)
                }
                else -> return emptyList()
            }
        }
        return out
    }

    private fun sanitize(item: JSONObject): HudDrawOp? {
        val type = item.optString("t")
        val gray = item.optDouble("g", 1.0).toFloat().coerceIn(0f, 1f)
        return when (type) {
            "path" -> {
                val d = item.optString("d").take(MAX_PATH)
                if (parsePath(d).isEmpty()) return null
                HudDrawOp(
                    type = "path",
                    d = d,
                    stroke = item.optDouble("w", 2.0).toFloat().coerceIn(0.5f, 16f),
                    gray = gray,
                )
            }
            "text" -> {
                val text = item.optString("v").replace(Regex("[\\r\\n]+"), "").trim().take(MAX_TEXT)
                if (text.isBlank()) return null
                val align = item.optString("a", "c").lowercase().take(1)
                HudDrawOp(
                    type = "text",
                    gray = gray,
                    x = clampX(item.optDouble("x").toFloat()),
                    y = clampY(item.optDouble("y").toFloat()),
                    size = item.optDouble("s", 18.0).toFloat().coerceIn(10f, 40f),
                    align = if (align == "l" || align == "r") align else "c",
                    text = text,
                )
            }
            else -> null
        }
    }

    private fun tokenize(raw: String): List<String> {
        return raw.replace(",", " ")
            .replace(Regex("([A-Za-z])"), " $1 ")
            .replace(Regex("(?<=[0-9.])-(?=[0-9.])"), " -")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }

    private fun nums(tokens: List<String>, start: Int, count: Int): FloatArray? {
        if (start + count > tokens.size) return null
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = tokens[start + i].toFloatOrNull() ?: return null
        }
        return out
    }

    private fun clampX(value: Float): Float = value.coerceIn(0f, WIDTH)
    private fun clampY(value: Float): Float = value.coerceIn(0f, HEIGHT)
}
