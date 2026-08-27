package com.glass.dining.shared.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentEventTrackerTest {
    @Test
    fun coffeeTripConfirmsOnlyAfterPickupAndKeepsEventAfterReturn() {
        var env = EnvironmentState()
        env = ingest(
            env,
            10,
            EnvironmentLook(
                sceneBrief = "工位上的电脑和键盘",
                objects = listOf("电脑", "显示器"),
                actions = listOf("操作"),
                placeHint = "desk",
            ),
        )
        assertEquals("在工位操作电脑", env.activeEvent?.summary)
        assertEquals(EnvironmentObservation.STATUS_OBSERVED, env.activeEvent?.confidence)

        env = ingest(
            env,
            20,
            EnvironmentLook(sceneBrief = "从工位起身", actions = listOf("起身"), placeHint = "other"),
        )
        env = ingest(
            env,
            30,
            EnvironmentLook(sceneBrief = "办公区走廊", placeHint = "corridor"),
        )
        assertEquals("离开工位并移动中", env.activeEvent?.summary)
        assertEquals("hypothesis", env.activeEvent?.confidence)
        assertFalse(env.activeEvent?.summary.orEmpty().contains("咖啡"))

        env = ingest(
            env,
            40,
            EnvironmentLook(
                sceneBrief = "咖啡机和杯子，正在接咖啡",
                objects = listOf("咖啡机", "杯子"),
                actions = listOf("接"),
                placeHint = "coffee",
            ),
        )
        assertEquals("从工位起身去接咖啡", env.activeEvent?.summary)
        assertEquals(EnvironmentObservation.STATUS_CONFIRMED, env.activeEvent?.confidence)

        env = ingest(
            env,
            50,
            EnvironmentLook(sceneBrief = "回到工位电脑前", objects = listOf("电脑"), placeHint = "desk"),
        )
        assertEquals("回到工位操作电脑", env.activeEvent?.summary)
        assertTrue(env.recentEvents.any { it.summary.contains("接咖啡") })
        assertTrue(env.currentBrief.contains("工位") || env.activeEvent?.summary?.contains("工位") == true)
    }

    @Test
    fun meetingTripRevisesCoffeeHypothesis() {
        var env = EnvironmentState()
        env = ingest(env, 10, EnvironmentLook(sceneBrief = "电脑", placeHint = "desk"))
        env = ingest(env, 20, EnvironmentLook(sceneBrief = "走廊", placeHint = "corridor"))
        assertEquals("hypothesis", env.activeEvent?.confidence)
        env = ingest(
            env,
            30,
            EnvironmentLook(
                sceneBrief = "会议室白板和投影",
                objects = listOf("投影", "白板"),
                placeHint = "meeting",
            ),
        )
        assertEquals("从工位前往会议区域", env.activeEvent?.summary)
        assertFalse(env.activeEvent?.summary.orEmpty().contains("咖啡"))
        assertFalse(env.recentEvents.any { it.summary.contains("咖啡") })
        assertEquals(EnvironmentObservation.STATUS_CONFIRMED, env.activeEvent?.confidence)
    }

    private fun ingest(prev: EnvironmentState, now: Long, look: EnvironmentLook): EnvironmentState {
        val withScene = EnvironmentMerge.fromLook(look.copy(confidence = 0.8f), prev, now)
        return EnvironmentEventTracker.ingest(look, withScene, now)
    }
}
