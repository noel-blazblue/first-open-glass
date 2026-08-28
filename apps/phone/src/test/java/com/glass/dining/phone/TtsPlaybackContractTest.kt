package com.glass.dining.phone

import com.glass.dining.shared.agent.StreamSpeakAction
import com.glass.dining.shared.agent.StreamingReplyAssembler
import com.glass.dining.shared.agent.TtsBackend
import com.glass.dining.shared.agent.TtsRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机 TTS 假后端契约：不碰 AudioTrack，只断言一轮只开口一次、降级不叠句。
 */
class TtsPlaybackContractTest {
    @Test
    fun streamedHelloIsSpokenOnceEvenIfDoneReplaysFullText() {
        val assembler = StreamingReplyAssembler()
        val spoken = ArrayList<String>()
        spoken += assembler.onPartial("你好！")
        spoken += assembler.onPartial("你好！需要我帮你做点什么吗？")
        when (val done = assembler.onDone("你好！需要我帮你做点什么吗？")) {
            is StreamSpeakAction.Speak -> spoken += done.chunks
            is StreamSpeakAction.Replace -> spoken += done.chunks
        }
        spoken += assembler.onPartial("你好！")
        assertEquals(listOf("你好！", "需要我帮你做点什么吗？"), spoken.filter { it.isNotBlank() })
    }

    @Test
    fun wsFailThenSpeakMoreStaysOnSingleRestQueue() {
        var backend = TtsBackend.WS
        backend = TtsRouter.choose(nlsReady = true, streamingDenied = TtsRouter.stickyDeny("FREE_TRIAL_EXPIRED"), current = TtsBackend.REST)
        assertEquals(TtsBackend.REST, backend)
        val queued = "你好！需要我帮你做点什么吗？"
        val restJobs = ArrayList<String>()
        restJobs += TtsRouter.leftover(queued, "")
        var covered = queued.length
        val (dup, next) = TtsRouter.restSlice(queued, covered)
        covered = next
        if (dup.isNotBlank()) restJobs += dup
        assertEquals(listOf(queued), restJobs)
        assertEquals(queued.length, covered)
    }
}
