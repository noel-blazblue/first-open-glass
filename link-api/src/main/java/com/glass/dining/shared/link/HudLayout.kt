package com.glass.dining.shared.link

import org.json.JSONArray
import org.json.JSONObject

internal sealed class HudBlock {
    data class Col(val gap: Float, val children: List<HudBlock>) : HudBlock()
    data class Row(val children: List<HudBlock>) : HudBlock()
    data class Copy(val role: String, val text: String) : HudBlock()
    data object Rule : HudBlock()
    data class Path(val d: String, val kind: String) : HudBlock()
    data class Circle(val radius: Float, val kind: String) : HudBlock()
    data class Rect(val boxW: Float, val boxH: Float, val kind: String) : HudBlock()
}

object HudType {
    const val KICKER = "kicker"
    const val TITLE = "title"
    const val BODY = "body"
    const val META = "meta"
    const val HINT = "hint"

    fun size(role: String): Float = when (role) {
        TITLE -> 26f
        BODY -> 20f
        else -> 14f
    }

    fun gray(role: String): Float = when (role) {
        TITLE, BODY -> 1f
        META -> 0.7f
        else -> 0.55f
    }

    fun maxLines(role: String): Int = if (role == TITLE || role == BODY) 2 else 1

    fun lineBox(size: Float): Float = size * 1.32f

    fun baseline(top: Float, size: Float): Float = top + size * 0.82f

    fun glyphWidth(ch: Char, size: Float): Float {
        return if (ch.code < 127) size * 0.58f else size
    }

    fun measure(text: String, size: Float): Float {
        var w = 0f
        text.forEach { w += glyphWidth(it, size) }
        return w
    }

    fun wrap(text: String, width: Float, size: Float, maxLines: Int): List<String> {
        if (text.isBlank() || width < size * 0.5f) return emptyList()
        val lines = ArrayList<String>(maxLines)
        var i = 0
        while (i < text.length && lines.size < maxLines) {
            val last = lines.size == maxLines - 1
            val start = i
            var end = i
            var lineW = 0f
            while (end < text.length) {
                val gw = glyphWidth(text[end], size)
                if (lineW + gw > width && end > start) break
                lineW += gw
                end++
            }
            var slice = text.substring(start, end)
            if (last && end < text.length) slice = ellipsize(text.substring(start), width, size)
            lines.add(slice)
            if (end == start) break
            i = end
        }
        return lines
    }

    fun ellipsize(text: String, width: Float, size: Float): String {
        val dots = "…"
        val dotsW = measure(dots, size)
        if (measure(text, size) <= width) return text
        val buf = StringBuilder()
        var w = 0f
        for (ch in text) {
            val gw = glyphWidth(ch, size)
            if (w + gw + dotsW > width) break
            buf.append(ch)
            w += gw
        }
        return if (buf.isEmpty()) dots else buf.append(dots).toString()
    }
}

object HudLayout {
    const val MAX_NODES = 24
    const val MAX_DEPTH = 3
    const val GAP_S = 8f
    const val GAP_M = 16f
    const val GAP_L = 28f
    const val ROW_GAP = 12f

    fun compile(args: JSONObject): HudDrawScene? {
        val root = readRoot(args) ?: return null
        return HudLayoutCompiler.lower(root, args.optLong("seq"))
    }

    internal fun readRoot(args: JSONObject): HudBlock? {
        if (!args.has("layout")) return null
        val raw = args.opt("layout")
        val node = when (raw) {
            is JSONArray -> HudBlock.Col(GAP_M, readList(raw, 0))
            is JSONObject -> readNode(raw, 0)
            else -> null
        } ?: return null
        return if (node is HudBlock.Col) node else HudBlock.Col(GAP_M, listOf(node))
    }

    internal fun gapOf(raw: String): Float = when (raw.lowercase()) {
        "s", "sm", "small" -> GAP_S
        "l", "lg", "large" -> GAP_L
        else -> GAP_M
    }

    internal fun roleOf(raw: String): String {
        return when (raw.lowercase()) {
            HudType.KICKER, HudType.TITLE, HudType.META, HudType.HINT -> raw.lowercase()
            else -> HudType.BODY
        }
    }

    private fun readList(arr: JSONArray, depth: Int): List<HudBlock> {
        if (depth > MAX_DEPTH) return emptyList()
        val out = ArrayList<HudBlock>(arr.length().coerceAtMost(MAX_NODES))
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_NODES) break
            val item = arr.optJSONObject(i) ?: continue
            readNode(item, depth)?.let(out::add)
        }
        return out
    }

    private fun readNode(item: JSONObject, depth: Int): HudBlock? {
        if (depth > MAX_DEPTH) return null
        val kind = kindOf(item.optString("k", "s"))
        return when (item.optString("t")) {
            "col" -> {
                val ch = childrenOf(item, depth + 1)
                if (ch.isEmpty()) null else HudBlock.Col(gapOf(item.optString("gap", "m")), ch)
            }
            "row" -> {
                val ch = childrenOf(item, depth + 1).ifEmpty { rowPair(item) }
                if (ch.isEmpty()) null else HudBlock.Row(ch)
            }
            "text" -> {
                val text = item.optString("v").replace(Regex("[\\r\\n]+"), " ").trim()
                if (text.isBlank()) null else HudBlock.Copy(roleOf(item.optString("role")), text)
            }
            "rule" -> HudBlock.Rule
            "path" -> {
                val d = item.optString("d")
                if (HudDraw.parsePath(d).isEmpty()) null else HudBlock.Path(d, kind)
            }
            "circle" -> {
                if (!item.has("r")) return null
                HudBlock.Circle(item.optDouble("r").toFloat().coerceIn(4f, 80f), kind)
            }
            "rect" -> {
                if (!item.has("rw") || !item.has("rh")) return null
                HudBlock.Rect(
                    item.optDouble("rw").toFloat().coerceIn(8f, HudDraw.SAFE_RIGHT - HudDraw.SAFE_LEFT),
                    item.optDouble("rh").toFloat().coerceIn(8f, 160f),
                    kind,
                )
            }
            else -> null
        }
    }

    private fun childrenOf(item: JSONObject, depth: Int): List<HudBlock> {
        val arr = item.optJSONArray("ch") ?: item.optJSONArray("children") ?: return emptyList()
        return readList(arr, depth)
    }

    private fun rowPair(item: JSONObject): List<HudBlock> {
        val left = item.optString("left").trim()
        val right = item.optString("right").trim()
        val out = ArrayList<HudBlock>(2)
        if (left.isNotBlank()) out.add(HudBlock.Copy(HudType.BODY, left))
        if (right.isNotBlank()) out.add(HudBlock.Copy(HudType.META, right))
        return out
    }

    private fun kindOf(raw: String): String {
        return when (raw.lowercase()) {
            "f", "fill" -> "f"
            "b", "both" -> "b"
            else -> "s"
        }
    }
}
