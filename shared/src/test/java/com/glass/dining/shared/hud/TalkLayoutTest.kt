package com.glass.dining.shared.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkLayoutTest {
    private val w = TalkLayout.GLASS_W
    private val h = TalkLayout.GLASS_H

    @Test
    fun characterSitsInTheCenter() {
        assertEquals(w / 2f, TalkLayout.characterCenterX(w), 0.1f)
        val bottom = TalkLayout.characterCenterY(h) + TalkLayout.characterSize(w, h) / 2f
        assertTrue(bottom < h - TalkLayout.CLOCK_BOTTOM)
    }

    @Test
    fun speechSitsCenteredAboveCharacter() {
        val descent = 6f
        val baseline = TalkLayout.newestBaselineY(w, h, descent)
        val top = TalkLayout.characterTop(w, h)
        assertTrue(top - baseline >= TalkLayout.TEXT_GAP_PX + TalkLayout.BOB_PX + descent - 0.1f)
        assertEquals(w / 2f, TalkLayout.textCenterX(w), 0.1f)
        assertEquals(16, TalkLayout.speechColumns(w))
    }

    @Test
    fun clockStaysInTheCorner() {
        assertEquals(16f, TalkLayout.CLOCK_LEFT)
        assertEquals(16f, TalkLayout.CLOCK_BOTTOM)
    }

    @Test
    fun liveBaselineStaysLockedWhileGhostRises() {
        val descent = 6f
        val locked = TalkLayout.lineBaselineY(w, h, 0, descent, 0f)
        val after = TalkLayout.lineBaselineY(w, h, 0, descent, 1f)
        assertEquals(locked, after, 0.01f)
        val ghost = TalkLayout.lineBaselineY(w, h, 1, descent, 1f)
        assertTrue(ghost >= TalkLayout.SAFE_TOP)
        assertTrue(ghost < locked)
    }
}
