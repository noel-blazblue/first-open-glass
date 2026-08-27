package com.glass.dining.shared.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AgentLoopTest {
    private val read = ToolSpec("search_nearby_places", "search", "{}", ToolRisk.READ, maxRetries = 1)
    private val write = ToolSpec("start_navigation", "nav", "{}", ToolRisk.WRITE)
    private val pay = ToolSpec("checkout", "pay", "{}", ToolRisk.CONFIRM)

    @Test
    fun directReply() {
        val loop = AgentLoop(maxSteps = 3)
        val messages = mutableListOf(LlmMessage("user", "今天天气适合走过去吗"))
        val out = loop.run(
            messages = messages,
            userText = "今天天气适合走过去吗",
            requestModel = { LlmTurn("我没有实时天气，只能按你看到的情况判断。") },
            execute = { ToolResult.fail("should not run") },
            specOf = { null },
        )
        assertEquals("我没有实时天气，只能按你看到的情况判断。", out.speak)
        assertFalse(out.failed)
        assertEquals(1, out.steps)
    }

    @Test
    fun singleToolThenReply() {
        val loop = AgentLoop()
        val messages = mutableListOf(LlmMessage("user", "附近有药店吗"))
        var step = 0
        val out = loop.run(
            messages,
            "附近有药店吗",
            requestModel = {
                step += 1
                if (step == 1) {
                    LlmTurn(toolCalls = listOf(LlmToolCall("1", "search_nearby_places", """{"keyword":"药店"}""")))
                } else {
                    LlmTurn("附近有一家药店，大约200米。")
                }
            },
            execute = { ToolResult.json(true, """{"ok":true,"places":["药店"]}""") },
            specOf = { read },
        )
        assertTrue(out.speak.contains("药店"))
        assertEquals(2, out.steps)
        assertTrue(messages.any { it.role == "tool" })
    }

    @Test
    fun toolFailThenSearchFallback() {
        val loop = AgentLoop()
        val messages = mutableListOf(LlmMessage("user", "导航去海底捞"))
        var step = 0
        val out = loop.run(
            messages,
            "导航去海底捞",
            requestModel = {
                step += 1
                when (step) {
                    1 -> LlmTurn(toolCalls = listOf(LlmToolCall("1", "recommend", "{}")))
                    2 -> LlmTurn(toolCalls = listOf(LlmToolCall("2", "search_nearby_places", """{"keyword":"海底捞"}""")))
                    else -> LlmTurn("开始去海底捞。")
                }
            },
            execute = { call ->
                if (call.name == "recommend") {
                    ToolResult.fail("empty_catalog", retryable = false, code = "empty_catalog")
                } else {
                    ToolResult.json(true, """{"ok":true,"places":[{"name":"海底捞"}]}""")
                }
            },
            specOf = { name -> if (name == "recommend") read.copy(name = "recommend") else read },
        )
        assertTrue(out.speak.contains("海底捞"))
        assertTrue(messages.any { it.role == "tool" && it.content.contains("empty_catalog") })
        assertTrue(messages.any { it.role == "tool" && it.content.contains("海底捞") })
    }

    @Test
    fun budgetStops() {
        val loop = AgentLoop(maxSteps = 2)
        val messages = mutableListOf(LlmMessage("user", "一直搜"))
        val out = loop.run(
            messages,
            "一直搜",
            requestModel = { LlmTurn(toolCalls = listOf(LlmToolCall("x", "search_nearby_places", "{}"))) },
            execute = { ToolResult.json(true, """{"ok":true}""") },
            specOf = { read },
        )
        assertTrue(out.reachedLimit)
        assertEquals(2, out.steps)
    }

    @Test
    fun cancelStops() {
        val loop = AgentLoop()
        val messages = mutableListOf(LlmMessage("user", "取消"))
        val out = loop.run(
            messages,
            "取消",
            requestModel = { LlmTurn("不该到这") },
            execute = { ToolResult.fail("no") },
            specOf = { null },
            cancel = { true },
        )
        assertTrue(out.cancelled)
        assertEquals("", out.speak)
    }

    @Test
    fun payConfirmBlockedWithoutUserYes() {
        val loop = AgentLoop()
        val messages = mutableListOf(LlmMessage("user", "帮我付款"))
        val ran = AtomicInteger(0)
        var step = 0
        val out = loop.run(
            messages,
            "帮我付款",
            requestModel = {
                step += 1
                if (step == 1) {
                    LlmTurn(toolCalls = listOf(LlmToolCall("p", "checkout", """{"confirm":true}""")))
                } else {
                    LlmTurn("还需要你说确认付款。")
                }
            },
            execute = {
                ran.incrementAndGet()
                ToolResult.json(true, """{"ok":true,"paid":true}""")
            },
            specOf = { pay },
        )
        assertEquals(0, ran.get())
        assertTrue(messages.any { it.content.contains("policy_blocked") })
        assertTrue(out.speak.contains("确认"))
    }

    @Test
    fun readToolRetriesOnce() {
        val loop = AgentLoop()
        val messages = mutableListOf(LlmMessage("user", "搜药店"))
        val tries = AtomicInteger(0)
        var step = 0
        loop.run(
            messages,
            "搜药店",
            requestModel = {
                step += 1
                if (step == 1) {
                    LlmTurn(toolCalls = listOf(LlmToolCall("1", "search_nearby_places", "{}")))
                } else {
                    LlmTurn("找到了")
                }
            },
            execute = {
                if (tries.incrementAndGet() == 1) {
                    ToolResult.fail("timeout", retryable = true)
                } else {
                    ToolResult.json(true, """{"ok":true}""")
                }
            },
            specOf = { read },
        )
        assertEquals(2, tries.get())
    }

    @Test
    fun writeToolDoesNotBlindRetry() {
        val loop = AgentLoop()
        val messages = mutableListOf(LlmMessage("user", "出发"))
        val tries = AtomicInteger(0)
        var step = 0
        loop.run(
            messages,
            "出发",
            requestModel = {
                step += 1
                if (step == 1) {
                    LlmTurn(toolCalls = listOf(LlmToolCall("1", "start_navigation", """{"request_id":"nav-1"}""")))
                } else {
                    LlmTurn("路没规划成")
                }
            },
            execute = {
                tries.incrementAndGet()
                ToolResult.fail("amap down", retryable = true)
            },
            specOf = { write },
        )
        assertEquals(1, tries.get())
    }
}
