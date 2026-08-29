package com.glass.dining.shared.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkLayoutTest {
    private val w = TalkLayout.GLASS_W
    private val h = TalkLayout.GLASS_H

    @Test
    fun characterSitsLeftOfCenterOnGlass() {
        val cx = TalkLayout.characterCenterX(w, h)
        val left = cx - TalkLayout.characterWidth(w, h) / 2f
        assertTrue(cx < w * 0.4f)
        assertTrue(cx > w * 0.2f)
        assertTrue(left >= TalkLayout.SAFE_LEFT - 0.1f)
        val bottom = TalkLayout.characterCenterY(h) + TalkLayout.characterSize(w, h) / 2f
        assertTrue(bottom < h - 16f)
    }

    @Test
    fun speechSitsToTheRightOfCharacter() {
        val textX = TalkLayout.textLeft(w, h)
        assertTrue(textX >= TalkLayout.characterRight(w, h) + TalkLayout.TEXT_GAP_X - 0.1f)
        val right = textX + TalkLayout.speechColumns(w, h) * TalkLayout.TEXT_SIZE_PX
        assertTrue(right <= TalkLayout.SAFE_RIGHT + 0.1f)
        assertEquals(14, TalkLayout.speechColumns(w, h))
    }

    @Test
    fun newestLineStaysBesideCharacter() {
        val descent = 6f
        val y = TalkLayout.newestBaselineY(w, h, descent)
        val top = TalkLayout.characterTop(w, h)
        val cy = TalkLayout.characterCenterY(h)
        assertTrue(y > top)
        assertTrue(y < cy)
        val locked = TalkLayout.lineBaselineY(w, h, 0, descent, 0f)
        val after = TalkLayout.lineBaselineY(w, h, 0, descent, 1f)
        assertEquals(locked, after, 0.01f)
        val ghost = TalkLayout.lineBaselineY(w, h, 1, descent, 1f)
        assertTrue(ghost >= TalkLayout.SAFE_TOP)
        assertTrue(ghost < locked)
    }
}
