package com.glass.dining.shared.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkCaptionTest {
    @Test
    fun singleLineStaysOpaque() {
        val lines = TalkCaption.lines(480f, 640f, "附近有几家店")
        assertEquals(1, lines.size)
        assertEquals(1f, lines[0].alpha)
        assertEquals(0, lines[0].fromBottom)
    }

    @Test
    fun overflowKeepsLatestLinesAndFadesOlder() {
        val width = TalkLayout.speechColumns()
        val text = "附近有几家店，你想吃什么？第一是一家火锅，现在人不多。也可以去面馆，走路更近一点。"
        val all = TalkCaption.chunks(text, width)
        assertTrue(all.size >= 3)
        val lines = TalkCaption.lines(480f, 640f, text, width = width)
        assertEquals(3, lines.size)
        assertEquals(all.takeLast(3), lines.map { it.text })
        assertEquals(1f, lines.last().alpha)
        assertEquals(TalkLayout.FADE_NEAR, lines[1].alpha)
        assertEquals(TalkLayout.FADE_FAR, lines.first().alpha)
        assertTrue(lines[0].y < lines[1].y)
        assertTrue(lines[1].y < lines[2].y)
    }

    @Test
    fun liveBaselineStaysLockedWhileGhostRises() {
        val text = "一二三四五六七八九十一二三四五六七八九十"
        val start = TalkCaption.lines(480f, 640f, text, scroll = 0f)
        val end = TalkCaption.lines(480f, 640f, text, scroll = 1f)
        assertEquals(2, start.size)
        assertEquals(start.last().y, end.last().y, 0.01f)
        assertTrue(end.first().y < start.first().y)
        assertTrue(start.first().alpha > end.first().alpha)
    }

    @Test
    fun growingCurrentLineDoesNotRetriggerScrollKey() {
        val a = TalkCaption.lines(480f, 640f, "附近有几家")
        val b = TalkCaption.lines(480f, 640f, "附近有几家店")
        assertEquals(TalkCaption.key(a), TalkCaption.key(b))
    }

    @Test
    fun wrapSpeechShowsTailNotHeadEllipsis() {
        val width = 4
        val texts = TalkCaption.visibleTexts("abcdefghijklmnop", width, 2)
        assertEquals(listOf("ijkl", "mnop"), texts)
    }
}
