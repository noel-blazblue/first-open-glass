package com.glass.dining.shared.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryTest {
    @Test
    fun summaryOmitsSideEffectIds() {
        val dropped = listOf(
            LlmMessage("user", "导航去海底捞"),
            LlmMessage(
                "tool",
                """{"ok":true,"shortName":"海底捞","request_id":"nav-123","place_id":"s1"}""",
                toolCallId = "1",
            ),
        )
        val summary = AgentMemory.summarize(dropped)
        assertTrue(summary.contains("海底捞"))
        assertFalse(summary.contains("副作用ID"))
        assertFalse(summary.contains("nav-123"))
        assertFalse(summary.contains("request_id"))
    }

    @Test
    fun observationToolDoesNotBecomeFact() {
        val dropped = listOf(
            LlmMessage("user", "眼前是什么"),
            LlmMessage(
                "tool",
                """{"ok":true,"observation":true,"store":"海底捞","message":"画面上像是海底捞"}""",
                toolCallId = "1",
            ),
        )
        val summary = AgentMemory.summarize(dropped)
        assertTrue(summary.contains("已确认事实：无"))
        assertFalse(summary.contains("已确认事实：海底捞"))
    }

    @Test
    fun needConfirmIsPendingNotFact() {
        val dropped = listOf(
            LlmMessage("user", "核销"),
            LlmMessage(
                "tool",
                """{"ok":true,"need_confirm":true,"shortName":"巴奴","message":"确认后才核销"}""",
                toolCallId = "1",
            ),
        )
        val summary = AgentMemory.summarize(dropped)
        assertTrue(summary.contains("已确认事实：无"))
        assertTrue(summary.contains("确认后才核销"))
    }
}
