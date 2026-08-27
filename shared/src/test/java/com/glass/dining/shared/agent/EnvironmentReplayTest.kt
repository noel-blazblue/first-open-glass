package com.glass.dining.shared.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentReplayTest {
    @Test
    fun computerTurnFloorTurnBackKeepsFloorWhenAsked() {
        val episodes = replay(
            frame(0, 40, "显示器", 50.0),
            frame(1_400, 40, "显示器", 50.0),
            frame(2_800, 110, "", 4.0),
            frame(4_200, 180, "7层 导览", 55.0),
            frame(5_600, 180, "7层 导览", 60.0),
            frame(7_000, 120, "", 5.0),
            frame(8_400, 40, "显示器", 48.0),
            frame(9_800, 40, "显示器", 52.0),
        )
        assertTrue(episodes.size >= 3)
        assertTrue(episodes.any { it.bestOcr.contains("7") })
        assertEquals("显示器", episodes.last().bestOcr)
        assertTrue(episodes.last().bestOcr.contains("显示"))

        var env = EnvironmentState()
        env = commit(
            env,
            10,
            EnvironmentLook(sceneBrief = "办公桌上的电脑显示器", placeHint = "desk", confidence = 0.8f),
            listOf("显示器"),
        )
        env = commit(
            env,
            20,
            EnvironmentLook(
                sceneBrief = "走廊墙面的楼层导览",
                placeHint = "signage",
                floorCandidate = "7",
                floorEvidence = "7层导览",
                confidence = 0.85f,
            ),
            listOf("7层 导览", "7层 导览"),
        )
        env = commit(
            env,
            30,
            EnvironmentLook(sceneBrief = "办公桌上的电脑显示器", placeHint = "desk", confidence = 0.8f),
            listOf("显示器"),
        )
        assertTrue(env.currentBrief.contains("电脑"))
        assertEquals("7", env.usableFloor)
        val answer = FloorQueryPolicy.resolve(env)
        assertNotNull(answer)
        assertEquals("7", answer!!.floor)
        assertEquals("sign", answer.source)
        assertTrue(answer.speak.contains("7"))
        assertTrue(env.promptBlock().contains("楼层标识=7"))
    }

    @Test
    fun vlmBusyKeepsBothSettledEpisodes() {
        val first = replayFrom(
            ProbeState(),
            frame(0, 40, "电脑", 40.0),
            frame(1_400, 40, "电脑", 40.0),
            frame(2_800, 170, "7层 电梯", 50.0),
            frame(4_200, 170, "7层 电梯", 55.0),
        )
        val second = replayFrom(
            first.state,
            frame(5_600, 50, "电脑", 42.0),
            frame(7_000, 50, "电脑", 44.0),
        )
        var queued = emptyList<EnvironmentEpisode>()
        first.episodes.forEach { queued = EpisodeQueuePolicy.enqueue(queued, it) }
        second.episodes.forEach { queued = EpisodeQueuePolicy.enqueue(queued, it) }
        assertTrue(queued.size >= 2)
        assertTrue(queued.any { it.bestOcr.contains("7") })
        assertTrue(queued.last().bestOcr.contains("电脑"))
        val busy = EnvironmentProbe.canCallVlm(8_000, 7_000, listOf(7_000), vlmBusy = true)
        assertFalse(busy.fire)
        assertEquals("busy", busy.reason)
        val later = EpisodeQueuePolicy.enqueue(queued, episode("desk-later", "电脑"))
        assertTrue(later.any { it.bestOcr.contains("7") })
        assertTrue(later.size >= queued.size)
    }

    @Test
    fun singleAdDigitDoesNotPromoteFloor() {
        val prev = EnvironmentState(currentBrief = "工位", updatedAt = 1)
        val next = EnvironmentMerge.fromSignage(listOf("B康7際 优惠"), prev, 2)
        assertEquals("", next.usableFloor)
        assertTrue(next.recentObservations.none { it.kind == EnvironmentObservation.KIND_FLOOR })
    }

    @Test
    fun conflictingFloorReplacesOldObservation() {
        val first = EnvironmentMerge.fromLook(
            EnvironmentLook(
                sceneBrief = "7层导览",
                placeHint = "signage",
                floorCandidate = "7",
                floorEvidence = "7层",
                confidence = 0.8f,
            ),
            EnvironmentState(),
            10,
        )
        val second = EnvironmentMerge.fromLook(
            EnvironmentLook(
                sceneBrief = "4F餐饮指示",
                placeHint = "signage",
                floorCandidate = "4",
                floorEvidence = "4F",
                confidence = 0.8f,
            ),
            first,
            20,
        )
        assertEquals("4", second.usableFloor)
        assertTrue(second.recentObservations.any { it.value == "7" && it.status == EnvironmentObservation.STATUS_STALE })
    }

    @Test
    fun elevatorWithoutNewSignStalesFloor() {
        val withFloor = EnvironmentMerge.fromLook(
            EnvironmentLook(
                sceneBrief = "7层标识",
                placeHint = "signage",
                floorCandidate = "7",
                floorEvidence = "7层",
                confidence = 0.8f,
            ),
            EnvironmentState(),
            10,
        )
        val moved = EnvironmentMerge.fromLook(
            EnvironmentLook(sceneBrief = "电梯轿厢内部", placeHint = "elevator", confidence = 0.7f),
            withFloor,
            20,
        )
        assertEquals("", moved.usableFloor)
        assertTrue(moved.recentObservations.all { it.status == EnvironmentObservation.STATUS_STALE || it.kind != EnvironmentObservation.KIND_FLOOR })
    }

    @Test
    fun userConfirmBeatsVisualFloor() {
        val visual = EnvironmentMerge.fromLook(
            EnvironmentLook(
                sceneBrief = "4层标识",
                placeHint = "signage",
                floorCandidate = "4",
                floorEvidence = "4层",
                confidence = 0.8f,
            ),
            EnvironmentState(),
            10,
        )
        val spoken = EnvironmentMerge.fromSpeech("我在7楼", visual, 20)
        val answer = FloorQueryPolicy.resolve(spoken)!!
        assertEquals("7", answer.floor)
        assertEquals("user", answer.source)
    }

    @Test
    fun rateLimitDoesNotDropQueuedEpisode() {
        val times = listOf(1_000L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L)
        val rated = EnvironmentProbe.canCallVlm(55_000L, 50_000L, times, vlmBusy = false)
        assertFalse(rated.fire)
        assertEquals("rate", rated.reason)
        val queued = EpisodeQueuePolicy.enqueue(emptyList(), episode("floor-7", "7层 导览"))
        assertEquals(1, queued.size)
    }

    @Test
    fun pickBestSkipsBlurredFrame() {
        val blur = frame(0, 160, "7", 3.0)
        val sharp = frame(1_400, 180, "7层 导览", 70.0)
        val best = EnvironmentProbe.pickBest(listOf(blur, sharp))
        assertEquals("7层 导览", best.ocr)
        assertEquals(70.0, best.sharpness, 0.01)
    }

    private fun commit(
        prev: EnvironmentState,
        now: Long,
        look: EnvironmentLook,
        ocr: List<String>,
    ): EnvironmentState {
        var next = EnvironmentMerge.fromLook(look, prev, now)
        next = EnvironmentMerge.fromSignage(ocr, next, now)
        return EnvironmentEventTracker.ingest(look, next, now)
    }

    private fun replay(vararg frames: ProbeFrame): List<EnvironmentEpisode> {
        return replayFrom(ProbeState(), *frames).episodes
    }

    private data class Replay(val episodes: List<EnvironmentEpisode>, val state: ProbeState)

    private fun replayFrom(start: ProbeState, vararg frames: ProbeFrame): Replay {
        var state = start
        val episodes = ArrayList<EnvironmentEpisode>()
        frames.forEach { frame ->
            val (next, signal) = EnvironmentProbe.step(state, frame)
            state = next
            if (signal is ProbeSignal.Settled) episodes.add(signal.episode)
        }
        return Replay(episodes, state)
    }

    private fun frame(at: Long, grid: Int, ocr: String, sharpness: Double): ProbeFrame {
        return ProbeFrame(
            atMs = at,
            grid = IntArray(256) { grid },
            ocr = ocr,
            sharpness = sharpness,
            brightness = 110f,
            qualityOk = true,
        )
    }

    private fun episode(id: String, ocr: String = ""): EnvironmentEpisode {
        return EnvironmentEpisode(
            id = id,
            startedAt = 1,
            settledAt = 2,
            grid = IntArray(16),
            ocrSamples = listOf(ocr).filter { it.isNotBlank() },
            bestOcr = ocr,
            sharpness = 40.0,
            brightness = 110f,
            visualFromAnchor = 0.4f,
            bestFrameAt = 2,
        )
    }
}
