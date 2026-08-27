package com.glass.dining.shared.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoFilterTest {
    @Test
    fun ttsPlaybackHeardBackIsEcho() {
        val spoken = "开始去海底捞，顺着路走大约三百米。"
        assertTrue(EchoFilter.isEcho("开始去海底捞", spoken))
        assertTrue(EchoFilter.isEcho("顺着路走大约三百米", spoken))
        assertTrue(EchoFilter.isEcho("开始去海底捞顺着路走", spoken))
    }

    @Test
    fun differentUserUtteranceIsNotEcho() {
        val spoken = "开始去海底捞，顺着路走大约三百米。"
        assertFalse(EchoFilter.isEcho("不对去巴奴", spoken))
        assertFalse(EchoFilter.isEcho("我在七楼", spoken))
        assertTrue(EchoFilter.isUserPartial("不对去巴奴", spoken))
    }

    @Test
    fun shortPartialIsNotUserBarge() {
        val spoken = "开始去海底捞"
        assertFalse(EchoFilter.isUserPartial("开", spoken))
        assertFalse(EchoFilter.isUserPartial("的", spoken))
    }

    @Test
    fun blankHeardCountsAsEcho() {
        assertTrue(EchoFilter.isEcho("  ", "开始走"))
        assertFalse(EchoFilter.isEcho("现在去几楼", ""))
    }

    @Test
    fun shortSharedCharsAreNotEcho() {
        val spoken = "附近找到3家海底捞，你想去哪一家？"
        assertFalse(EchoFilter.isEcho("去第一家。", spoken))
        assertTrue(EchoFilter.isUserPartial("去第一家。", spoken))
    }
}
