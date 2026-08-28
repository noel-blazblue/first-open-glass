package com.glass.dining.shared.hud

import kotlin.math.min

/** 对话面：字幕在人物上方，预留下沉和上下浮动，避免字被挡住。 */
object TalkLayout {
    const val CHAR_SIZE_FRACTION = 0.28f
    const val CHAR_CY_FRACTION = 0.84f
    const val LINE_GAP_PX = 28f
    const val TEXT_SIZE_PX = 20f
    const val TEXT_GAP_PX = 18f
    const val BOB_PX = 3.5f
    const val PREVIEW_GAP_DP = 16
    const val PREVIEW_CHAR_WIDTH_DP = 72
    const val PREVIEW_CHAR_HEIGHT_DP = 96

    fun characterSize(w: Float, h: Float): Float = min(w, h) * CHAR_SIZE_FRACTION

    fun characterCenterY(h: Float): Float = h * CHAR_CY_FRACTION

    fun characterTop(w: Float, h: Float): Float = characterCenterY(h) - characterSize(w, h) / 2f

    fun textBaselineY(w: Float, h: Float, descent: Float): Float {
        return characterTop(w, h) - BOB_PX - TEXT_GAP_PX - descent
    }
}
