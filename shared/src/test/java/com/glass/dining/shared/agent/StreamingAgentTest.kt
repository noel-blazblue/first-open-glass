package com.glass.dining.shared.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTurnAssemblerTest {
    @Test
    fun fragmentsToolArgumentsByIndex() {
        val assembler = StreamingTurnAssembler()
        assembler.accept(LlmStreamDelta(toolCalls = listOf(LlmToolCallDelta(0, "call-a", "search_nearby_places", ""))))
        assembler.accept(LlmStreamDelta(toolCalls = listOf(LlmToolCallDelta(0, argumentsFragment = "{\"key"))))
        assembler.accept(LlmStreamDelta(toolCalls = listOf(LlmToolCallDelta(1, "call-b", "look_at_scene", "{"))))
        assembler.accept(LlmStreamDelta(toolCalls = listOf(LlmToolCallDelta(0, argumentsFragment = "word\":\"药店\"}"))))
        assembler.accept(LlmStreamDelta(toolCalls = listOf(LlmToolCallDelta(1, argumentsFragment = "}"))))
        assembler.accept(LlmStreamDelta(done = true, finishReason = "tool_calls"))
        val turn = assembler.finish()
        assertEquals(StreamKind.TOOLS, assembler.kind)
        assertEquals(2, turn.toolCalls.size)
        assertEquals("search_nearby_places", turn.toolCalls[0].name)
        assertTrue(turn.toolCalls[0].argumentsJson.contains("药店"))
        assertEquals("look_at_scene", turn.toolCalls[1].name)
    }

    @Test
    fun whitespaceContentWithToolsIsNotMixed() {
        val assembler = StreamingTurnAssembler()
        assembler.accept(LlmStreamDelta(content = "  \n"))
        assembler.accept(LlmStreamDelta(toolCalls = listOf(LlmToolCallDelta(0, "1", "look_at_scene", "{}"))))
        assertEquals(StreamKind.TOOLS, assembler.kind)
        assertFalse(assembler.mixed)
    }

    @Test
    fun contentThenToolsIsMixed() {
        val assembler = StreamingTurnAssembler()
        assembler.accept(LlmStreamDelta(content = "附近有三家店。"))
        assembler.accept(LlmStreamDelta(toolCalls = listOf(LlmToolCallDelta(0, "1", "search_nearby_places", "{}"))))
        assertTrue(assembler.mixed)
        val turn = assembler.finish()
        assertEquals("附近有三家店。", turn.content.trim())
        assertEquals(1, turn.toolCalls.size)
    }
}

class LlmSseParserTest {
    @Test
    fun parsesContentReasoningAndDone() {
        val delta = LlmSseParser.parseLine(
            """data: {"choices":[{"delta":{"content":"你好","reasoning_content":"think"}}]}""",
        )
        assertEquals("你好", delta?.content)
        assertEquals("think", delta?.reasoning)
        assertFalse(delta?.done == true)
        val done = LlmSseParser.parsePayload("[DONE]")
        assertTrue(done?.done == true)
    }

    @Test
    fun parsesFragmentedToolCalls() {
        val delta = LlmSseParser.parsePayload(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"search_nearby_places","arguments":"{k"}}]},"finish_reason":"tool_calls"}]}""",
        )
        assertEquals(1, delta?.toolCalls?.size)
        assertEquals("search_nearby_places", delta?.toolCalls?.first()?.name)
        assertEquals("c1", delta?.toolCalls?.first()?.id)
        assertTrue(delta?.done == true)
    }

    @Test
    fun ignoresNonDataLines() {
        assertNull(LlmSseParser.parseLine("event: message"))
        assertNull(LlmSseParser.parseLine(""))
    }
}

class StreamingTextSegmenterTest {
    @Test
    fun splitsOnPunctuation() {
        val seg = StreamingTextSegmenter()
        val first = seg.consume("附近找到三家。你想去哪一家？")
        assertEquals(listOf("附近找到三家。", "你想去哪一家？"), first)
    }

    @Test
    fun idleFlushesUnpunctuatedChinese() {
        val seg = StreamingTextSegmenter(maxChars = 36, idleFlushChars = 8)
        assertTrue(seg.consume("今天天气还不错先走走看").isEmpty())
        val flushed = seg.consume("", idle = true)
        assertEquals(listOf("今天天气还不错先走走看"), flushed)
    }

    @Test
    fun finishFlushesRemainder() {
        val seg = StreamingTextSegmenter()
        val out = seg.consume("先往前走") + seg.finish()
        assertEquals(listOf("先往前走"), out)
    }

    @Test
    fun staleShorterPartialDoesNotReplay() {
        val seg = StreamingTextSegmenter()
        assertEquals(listOf("你好！"), seg.consume("你好！"))
        assertEquals(listOf("需要我帮你做点什么吗？"), seg.consume("你好！需要我帮你做点什么吗？") + seg.finish())
        assertTrue(seg.consume("你好！").isEmpty())
        assertTrue(seg.finish().isEmpty())
    }

    @Test
    fun forkIsIgnoredUntilReset() {
        val seg = StreamingTextSegmenter()
        assertEquals(listOf("你好！"), seg.consume("你好！"))
        assertTrue(seg.consume("我这次没回上，再说一次。").isEmpty())
        assertTrue(seg.finish().isEmpty())
    }
}

class StreamingReplyAssemblerTest {
    @Test
    fun doneAfterDeltasOnlyFlushesRemainder() {
        val assembler = StreamingReplyAssembler()
        val first = assembler.onPartial("你好！")
        val done = assembler.onDone("你好！需要我帮你做点什么吗？")
        assertEquals(listOf("你好！"), first)
        val spoken = (done as StreamSpeakAction.Speak).chunks
        assertEquals(listOf("需要我帮你做点什么吗？"), spoken)
    }

    @Test
    fun lateShorterPartialAfterDoneIsIgnored() {
        val assembler = StreamingReplyAssembler()
        assembler.onPartial("你好！")
        assembler.onDone("你好！需要我帮你做点什么吗？")
        assertTrue(assembler.onPartial("你好！").isEmpty())
        assertTrue(assembler.onPartial("你好！需要我帮你做点什么吗？").isEmpty())
    }

    @Test
    fun identicalFinalDoesNotRepeat() {
        val assembler = StreamingReplyAssembler()
        val chunks = ArrayList<String>()
        chunks += assembler.onPartial("你好！需要我帮你做点什么吗？")
        val done = assembler.onDone("你好！需要我帮你做点什么吗？")
        chunks += (done as StreamSpeakAction.Speak).chunks
        assertEquals(listOf("你好！", "需要我帮你做点什么吗？"), chunks)
    }

    @Test
    fun mixedFallbackReplacesSpokenPrefix() {
        val assembler = StreamingReplyAssembler()
        assertEquals(listOf("附近有三家。"), assembler.onPartial("附近有三家。"))
        val done = assembler.onDone("我这次没回上，再说一次。")
        assertTrue(done is StreamSpeakAction.Replace)
        assertEquals(listOf("我这次没回上，", "再说一次。"), (done as StreamSpeakAction.Replace).chunks)
    }

    @Test
    fun toolStepThenFinalContinuesWithoutReplace() {
        val assembler = StreamingReplyAssembler()
        assertEquals(listOf("好的，", "帮你看下。"), assembler.onPartial("好的，帮你看下。"))
        assertTrue(assembler.finishStep().isEmpty())
        val second = assembler.onPartial("附近有一家药店。")
        val done = assembler.onDone("附近有一家药店。")
        assertEquals(listOf("附近有一家药店。"), second)
        assertTrue(done is StreamSpeakAction.Speak)
        assertTrue((done as StreamSpeakAction.Speak).chunks.isEmpty())
    }
}

class TtsRouterTest {
    @Test
    fun wsFailurePinsRestForTheSameTurn() {
        assertEquals(TtsBackend.WS, TtsRouter.choose(nlsReady = true, streamingDenied = false, current = TtsBackend.NONE))
        assertEquals(TtsBackend.REST, TtsRouter.choose(nlsReady = true, streamingDenied = false, current = TtsBackend.REST))
        assertEquals(TtsBackend.REST, TtsRouter.choose(nlsReady = true, streamingDenied = true, current = TtsBackend.NONE))
        assertEquals(TtsBackend.ANDROID, TtsRouter.choose(nlsReady = false, streamingDenied = false, current = TtsBackend.NONE))
    }

    @Test
    fun leftoverSkipsAlreadyAudiblePrefix() {
        assertEquals("需要我帮你做点什么吗？", TtsRouter.leftover("你好！需要我帮你做点什么吗？", "你好！"))
        assertEquals("你好！需要我帮你做点什么吗？", TtsRouter.leftover("你好！需要我帮你做点什么吗？", ""))
        assertEquals("", TtsRouter.leftover("你好！", "你好！需要"))
        assertEquals("需要我", TtsRouter.leftover("你好！需要我", "你好", "你好！"))
    }

    @Test
    fun restSliceDropsInflightDuplicateAfterFallback() {
        val queued = "你好！需要我帮你做点什么吗？"
        val leftover = TtsRouter.leftover(queued, "")
        val covered = queued.length
        assertEquals(queued, leftover)
        assertEquals("" to queued.length, TtsRouter.restSlice(queued, covered))
        assertEquals("需要我帮你做点什么吗？" to queued.length, TtsRouter.restSlice(queued, "你好！".length))
    }

    @Test
    fun trialExpiredIsStickyDeny() {
        assertTrue(TtsRouter.stickyDeny("Gateway:FREE_TRIAL_EXPIRED"))
        assertTrue(TtsRouter.stickyDeny("permission denied"))
        assertFalse(TtsRouter.stickyDeny("timeout"))
    }
}

class StreamingSynthGateTest {
    @Test
    fun finishBeforeStartSendsStopAfterStarted() {
        val gate = StreamingSynthGate()
        assertFalse(gate.requestStop())
        assertTrue(gate.pendingStop)
        assertTrue(gate.onStarted())
        assertFalse(gate.pendingStop)
        assertTrue(gate.acceptText())
    }

    @Test
    fun completedRejectsLateText() {
        val gate = StreamingSynthGate()
        gate.onStarted()
        gate.onCompleted()
        assertFalse(gate.acceptText())
        assertFalse(gate.requestStop())
    }
}

class TtsSessionTest {
    @Test
    fun navPreemptsAgentAndAgentCannotPreemptNav() {
        val session = TtsSession()
        val agent = session.begin(SpeechPriority.AGENT, flush = true)
        session.appendQueued("普通回答")
        assertEquals(TtsPlayState.SYNTHESIZING, session.state)
        val nav = session.begin(SpeechPriority.NAV, flush = true)
        assertTrue(nav != null && nav != agent)
        assertEquals(SpeechPriority.NAV, session.priority)
        assertNull(session.begin(SpeechPriority.AGENT, flush = true))
        session.markAudible(session.generation, "右转八十米")
        assertEquals("右转八十米", session.echoWindow)
        val old = session.generation
        session.bump()
        session.markAudible(old, "复活")
        assertEquals("", session.echoWindow)
        assertEquals(TtsPlayState.IDLE, session.state)
    }

    @Test
    fun unplayedUsesAudibleBoundary() {
        val session = TtsSession()
        session.begin(SpeechPriority.AGENT, flush = true)
        session.appendQueued("你好！需要我帮你做点什么吗？")
        assertEquals("你好！需要我帮你做点什么吗？", session.unplayed())
        session.markAudible(session.generation, "你好！")
        assertEquals("需要我帮你做点什么吗？", session.unplayed())
    }

    @Test
    fun idleIsIdempotent() {
        val session = TtsSession()
        val gen = session.begin(SpeechPriority.AGENT, flush = true) ?: 0
        session.markIdle(gen)
        session.markIdle(gen)
        assertEquals(TtsPlayState.IDLE, session.state)
    }

    @Test
    fun synthesizingIsNotEchoWindow() {
        val session = TtsSession()
        session.begin(SpeechPriority.AGENT, flush = true)
        session.appendQueued("尚未发声的全文去第一家吧")
        assertTrue(session.speaking)
        assertEquals("", session.echoWindow)
    }
}

class TtsJobQueueTest {
    @Test
    fun prefetchesTwoAndPlaysInOrder() {
        val queue = TtsJobQueue(maxPrefetch = 2)
        queue.enqueue("一")
        queue.enqueue("二")
        queue.enqueue("三")
        val first = queue.toSynthesize()
        assertEquals(2, first.size)
        first.forEach { it.synthesizing = true }
        assertTrue(queue.toSynthesize().isEmpty())
        first[0].pcm = byteArrayOf(1)
        assertEquals("一", queue.nextPlayable()?.text)
        queue.markPlayed(first[0].index)
        assertNull(queue.nextPlayable())
        first[1].pcm = byteArrayOf(2)
        assertEquals("二", queue.nextPlayable()?.text)
        assertEquals("二三", queue.unplayedText())
    }
}

class TtsTimelineTest {
    @Test
    fun usesWordTimestampsNotFutureText() {
        val words = listOf(
            SubtitleWord("今", 0, 175, 0, 1),
            SubtitleWord("天", 175, 320, 1, 2),
            SubtitleWord("去", 320, 480, 2, 3),
        )
        assertEquals("今", TtsTimeline.audiblePrefix(100, words, "今天去"))
        assertEquals("今天", TtsTimeline.audiblePrefix(200, words, "今天去"))
        assertEquals("今", TtsTimeline.estimatePrefix(1, 3, "今天去"))
    }
}
