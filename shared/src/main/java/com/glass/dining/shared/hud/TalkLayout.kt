package com.glass.dining.shared.hud

import kotlin.math.min

/** 对话面：人物居中，字幕在头顶，上一行残影上移。 */
object TalkLayout {
    const val CHAR_SIZE_FRACTION = 0.28f
    const val CHAR_CY_FRACTION = 0.84f
    const val CHAR_ASPECT = 193f / 320f
    const val LINE_GAP_PX = 28f
    const val TEXT_SIZE_PX = 20f
    const val TEXT_GAP_PX = 18f
    const val BOB_PX = 3.5f
    const val CLOCK_LEFT = 16f
    const val CLOCK_BOTTOM = 16f
    const val SPEECH_LINES = 3
    const val FADE_START = 0.62f
    const val FADE_NEAR = 0.28f
    const val FADE_FAR = 0.12f
    const val SCROLL_MS = 260f
    const val GLASS_W = 480f
    const val GLASS_H = 640f
    const val SAFE_LEFT = 64f
    const val SAFE_RIGHT = 416f
    const val SAFE_TOP = 56f
    const val SAFE_BOTTOM = 552f
    const val PREVIEW_GAP_DP = 16
    const val PREVIEW_CHAR_WIDTH_DP = 72
    const val PREVIEW_CHAR_HEIGHT_DP = 96

    fun characterSize(w: Float, h: Float): Float = min(w, h) * CHAR_SIZE_FRACTION

    fun characterWidth(w: Float, h: Float): Float = characterSize(w, h) * CHAR_ASPECT

    fun characterCenterX(w: Float): Float = w / 2f

    fun characterCenterY(h: Float): Float = h * CHAR_CY_FRACTION

    fun characterTop(w: Float, h: Float): Float = characterCenterY(h) - characterSize(w, h) / 2f

    fun textCenterX(w: Float): Float = w / 2f

    fun textMaxWidth(w: Float): Float = (safeRight(w) - safeLeft(w)).coerceAtLeast(TEXT_SIZE_PX * 6f)

    fun speechColumns(w: Float = GLASS_W): Int {
        return (textMaxWidth(w) / TEXT_SIZE_PX).toInt().coerceIn(10, 16)
    }

    fun newestBaselineY(w: Float, h: Float, descent: Float): Float {
        return characterTop(w, h) - BOB_PX - TEXT_GAP_PX - descent
    }

    fun lineBaselineY(w: Float, h: Float, fromBottom: Int, descent: Float, scroll: Float = 1f): Float {
        val locked = newestBaselineY(w, h, descent) - fromBottom * LINE_GAP_PX
        return if (fromBottom == 0) locked else locked + scrollShift(scroll)
    }

    fun scrollShift(progress: Float): Float = (1f - progress.coerceIn(0f, 1f)) * LINE_GAP_PX

    fun safeLeft(w: Float): Float = w * SAFE_LEFT / GLASS_W

    fun safeRight(w: Float): Float = w * SAFE_RIGHT / GLASS_W

    fun safeTop(h: Float): Float = h * SAFE_TOP / GLASS_H

    fun safeBottom(h: Float): Float = h * SAFE_BOTTOM / GLASS_H
}
