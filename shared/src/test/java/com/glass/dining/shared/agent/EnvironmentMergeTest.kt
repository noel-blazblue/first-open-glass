package com.glass.dining.shared.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentMergeTest {
    @Test
    fun speechFactInjectsWithoutWritingScene() {
        val next = EnvironmentMerge.fromSpeech("我在7楼，去海底捞", EnvironmentState(), 1)
        assertEquals("7", next.usableFloor)
        assertTrue(next.userFacts.any { it.contains("7") })
        assertEquals("", next.currentBrief)
        assertTrue(next.promptBlock().contains("【用户确认】"))
    }

    @Test
    fun vlmNoteReplacesSceneAndKeepsRecentChanges() {
        val first = EnvironmentMerge.fromVlmNote(
            "当前场景：室内大厅，前方有服务台。\n相对变化：刚从室外进入室内。",
            EnvironmentState(),
            10,
        )
        assertTrue(first.currentBrief.contains("服务台"))
        assertEquals(1, first.recentChanges.size)
        val second = EnvironmentMerge.fromVlmNote(
            "当前场景：同一大厅，右侧有人排队，墙上屏幕显示取号。\n相对变化：没有明显变化。",
            first,
            20,
        )
        assertTrue(second.currentBrief.contains("取号"))
        assertEquals("刚从室外进入室内。", second.recentChanges.single().text)
    }

    @Test
    fun vlmFailureKeepsPreviousBrief() {
        val prev = EnvironmentState(currentBrief = "旧场景正文", updatedAt = 5)
        assertEquals(prev, EnvironmentMerge.fromVlmNote("", prev, 9))
        assertEquals(prev.currentBrief, EnvironmentMerge.fromVlmNote("相对变化：无", prev, 9).currentBrief)
    }

    @Test
    fun promptBlockIsCappedAndIncludesUserFact() {
        val longScene = "室内公共空间，".repeat(40)
        val state = EnvironmentMerge.fromSpeech(
            "我在7楼",
            EnvironmentState(
                currentBrief = longScene,
                recentChanges = listOf(EnvironmentChange("刚进入室内", 1)),
                updatedAt = 2,
            ),
            3,
        )
        val block = state.promptBlock(limit = 300)
        assertTrue(block.startsWith("【当前环境】"))
        assertTrue(block.contains("【用户确认】"))
        assertTrue(block.length <= 300)
        assertTrue(block.contains("7"))
    }

    @Test
    fun assemblerInjectsEnvironmentBlock() {
        val env = EnvironmentMerge.fromVlmNote(
            "当前场景：用户在室内公共空间，前方有服务台。\n相对变化：刚从室外进入室内。",
            EnvironmentState(),
            1,
        )
        val withSpeech = EnvironmentMerge.fromSpeech("我在7楼", env, 2)
        val text = AgentContextAssembler.format(WorldContext(environment = withSpeech))
        assertTrue(text.contains("【当前视野】"))
        assertTrue(text.contains("服务台"))
        assertTrue(text.contains("【用户所在】"))
        assertTrue(text.contains("用户确认"))
        assertTrue(withSpeech.usableFloor == "7")
    }

    @Test
    fun parseLookReadsStructuredJsonWithoutFloorInScene() {
        val look = EnvironmentMerge.parseLook(
            """{"sceneBrief":"办公桌上的电脑","change":"没有明显变化","objects":["电脑","键盘"],"actions":["操作"],"placeHint":"desk","floorCandidate":"","floorEvidence":"","confidence":0.8}""",
        )
        assertEquals("办公桌上的电脑", look.sceneBrief)
        assertEquals("desk", look.placeHint)
        assertEquals(listOf("电脑", "键盘"), look.objects)
        assertEquals("", look.floorCandidate)
        val env = EnvironmentMerge.fromLook(look, EnvironmentState(), 1)
        assertEquals("办公桌上的电脑", env.currentBrief)
        assertEquals("", env.usableFloor)
    }

    @Test
    fun parseLookReadsNestedObservationNavigationProposal() {
        val look = EnvironmentMerge.parseLook(
            """
            {"observation":{"sceneBrief":"打印机正在出纸","change":"刚走到打印机旁","objects":["打印机"],"actions":["等待"],"location":"文印角落","observedClaims":[{"id":"c1","text":"桌上有打印机","evidence":"scene"}]},"navigation":{"spaceType":"corridor","guideDir":"ahead","storeNames":[],"blocked":false,"floorCandidate":"","floorEvidence":""},"eventProposal":{"operation":"start","eventSummary":"在打印文件","evidenceLevel":"observed","relations":[]},"confidence":0.82}
            """.trimIndent(),
        )
        assertEquals("打印机正在出纸", look.sceneBrief)
        assertEquals("文印角落", look.location)
        assertEquals("corridor", look.spaceType)
        assertEquals("在打印文件", look.eventProposal?.summary)
        assertEquals(EventReducer.OP_START, look.eventProposal?.operation)
        assertEquals("桌上有打印机", look.observedClaims.single().text)
    }

    @Test
    fun parseLookReadsSpaceTypeAndGuide() {
        val look = EnvironmentMerge.parseLook(
            """{"sceneBrief":"走廊分叉","placeHint":"corridor","spaceType":"junction","guideDir":"right","storeNames":["餐饮"],"blocked":false,"confidence":0.7}""",
        )
        assertEquals("junction", look.spaceType)
        assertEquals("right", look.guideDir)
        assertEquals(listOf("餐饮"), look.storeNames)
        assertFalse(look.blocked)
    }

    @Test
    fun returningToDeskKeepsFloorObservation() {
        val floor = EnvironmentMerge.fromLook(
            EnvironmentLook(
                sceneBrief = "走廊墙面的楼层导览",
                placeHint = "signage",
                floorCandidate = "7",
                floorEvidence = "7层导览",
                confidence = 0.85f,
            ),
            EnvironmentState(),
            10,
        )
        val desk = EnvironmentMerge.fromLook(
            EnvironmentLook(sceneBrief = "办公桌上的电脑显示器", placeHint = "desk", confidence = 0.8f),
            floor,
            20,
        )
        assertTrue(desk.currentBrief.contains("电脑"))
        assertEquals("7", desk.usableFloor)
        assertTrue(desk.promptBlock().contains("楼层标识=7"))
        assertTrue(desk.promptBlock().contains("【近期观察】"))
    }

    @Test
    fun salientTextRecoversFloorWithoutWritingScene() {
        val env = EnvironmentMerge.fromLook(
            EnvironmentLook(
                sceneBrief = "墙面上的导视牌",
                salientText = "7层 导览",
                spaceType = "signage",
                confidence = 0.5f,
            ),
            EnvironmentState(),
            1,
        )
        assertEquals("墙面上的导视牌", env.currentBrief)
        assertEquals("7", env.usableFloor)
        assertTrue(env.promptBlock().contains("楼层标识=7"))
        assertTrue(env.promptBlock().contains("可见文字=7层 导览"))
    }

    @Test
    fun oneClearOcrHitIsObservedFloor() {
        val next = EnvironmentMerge.fromSignage(
            listOf("7层 导览"),
            EnvironmentState(),
            3,
        )
        assertEquals("7", next.usableFloor)
        assertEquals(EnvironmentObservation.STATUS_OBSERVED, next.recentObservations.first().status)
    }

    @Test
    fun parseNoteFallsBackToPlainText() {
        val parsed = EnvironmentMerge.parseNote("前方是一条走廊，右侧有电梯门。")
        assertTrue(parsed.scene.contains("走廊"))
        assertEquals("", parsed.change)
    }
}
