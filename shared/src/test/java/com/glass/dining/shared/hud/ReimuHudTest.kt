package com.glass.dining.shared.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class ReimuHudTest {
    @Test
    fun factoryPoses() {
        assertEquals(HudCard.POSE_IDLE, HudCard.idle().pose)
        assertEquals(HudCard.POSE_LISTEN, HudCard.listening().pose)
        assertEquals(HudCard.POSE_THINK, HudCard.thinking().pose)
        assertEquals(HudCard.POSE_SPEAK, HudCard.talkReply("去哪", "", "右转").pose)
        assertEquals(HudCard.POSE_LOOK, HudCard.shooting().pose)
        assertEquals(HudCard.POSE_LOOK, HudCard.observe("前方路口").pose)
    }

    @Test
    fun inferPoseFromSpeechWhenFieldMissing() {
        assertEquals(TalkPose.THINK, TalkPose.of("", "思考中"))
        assertEquals(TalkPose.LOOK, TalkPose.of("", "正在查看"))
        assertEquals(TalkPose.LISTEN, TalkPose.of("", "Hi, 我在听"))
        assertEquals(TalkPose.SPEAK, TalkPose.of("", "右转五十米"))
        assertEquals(TalkPose.THINK, TalkPose.of("think", "Hi, 我在听"))
    }
}
