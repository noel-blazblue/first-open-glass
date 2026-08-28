package com.glass.dining.shared.agent

import org.junit.Assert.assertEquals
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

    @Test
    fun commitAppendsOnlyCurrentTurn() {
        val prior = listOf(
            LlmMessage("user", "你好"),
            LlmMessage("assistant", "我在。"),
        )
        val loop = listOf(
            LlmMessage("system", "sys"),
            LlmMessage("user", "你好"),
            LlmMessage("assistant", "我在。"),
            LlmMessage("user", "附近有店吗"),
            LlmMessage("assistant", "附近有一家药店。"),
        )
        val next = AgentMemory.commit(prior, "附近有店吗", loop, "附近有一家药店。")
        assertEquals(1, next.count { it.role == "user" && it.content == "你好" })
        assertEquals(1, next.count { it.role == "assistant" && it.content == "我在。" })
        assertEquals(
            listOf("你好", "我在。", "附近有店吗", "附近有一家药店。"),
            next.map { it.content },
        )
    }

    @Test
    fun compactKeepsToolPairsAtomic() {
        val history = (1..6).flatMap { n -> toolTurn("问$n", "c$n", "答$n") }
        val compacted = AgentMemory.compact(history, maxTurns = 3, recentTurns = 2)
        assertToolCallsClosed(compacted)
        val recentTools = compacted.filter { it.role == "tool" }
        assertEquals(2, recentTools.size)
        assertTrue(compacted.first().content.startsWith("【压缩摘要】"))
        assertTrue(compacted.none { it.role == "tool" && it.toolCallId == "c1" })
    }

    @Test
    fun historicTurnsDropToolPayloads() {
        val history = (1..3).flatMap { n -> toolTurn("问$n", "c$n", "答$n") }
        val compacted = AgentMemory.compact(history, maxTurns = 3, recentTurns = 1)
        val turns = AgentMemory.groupTurns(compacted)
        assertEquals(3, turns.size)
        assertTrue(turns[0].followUp.none { it.role == "tool" })
        assertTrue(turns[1].followUp.none { it.role == "tool" })
        assertTrue(turns[2].followUp.any { it.role == "tool" && it.toolCallId == "c3" })
        assertEquals("答1", turns[0].followUp.single { it.role == "assistant" }.content)
    }

    @Test
    fun prepareSanitizesOrphanToolInPriorHistory() {
        val prior = listOf(
            LlmMessage("user", "搜店"),
            LlmMessage("tool", """{"ok":true}""", toolCallId = "orphan"),
            LlmMessage("assistant", "好。"),
        )
        val prepared = AgentMemory.prepare(prior)
        assertTrue(prepared.none { it.role == "tool" })
        assertToolCallsClosed(prepared)
    }

    private fun toolTurn(user: String, callId: String, speak: String): List<LlmMessage> {
        val call = LlmToolCall(callId, "search_nearby_places", "{}")
        return listOf(
            LlmMessage("user", user),
            LlmMessage("assistant", toolCalls = listOf(call)),
            LlmMessage("tool", """{"ok":true,"name":"$user"}""", toolCallId = callId),
            LlmMessage("assistant", speak),
        )
    }

    private fun assertToolCallsClosed(messages: List<LlmMessage>) {
        val sanitized = LlmMessageSanitizer.sanitize(messages)
        assertEquals(messages.map { it.role }, sanitized.map { it.role })
        assertTrue(sanitized.none { it.content.contains("missing_result") })
        var pending = emptySet<String>()
        for (msg in sanitized) {
            when (msg.role) {
                "assistant" -> {
                    assertTrue(pending.isEmpty())
                    pending = msg.toolCalls.map { it.id }.toSet()
                }
                "tool" -> {
                    assertTrue(msg.toolCallId in pending)
                    pending = pending - msg.toolCallId
                }
                else -> assertTrue(pending.isEmpty())
            }
        }
        assertTrue(pending.isEmpty())
    }
}
