package com.glass.dining.shared.hud

import org.junit.Assert.assertTrue
import org.junit.Test

class TalkLayoutTest {
    @Test
    fun speechBaselineStaysAboveCharacterOnGlass() {
        val w = 480f
        val h = 640f
        val descent = 6f
        val top = TalkLayout.characterTop(w, h)
        val baseline = TalkLayout.textBaselineY(w, h, descent)
        assertTrue(top - baseline >= TalkLayout.TEXT_GAP_PX + TalkLayout.BOB_PX + descent - 0.1f)
        assertTrue(baseline > h * 0.5f)
        val charBottom = TalkLayout.characterCenterY(h) + TalkLayout.characterSize(w, h) / 2f
        assertTrue(charBottom < h - 16f)
    }
}
