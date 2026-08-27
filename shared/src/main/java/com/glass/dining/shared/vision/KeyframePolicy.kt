package com.glass.dining.shared.vision

/**
 * 稀疏关键帧：视频会话里按间隔抽帧，工具调用时复用最近一帧。
 * 不把每一帧送给多模态。重试之间至少隔 [MIN_GAP_MS]，会话抽帧间隔 [SAMPLE_GAP_MS]。
 */
object KeyframePolicy {
    const val MIN_GAP_MS = 700L
    const val SAMPLE_GAP_MS = 1_400L
    const val MAX_RETRIES = 3
    const val FRESH_FRAME_MS = 2_000L

    fun allowSample(nowMs: Long, lastSampleMs: Long, gapMs: Long = MIN_GAP_MS): Boolean {
        if (lastSampleMs <= 0L) return true
        return nowMs - lastSampleMs >= gapMs
    }

    fun waitMs(nowMs: Long, lastSampleMs: Long, gapMs: Long = MIN_GAP_MS): Long {
        if (allowSample(nowMs, lastSampleMs, gapMs)) return 0L
        return (gapMs - (nowMs - lastSampleMs)).coerceAtLeast(0L)
    }
}
