package com.glass.dining.shared.nav

import com.glass.dining.shared.vision.LocalExtract
import com.glass.dining.shared.vision.OcrBlock

data class LandmarkHit(
    val kind: String,
    val headingDeg: Float,
    val elevationDeg: Float,
    val area: Int,
)

/**
 * OCR 标牌证据：楼层、电梯、出口、店招。不单独决定室内路线。
 */
object LandmarkSignage {
    private val elevator = Regex("电梯|直梯|升降梯|客梯")
    private val stairs = Regex("扶梯|自动扶梯|楼梯|步行梯")
    private val corridor = Regex("通道|走廊|过道")
    private val indoorCue = Regex("电梯|扶梯|楼梯|楼层|导览|出口|大门|[BF]\\d|\\d\\s*[Ff层楼]")
    private val deepIndoor = Regex("电梯|扶梯|楼梯|楼层|导览|[BF]\\d|\\d\\s*[Ff层楼]")
    private val outdoorCue = Regex("路口|红绿灯|人行道|人行横道|公交站|马路")
    private val exitCue = Regex("出口|大门|正门|地面层|Exit|GATE|出门")

    fun normalizeFloor(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        Regex("""地下\s*(\d{1,2})\s*层""").find(text)?.let { return "B${it.groupValues[1]}" }
        Regex("""负\s*([一二三四五六七八九十\d]{1,2})""").find(text)?.let { matched ->
            val n = chineseOrDigit(matched.groupValues[1]) ?: return@let
            return "B$n"
        }
        Regex("""(?i)b-?\s*(\d{1,2})""").find(text)?.let { return "B${it.groupValues[1]}" }
        Regex("""第\s*(\d{1,2})\s*层""").find(text)?.let { return it.groupValues[1] }
        Regex("""(\d{1,2})\s*[Ff层楼]""").find(text)?.let { return it.groupValues[1] }
        Regex("""(?i)^[Bb]?-?\d{1,2}$""").matchEntire(
            text.replace("层", "").replace("楼", "").replace("F", "").replace("f", "").trim(),
        )?.let { matched ->
            val digits = Regex("""\d{1,2}""").find(matched.value)?.value ?: return ""
            return if (matched.value.startsWith("B", true) || matched.value.startsWith("b")) "B$digits" else digits
        }
        return ""
    }

    fun parseSpokenFloor(utterance: String): String {
        val text = utterance.trim()
        if (text.isBlank()) return ""
        Regex("""(?:我在|现在在|当前在|从)\s*(地下\s*\d{1,2}\s*层|负\s*[一二三四五六七八九十\d]{1,2}\s*层?|[Bb]-?\s*\d{1,2}\s*[层楼Ff]?|\d{1,2}\s*[层楼Ff])""")
            .find(text)
            ?.let { return normalizeFloor(it.groupValues[1]) }
        return ""
    }

    fun parseVisibleFloor(text: String): String {
        val raw = text.trim()
        if (raw.isBlank()) return ""
        Regex("""可见楼层\s*([Bb]-?\s*\d{1,2}|\d{1,2}|[一二三四五六七八九十]{1,3})\s*[Ff层楼]?""")
            .find(raw)
            ?.let { return normalizeFloor(it.groupValues[1]) }
        Regex("""(?:楼层标识|层号|电梯显示|导览)\s*[上是为：:\s]*([Bb]-?\s*\d{1,2}|\d{1,2}\s*[Ff层楼]|第\s*\d{1,2}\s*层)""")
            .find(raw)
            ?.let { return normalizeFloor(it.groupValues[1]) }
        Regex("""(\d{1,2})\s*[Ff层楼](?:标识|指示牌|导览|电梯)?""")
            .find(raw)
            ?.let { return it.groupValues[1] }
        Regex("""第\s*(\d{1,2})\s*层""")
            .find(raw)
            ?.let { return it.groupValues[1] }
        Regex("""地下\s*(\d{1,2})\s*层""")
            .find(raw)
            ?.let { return "B${it.groupValues[1]}" }
        return ""
    }

    fun floorRank(floor: String): Int? {
        val n = normalizeFloor(floor).ifBlank { return null }
        if (n.startsWith("B", ignoreCase = true)) {
            val depth = n.drop(1).toIntOrNull() ?: return null
            return -depth
        }
        if (n.equals("G", true)) return 1
        return n.toIntOrNull()
    }

    fun relate(current: String, target: String): FloorRel {
        val a = floorRank(current) ?: return FloorRel.UNKNOWN
        val b = floorRank(target) ?: return FloorRel.UNKNOWN
        return when {
            a == b -> FloorRel.SAME
            a < b -> FloorRel.UP
            else -> FloorRel.DOWN
        }
    }

    fun hasIndoorCue(extract: LocalExtract): Boolean {
        return indoorCue.containsMatchIn(extract.ocrText)
    }

    fun hasDeepIndoorCue(extract: LocalExtract): Boolean {
        return deepIndoor.containsMatchIn(extract.ocrText)
    }

    fun hasOutdoorCue(extract: LocalExtract): Boolean {
        return outdoorCue.containsMatchIn(extract.ocrText)
    }

    fun hasFloorNumber(extract: LocalExtract): Boolean {
        return extract.ocr.any { normalizeFloor(it.text).isNotBlank() }
    }

    fun bestFloor(extract: LocalExtract): String? {
        return extract.ocr.mapNotNull { block ->
            val floor = normalizeFloor(block.text)
            if (floor.isBlank()) return@mapNotNull null
            val area = (block.right - block.left).coerceAtLeast(1) *
                (block.bottom - block.top).coerceAtLeast(1)
            floor to area
        }.maxByOrNull { it.second }?.first
    }

    fun floorMatch(extract: LocalExtract, floor: String): Boolean {
        if (floor.isBlank()) return false
        val want = normalizeFloor(floor).ifBlank { floor.uppercase() }
        return extract.ocr.any { block ->
            val got = normalizeFloor(block.text)
            got.isNotBlank() && got.equals(want, ignoreCase = true)
        }
    }

    fun bestVertical(extract: LocalExtract): LandmarkHit? {
        val w = extract.width.coerceAtLeast(1)
        val h = extract.height.coerceAtLeast(1)
        return extract.ocr.mapNotNull { block ->
            val kind = when {
                elevator.containsMatchIn(block.text) -> LandmarkPlanner.MODE_ELEVATOR
                stairs.containsMatchIn(block.text) -> LandmarkPlanner.MODE_STAIRS
                corridor.containsMatchIn(block.text) -> LandmarkPlanner.MODE_CORRIDOR
                else -> return@mapNotNull null
            }
            val pose = poseOf(block, w, h)
            val area = (block.right - block.left).coerceAtLeast(1) * (block.bottom - block.top).coerceAtLeast(1)
            LandmarkHit(kind, pose.first, pose.second, area)
        }.maxByOrNull { it.area }
    }

    fun bestExit(extract: LocalExtract): LandmarkHit? {
        val w = extract.width.coerceAtLeast(1)
        val h = extract.height.coerceAtLeast(1)
        return extract.ocr.mapNotNull { block ->
            if (!exitCue.containsMatchIn(block.text)) return@mapNotNull null
            val pose = poseOf(block, w, h)
            val area = (block.right - block.left).coerceAtLeast(1) * (block.bottom - block.top).coerceAtLeast(1)
            LandmarkHit(LandmarkPlanner.MODE_EXIT, pose.first, pose.second, area)
        }.maxByOrNull { it.area }
    }

    fun bestStore(extract: LocalExtract, goal: LandmarkGoal): OcrBlock? {
        val names = listOf(goal.shortName, goal.storeName)
            .map { it.replace(" ", "").trim() }
            .filter { it.length >= 2 }
            .distinct()
        if (names.isEmpty()) return null
        return extract.ocr.firstOrNull { block ->
            val compact = block.text.replace(" ", "")
            names.any { name -> compact.contains(name) }
        }
    }

    fun signDirection(extract: LocalExtract): Float {
        val text = extract.ocrText
        val fromArrow = when {
            text.contains("→") || text.contains("➡") || text.contains("右") -> 18f
            text.contains("←") || text.contains("⬅") || text.contains("左") -> -18f
            else -> 0f
        }
        if (fromArrow != 0f) return fromArrow
        val cue = extract.ocr.firstOrNull { indoorCue.containsMatchIn(it.text) } ?: return 0f
        return poseOf(cue, extract.width.coerceAtLeast(1), extract.height.coerceAtLeast(1)).first
    }

    fun poseOf(block: OcrBlock, width: Int, height: Int): Pair<Float, Float> {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val cx = (block.left + block.right) / 2f
        val cy = (block.top + block.bottom) / 2f
        val heading = ((cx / w) - 0.5f) * LandmarkPlanner.CAMERA_HFOV
        val elevation = (0.5f - (cy / h)) * LandmarkPlanner.CAMERA_VFOV
        return heading.coerceIn(-40f, 40f) to elevation.coerceIn(-40f, 40f)
    }

    private fun chineseOrDigit(raw: String): Int? {
        return when (raw.trim()) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "七" -> 7
            "八" -> 8
            "九" -> 9
            "十" -> 10
            else -> raw.toIntOrNull()
        }
    }
}
