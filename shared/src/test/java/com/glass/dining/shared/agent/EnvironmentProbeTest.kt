package com.glass.dining.shared.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentProbeTest {
    @Test
    fun visualOnlyChangeCanTrigger() {
        val still = grid(40)
        val room = grid(180)
        val first = EnvironmentProbe.score(still, room, "", "", 0f)
        val second = EnvironmentProbe.score(room, still, "", "", 0f)
        assertEquals(EnvironmentProbe.Level.HIGH, first.level)
        assertEquals(EnvironmentProbe.Level.HIGH, second.level)
        assertTrue(EnvironmentProbe.stableChange(listOf(first.level, second.level)))
        val decision = EnvironmentProbe.decide(
            levels = listOf(first.level, second.level),
            moving = true,
            now = 20_000L,
            lastVlmAt = 0L,
            vlmTimes = emptyList(),
            vlmBusy = false,
        )
        assertTrue(decision.fire)
        assertEquals("change", decision.reason)
    }

    @Test
    fun jitterDoesNotTrigger() {
        val base = grid(80)
        val a = EnvironmentProbe.score(base, grid(82), "7F", "7F", 2f)
        val b = EnvironmentProbe.score(grid(82), grid(79), "7F", "7F", 1f)
        val c = EnvironmentProbe.score(grid(79), grid(81), "7F", "7F", 0f)
        assertTrue(a.level == EnvironmentProbe.Level.NONE || a.level == EnvironmentProbe.Level.LOW)
        assertTrue(b.level == EnvironmentProbe.Level.NONE || b.level == EnvironmentProbe.Level.LOW)
        assertFalse(EnvironmentProbe.stableChange(listOf(a.level, b.level, c.level)))
        val decision = EnvironmentProbe.decide(
            levels = listOf(a.level, b.level, c.level),
            moving = false,
            now = 10_000L,
            lastVlmAt = 1_000L,
            vlmTimes = listOf(1_000L),
            vlmBusy = false,
        )
        assertFalse(decision.fire)
        assertEquals("quiet", decision.reason)
    }

    @Test
    fun ocrChangeRaisesScoreButDoesNotWriteFacts() {
        val prev = grid(80)
        val next = grid(108)
        val visualOnly = EnvironmentProbe.score(prev, next, "7F 餐饮", "7F 餐饮", 0f)
        val withOcr = EnvironmentProbe.score(prev, next, "7F 餐饮", "B1 超市出口", 0f)
        assertTrue(withOcr.ocr > visualOnly.ocr)
        assertTrue(withOcr.level >= visualOnly.level)
        assertEquals(EnvironmentProbe.Level.LOW, visualOnly.level)
        assertEquals(EnvironmentProbe.Level.MEDIUM, withOcr.level)
    }

    @Test
    fun cooldownAndRateLimitBlockVlm() {
        val levels = listOf(EnvironmentProbe.Level.HIGH, EnvironmentProbe.Level.HIGH)
        val cooling = EnvironmentProbe.decide(
            levels = levels,
            moving = true,
            now = 12_000L,
            lastVlmAt = 8_000L,
            vlmTimes = listOf(8_000L),
            vlmBusy = false,
        )
        assertFalse(cooling.fire)
        assertEquals("cooldown", cooling.reason)
        val times = listOf(1_000L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L)
        val rated = EnvironmentProbe.decide(
            levels = levels,
            moving = true,
            now = 55_000L,
            lastVlmAt = 40_000L,
            vlmTimes = times,
            vlmBusy = false,
        )
        assertFalse(rated.fire)
        assertEquals("rate", rated.reason)
        val busy = EnvironmentProbe.decide(
            levels = levels,
            moving = true,
            now = 80_000L,
            lastVlmAt = 60_000L,
            vlmTimes = emptyList(),
            vlmBusy = true,
        )
        assertFalse(busy.fire)
        assertEquals("busy", busy.reason)
    }

    @Test
    fun idleRefreshOnlyWhileMoving() {
        val quiet = listOf(EnvironmentProbe.Level.NONE, EnvironmentProbe.Level.LOW, EnvironmentProbe.Level.NONE)
        val idle = EnvironmentProbe.decide(
            levels = quiet,
            moving = true,
            now = 40_000L,
            lastVlmAt = 8_000L,
            vlmTimes = listOf(8_000L),
            vlmBusy = false,
        )
        assertTrue(idle.fire)
        assertEquals("idle", idle.reason)
        val still = EnvironmentProbe.decide(
            levels = quiet,
            moving = false,
            now = 40_000L,
            lastVlmAt = 8_000L,
            vlmTimes = listOf(8_000L),
            vlmBusy = false,
        )
        assertFalse(still.fire)
    }

    @Test
    fun firstStableViewThenTransitionSettlesNewEpisode() {
        var state = ProbeState()
        val computer = ProbeFrame(0, grid(40), "显示器", 50.0, 110f, true)
        val still = ProbeFrame(1_400, grid(40), "显示器", 52.0, 110f, true)
        val (s1, a) = EnvironmentProbe.step(state, computer)
        state = s1
        assertTrue(a is ProbeSignal.Hold)
        val (s2, b) = EnvironmentProbe.step(state, still)
        state = s2
        assertTrue(b is ProbeSignal.Settled)
        val blur = ProbeFrame(2_800, grid(150), "", 3.0, 90f, true, heading = 40f)
        val (s3, c) = EnvironmentProbe.step(state, blur)
        state = s3
        assertTrue(c is ProbeSignal.TransitionStart)
        val floor1 = ProbeFrame(4_200, grid(200), "7层 导览", 60.0, 110f, true)
        val (s4, d) = EnvironmentProbe.step(state, floor1)
        state = s4
        assertTrue(d is ProbeSignal.Hold)
        val floor2 = ProbeFrame(5_600, grid(200), "7层 导览", 62.0, 110f, true)
        val (s5, e) = EnvironmentProbe.step(state, floor2)
        assertTrue(e is ProbeSignal.Settled)
        val episode = (e as ProbeSignal.Settled).episode
        assertTrue(episode.bestOcr.contains("7"))
        assertTrue(episode.visualFromAnchor >= 0.18f)
        assertEquals(ViewPhase.Stable, s5.phase)
    }

    @Test
    fun pickBestPrefersFloorSignOcrOverLongerBanner() {
        val banner = ProbeFrame(0, grid(180), "R 深港欢迎您来到本园区", 50.0, 110f, true)
        val floor = ProbeFrame(1, grid(180), "7层 导览", 40.0, 110f, true)
        val best = EnvironmentProbe.pickBest(listOf(banner, floor))
        assertEquals("7层 导览", best.ocr)
    }

    @Test
    fun newReadableTextRetriggersAfterSimilarVisualSettle() {
        var state = ProbeState()
        val room = grid(180)
        val office = ProbeFrame(0, room, "R 深港欢迎您来到本园区", 50.0, 110f, true)
        state = EnvironmentProbe.step(state, office).first
        val officeStill = ProbeFrame(1_400, room, "R 深港欢迎您来到本园区", 52.0, 110f, true)
        val settled = EnvironmentProbe.step(state, officeStill)
        state = settled.first
        assertTrue(settled.second is ProbeSignal.Settled)

        val sign = ProbeFrame(2_800, room, "7层 导览", 48.0, 110f, true)
        val leave = EnvironmentProbe.step(state, sign)
        assertTrue(leave.second is ProbeSignal.TransitionStart)
        assertTrue((leave.second as ProbeSignal.TransitionStart).ocr >= 0.30f)
        state = leave.first
        val stay = ProbeFrame(4_200, room, "7层 导览", 50.0, 110f, true)
        val again = EnvironmentProbe.step(state, stay)
        assertTrue(again.second is ProbeSignal.Settled)
        assertTrue((again.second as ProbeSignal.Settled).episode.bestOcr.contains("7"))
    }

    private fun grid(value: Int): IntArray {
        return IntArray(256) { value.coerceIn(0, 255) }
    }
}
