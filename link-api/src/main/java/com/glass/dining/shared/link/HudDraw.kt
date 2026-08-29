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
    val radius: Float = 0f,
    val boxW: Float = 0f,
    val boxH: Float = 0f,
    val kind: String = "s",
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
    const val SAFE_LEFT = 64f
    const val SAFE_RIGHT = 416f
    const val SAFE_TOP = 56f
    const val SAFE_BOTTOM = 552f
    const val MAX_OPS = 48
    const val MAX_PATH = 600
    const val MAX_TEXT = 40
    const val MAX_JSON = 2400

    fun encode(scene: HudDrawScene): String {
        val ops = JSONArray()
        scene.ops.forEach { op ->
            val item = JSONObject()
                .put("t", op.type)
                .put("g", op.gray.toDouble())
                .put("w", op.stroke.toDouble())
                .put("k", op.kind)
            when (op.type) {
                "path" -> item.put("d", op.d)
                "text" -> item
                    .put("x", op.x.toDouble())
                    .put("y", op.y.toDouble())
                    .put("s", op.size.toDouble())
                    .put("a", op.align)
                    .put("v", op.text)
                "circle" -> item
                    .put("x", op.x.toDouble())
                    .put("y", op.y.toDouble())
                    .put("r", op.radius.toDouble())
                "rect" -> item
                    .put("x", op.x.toDouble())
                    .put("y", op.y.toDouble())
                    .put("rw", op.boxW.toDouble())
                    .put("rh", op.boxH.toDouble())
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
            if (kept.none { it.type != "text" }) return null
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
        var x = SAFE_LEFT
        var y = SAFE_TOP
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
                "Z", "z" -> out.add(HudPathSeg.Close)
                else -> return emptyList()
            }
        }
        return out
    }

    private fun sanitize(item: JSONObject): HudDrawOp? {
        val type = item.optString("t")
        val gray = item.optDouble("g", 1.0).toFloat().coerceIn(0f, 1f)
        val stroke = item.optDouble("w", 2.0).toFloat().coerceIn(0.5f, 16f)
        val kind = kindOf(item.optString("k", "s"))
        return when (type) {
            "path" -> {
                val d = item.optString("d").take(MAX_PATH)
                if (parsePath(d).isEmpty()) return null
                HudDrawOp(type = "path", d = d, stroke = stroke, gray = gray, kind = kind)
            }
            "text" -> {
                val text = item.optString("v").replace(Regex("[\\r\\n]+"), "").trim().take(MAX_TEXT)
                if (text.isBlank()) return null
                val align = item.optString("a", "c").lowercase().take(1).let {
                    if (it == "l" || it == "r") it else "c"
                }
                val size = item.optDouble("s", 18.0).toFloat().coerceIn(10f, 36f)
                HudDrawOp(
                    type = "text",
                    gray = gray,
                    x = clampTextX(item.optDouble("x").toFloat(), align, text, size),
                    y = clampY(item.optDouble("y").toFloat()),
                    size = size,
                    align = align,
                    text = text,
                )
            }
            "circle" -> {
                if (!item.has("r")) return null
                var r = item.optDouble("r").toFloat().coerceIn(4f, 120f)
                r = r.coerceAtMost((SAFE_RIGHT - SAFE_LEFT) / 2f)
                val x = item.optDouble("x").toFloat().coerceIn(SAFE_LEFT + r, SAFE_RIGHT - r)
                val y = item.optDouble("y").toFloat().coerceIn(SAFE_TOP + r, SAFE_BOTTOM - r)
                HudDrawOp(type = "circle", stroke = stroke, gray = gray, x = x, y = y, radius = r, kind = kind)
            }
            "rect" -> {
                if (!item.has("rw") || !item.has("rh")) return null
                val rw = item.optDouble("rw").toFloat().coerceIn(8f, SAFE_RIGHT - SAFE_LEFT)
                val rh = item.optDouble("rh").toFloat().coerceIn(8f, SAFE_BOTTOM - SAFE_TOP)
                val x = item.optDouble("x").toFloat().coerceIn(SAFE_LEFT, SAFE_RIGHT - rw)
                val y = item.optDouble("y").toFloat().coerceIn(SAFE_TOP, SAFE_BOTTOM - rh)
                HudDrawOp(type = "rect", stroke = stroke, gray = gray, x = x, y = y, boxW = rw, boxH = rh, kind = kind)
            }
            else -> null
        }
    }

    private fun kindOf(raw: String): String {
        return when (raw.lowercase()) {
            "f", "fill" -> "f"
            "b", "both" -> "b"
            else -> "s"
        }
    }

    private fun clampTextX(x: Float, align: String, text: String, size: Float): Float {
        val half = (text.length * size * 0.52f).coerceAtLeast(size)
        return when (align) {
            "l" -> x.coerceIn(SAFE_LEFT, SAFE_RIGHT - 8f)
            "r" -> x.coerceIn(SAFE_LEFT + 8f, SAFE_RIGHT)
            else -> x.coerceIn(SAFE_LEFT + half, SAFE_RIGHT - half)
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

    fun clampX(value: Float): Float = value.coerceIn(SAFE_LEFT, SAFE_RIGHT)
    fun clampY(value: Float): Float = value.coerceIn(SAFE_TOP, SAFE_BOTTOM)
}
