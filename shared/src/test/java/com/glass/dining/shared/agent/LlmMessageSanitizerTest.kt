package com.glass.dining.shared.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmMessageSanitizerTest {
    @Test
    fun dropsOrphanTool() {
        val cleaned = LlmMessageSanitizer.sanitize(
            listOf(
                LlmMessage("user", "附近有店吗"),
                LlmMessage("tool", """{"ok":true}""", toolCallId = "orphan"),
                LlmMessage("assistant", "再说一次"),
            ),
        )
        assertEquals(listOf("user", "assistant"), cleaned.map { it.role })
    }

    @Test
    fun padsMissingToolResults() {
        val call = LlmToolCall("c1", "search_nearby_places", "{}")
        val cleaned = LlmMessageSanitizer.sanitize(
            listOf(
                LlmMessage("user", "搜一下"),
                LlmMessage("assistant", toolCalls = listOf(call)),
                LlmMessage("user", "还在吗"),
            ),
        )
        assertEquals(listOf("user", "assistant", "tool", "user"), cleaned.map { it.role })
        assertEquals("c1", cleaned[2].toolCallId)
        assertTrue(cleaned[2].content.contains("missing_result"))
    }

    @Test
    fun keepsClosedToolPair() {
        val call = LlmToolCall("c1", "search_nearby_places", "{}")
        val input = listOf(
            LlmMessage("user", "搜药店"),
            LlmMessage("assistant", toolCalls = listOf(call)),
            LlmMessage("tool", """{"ok":true,"name":"药店"}""", toolCallId = "c1"),
            LlmMessage("assistant", "附近有一家药店。"),
        )
        assertEquals(input, LlmMessageSanitizer.sanitize(input))
    }

    @Test
    fun bindsBlankToolIdWhenOnlyOneCallOpen() {
        val call = LlmToolCall("c1", "look_at_scene", "{}")
        val cleaned = LlmMessageSanitizer.sanitize(
            listOf(
                LlmMessage("assistant", toolCalls = listOf(call)),
                LlmMessage("tool", """{"ok":true}"""),
            ),
        )
        assertEquals("c1", cleaned.last().toolCallId)
    }
}
