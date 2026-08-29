package com.glass.dining.shared.hud

import kotlin.math.min

/** 对话面：当前行锁在脸旁，上一行残影上移。 */
object TalkLayout {
    const val CHAR_SIZE_FRACTION = 0.28f
    const val CHAR_CX_FRACTION = 0.25f
    const val CHAR_CY_FRACTION = 0.84f
    const val CHAR_ASPECT = 193f / 320f
    const val LINE_GAP_PX = 26f
    const val TEXT_SIZE_PX = 17f
    const val TEXT_GAP_X = 10f
    const val BOB_PX = 3.5f
    const val SPEECH_LINES = 2
    const val FADE_START = 0.62f
    const val FADE_NEAR = 0.22f
    const val FADE_FAR = 0.10f
    const val SCROLL_MS = 260f
    const val GLASS_W = 480f
    const val GLASS_H = 640f
    const val SAFE_LEFT = 64f
    const val SAFE_RIGHT = 416f
    const val SAFE_TOP = 56f
    const val SAFE_BOTTOM = 552f

    fun characterSize(w: Float, h: Float): Float = min(w, h) * CHAR_SIZE_FRACTION

    fun characterWidth(w: Float, h: Float): Float = characterSize(w, h) * CHAR_ASPECT

    fun characterCenterX(w: Float, h: Float): Float {
        val half = characterWidth(w, h) / 2f
        return (w * CHAR_CX_FRACTION).coerceIn(safeLeft(w) + half, safeRight(w) - half)
    }

    fun characterCenterY(h: Float): Float = h * CHAR_CY_FRACTION

    fun characterTop(w: Float, h: Float): Float = characterCenterY(h) - characterSize(w, h) / 2f

    fun characterRight(w: Float, h: Float): Float = characterCenterX(w, h) + characterWidth(w, h) / 2f

    fun textLeft(w: Float, h: Float): Float = characterRight(w, h) + TEXT_GAP_X

    fun textMaxWidth(w: Float, h: Float): Float {
        return (safeRight(w) - textLeft(w, h)).coerceAtLeast(TEXT_SIZE_PX * 6f)
    }

    fun speechColumns(w: Float = GLASS_W, h: Float = GLASS_H): Int {
        return (textMaxWidth(w, h) / TEXT_SIZE_PX).toInt().coerceIn(8, 16)
    }

    fun newestBaselineY(w: Float, h: Float, descent: Float): Float {
        return characterTop(w, h) + characterSize(w, h) * 0.32f - descent
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
