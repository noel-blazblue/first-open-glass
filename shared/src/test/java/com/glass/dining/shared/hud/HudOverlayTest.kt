package com.glass.dining.shared.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudOverlayTest {
    @Test
    fun overlayStaysCloseableDuringNav() {
        assertEquals(
            HudOverlay.CONFIRM,
            HudOverlay.kind(
                pendingConfirm = true,
                drawShowing = true,
                storeShowing = true,
                cardShowing = true,
            ),
        )
        assertEquals(
            HudOverlay.DRAW,
            HudOverlay.kind(
                pendingConfirm = false,
                drawShowing = true,
                storeShowing = true,
            ),
        )
        assertTrue(HudOverlay.canClose(HudOverlay.DRAW))
        assertTrue(HudOverlay.shouldHoldNavPush(HudOverlay.DRAW, alreadyNavigating = true))
        assertFalse(HudOverlay.shouldHoldNavPush(HudOverlay.DRAW, alreadyNavigating = false))
        assertFalse(HudOverlay.shouldHoldNavPush(HudOverlay.NONE))
        assertFalse(HudOverlay.canClose(HudOverlay.NAV))
    }

    @Test
    fun confirmOutranksDrawAndStore() {
        assertEquals(
            HudOverlay.CONFIRM,
            HudOverlay.kind(
                pendingConfirm = true,
                drawShowing = true,
                storeShowing = true,
            ),
        )
        assertTrue(HudOverlay.canClose(HudOverlay.CONFIRM))
    }

    @Test
    fun overlayFaceBeatsNav() {
        assertEquals(
            HudOverlay.STORE,
            HudOverlay.face(
                drawShowing = false,
                storeShowing = true,
                cardIsNav = true,
                cardIsTalk = false,
            ),
        )
        assertEquals(
            HudOverlay.DRAW,
            HudOverlay.face(
                drawShowing = true,
                storeShowing = true,
                cardIsNav = true,
                cardIsTalk = false,
            ),
        )
        assertEquals(
            HudOverlay.NAV,
            HudOverlay.face(
                drawShowing = false,
                storeShowing = false,
                cardIsNav = true,
                cardIsTalk = false,
            ),
        )
    }

    @Test
    fun emptyIsNotCloseable() {
        assertEquals(HudOverlay.NONE, HudOverlay.kind(false, false, false, false))
        assertFalse(HudOverlay.canClose(HudOverlay.NONE))
        assertNull(HudOverlay.contextLine(HudOverlay.NONE))
    }

    @Test
    fun contextTellsModelHowToLeave() {
        assertTrue(HudOverlay.contextLine(HudOverlay.DRAW)!!.contains("close_hud"))
        assertTrue(HudOverlay.contextLine(HudOverlay.CONFIRM)!!.contains("close_hud"))
        assertTrue(HudOverlay.contextLine(HudOverlay.NONE, navActive = true)!!.contains("stop_navigation"))
        assertTrue(HudOverlay.contextLine(HudOverlay.DRAW, navActive = true)!!.contains("关掉后回到导航"))
        assertTrue(HudOverlay.contextLine(HudOverlay.CONFIRM)!!.contains("不要带 confirm=true"))
        assertFalse(HudOverlay.contextLine(HudOverlay.DRAW, navActive = true)!!.contains("stop_navigation"))
    }
}
