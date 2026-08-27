package com.glass.dining.shared.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnvironmentEventTrackerTest {
    @Test
    fun coffeeFixtureComesFromProposalNotKeywords() {
        var env = EnvironmentState()
        env = ingest(
            env,
            10,
            look("工位上的电脑和键盘", objects = listOf("电脑")),
            proposal(EventReducer.OP_START, "在工位操作电脑"),
        )
        assertEquals("在工位操作电脑", env.activeEvent?.summary)
        assertEquals(EnvironmentObservation.STATUS_OBSERVED, env.activeEvent?.confidence)

        env = ingest(
            env,
            20,
            look("办公区走廊"),
            proposal(
                EventReducer.OP_CONTINUE,
                "离开工位并移动中",
                level = EnvironmentObservation.STATUS_HYPOTHESIS,
                hypothesis = "离开工位并移动中",
            ),
        )
        assertEquals("离开工位并移动中", env.activeEvent?.summary)
        assertEquals(EnvironmentObservation.STATUS_HYPOTHESIS, env.activeEvent?.confidence)

        env = ingest(
            env,
            40,
            look("咖啡机和杯子，正在接咖啡", objects = listOf("咖啡机", "杯子"), actions = listOf("接")),
            proposal(
                EventReducer.OP_CONTINUE,
                "从工位起身去接咖啡",
                level = EnvironmentObservation.STATUS_INFERRED,
            ),
        )
        assertEquals("从工位起身去接咖啡", env.activeEvent?.summary)
        assertEquals(EnvironmentObservation.STATUS_INFERRED, env.activeEvent?.confidence)
        val block = env.promptBlock()
        assertTrue(block.contains("根据连续证据判断"))
        assertFalse(block.contains("从工位起身去接咖啡（观察到）"))

        env = ingest(
            env,
            50,
            look("回到工位电脑前", objects = listOf("电脑")),
            proposal(EventReducer.OP_TRANSITION, "回到工位操作电脑"),
        )
        assertEquals("回到工位操作电脑", env.activeEvent?.summary)
        assertTrue(env.recentEvents.any { it.summary.contains("接咖啡") })
    }

    @Test
    fun meetingFixtureRevisesPriorHypothesis() {
        var env = EnvironmentState()
        env = ingest(env, 10, look("电脑"), proposal(EventReducer.OP_START, "在工位操作电脑"))
        env = ingest(
            env,
            20,
            look("走廊"),
            proposal(
                EventReducer.OP_CONTINUE,
                "离开工位并移动中",
                level = EnvironmentObservation.STATUS_HYPOTHESIS,
                hypothesis = "可能去接咖啡",
            ),
        )
        val before = env.activeEvent!!.id
        env = ingest(
            env,
            30,
            look("会议室白板和投影", objects = listOf("投影", "白板")),
            proposal(
                EventReducer.OP_REVISE,
                "从工位前往会议区域",
                level = EnvironmentObservation.STATUS_INFERRED,
            ),
        )
        assertEquals("从工位前往会议区域", env.activeEvent?.summary)
        assertEquals(before, env.activeEvent?.id)
        assertFalse(env.activeEvent?.summary.orEmpty().contains("咖啡"))
        assertFalse(env.recentEvents.any { it.summary.contains("咖啡") })
        assertEquals(EnvironmentObservation.STATUS_INFERRED, env.activeEvent?.confidence)
    }

    @Test
    fun keywordsAloneDoNotInventCoffeeActivity() {
        var env = EnvironmentState()
        env = ingest(env, 10, look("角落有一台咖啡机和纸杯"))
        assertFalse(env.activeEvent?.summary.orEmpty().contains("从工位起身去接咖啡"))
        assertTrue(env.activeEvent?.summary.orEmpty().contains("咖啡机"))
        env = ingest(env, 20, look("会议室白板"))
        assertFalse(env.activeEvent?.summary.orEmpty().contains("前往会议区域"))
    }

    @Test
    fun unknownActivitiesNeedNoNewBranches() {
        var env = EnvironmentState()
        env = ingest(env, 10, look("桌上的打印机正在出纸"), proposal(EventReducer.OP_START, "在打印文件"))
        env = ingest(
            env,
            20,
            look("快递柜屏幕和取件码"),
            proposal(EventReducer.OP_TRANSITION, "去快递柜取件"),
        )
        assertEquals("去快递柜取件", env.activeEvent?.summary)
        assertTrue(env.recentEvents.any { it.summary == "在打印文件" })

        env = ingest(env, 30, look("楼梯间"), proposal(EventReducer.OP_TRANSITION, "沿楼梯上楼"))
        env = ingest(env, 40, look("墙上导览图"), proposal(EventReducer.OP_TRANSITION, "在阅读楼层导览"))
        env = ingest(env, 50, look("饮水机和杯子"), proposal(EventReducer.OP_TRANSITION, "接了一杯水"))
        env = ingest(
            env,
            60,
            look("从未见过的量子园艺展览"),
            proposal(EventReducer.OP_TRANSITION, "在看量子园艺展览"),
        )
        assertEquals("在看量子园艺展览", env.activeEvent?.summary)
        assertEquals(EventReducer.MAX_RECENT, env.recentEvents.size)
        assertTrue(env.recentEvents.any { it.summary == "接了一杯水" })
    }

    @Test
    fun corridorNodesDoNotFragmentEvents() {
        var env = EnvironmentState()
        val summary = "沿走廊前进"
        env = ingest(
            env,
            10,
            look("长走廊"),
            proposal(EventReducer.OP_START, summary),
            SpatialEvidence(episodeId = "e1", topologyNodeId = "n1", tracking = "good", movedMeters = 2.4f),
        )
        val id = env.activeEvent!!.id
        env = ingest(
            env,
            20,
            look("同一走廊更远处"),
            proposal(EventReducer.OP_START, summary),
            SpatialEvidence(episodeId = "e2", topologyNodeId = "n2", tracking = "good", movedMeters = 3.1f),
        )
        env = ingest(
            env,
            30,
            look("走廊转弯"),
            proposal(EventReducer.OP_START, summary),
            SpatialEvidence(episodeId = "e3", topologyNodeId = "n3", tracking = "good", movedMeters = 2.8f),
        )
        assertEquals(id, env.activeEvent?.id)
        assertEquals(3, env.activeEvent?.episodeCount)
        assertEquals(emptyList<EnvironmentEvent>(), env.recentEvents)
        assertTrue(env.activeEvent?.episodeIds?.containsAll(listOf("e1", "e2", "e3")) == true)
    }

    @Test
    fun vioDisplacementSupportsContinues() {
        var env = EnvironmentState()
        env = ingest(
            env,
            10,
            look("大厅"),
            proposal(EventReducer.OP_START, "穿过大厅"),
            SpatialEvidence(episodeId = "e1", poseX = 0f, poseY = 0f, tracking = "good"),
        )
        env = ingest(
            env,
            20,
            look("大厅另一侧"),
            proposal(
                EventReducer.OP_CONTINUE,
                "穿过大厅",
                relations = listOf(
                    EventRelation(
                        relatedEventId = env.activeEvent!!.id,
                        type = EventReducer.REL_CONTINUES,
                        evidenceRefs = listOf("pose", "e2"),
                    ),
                ),
            ),
            SpatialEvidence(
                episodeId = "e2",
                poseX = 0f,
                poseY = 4.2f,
                tracking = "good",
                movedMeters = 4.2f,
            ),
        )
        val rel = env.activeEvent!!.relations.single { it.type == EventReducer.REL_CONTINUES }
        assertEquals(env.activeEvent?.id, rel.relatedEventId)
        assertTrue(rel.evidenceRefs.contains("pose"))
    }

    @Test
    fun loopClosureSupportsReturnsTo() {
        var env = EnvironmentState()
        env = ingest(
            env,
            10,
            look("门口"),
            proposal(EventReducer.OP_START, "从门口走进去"),
            SpatialEvidence(episodeId = "e1", topologyNodeId = "n1", tracking = "good"),
        )
        val firstId = env.activeEvent!!.id
        env = ingest(
            env,
            20,
            look("又回到门口"),
            proposal(
                EventReducer.OP_CONTINUE,
                "从门口走进去",
                relations = listOf(
                    EventRelation(
                        relatedEventId = firstId,
                        type = EventReducer.REL_RETURNS,
                        evidenceRefs = listOf("loop"),
                    ),
                ),
            ),
            SpatialEvidence(
                episodeId = "e2",
                topologyNodeId = "n1",
                loopClosed = true,
                tracking = "good",
                movedMeters = 8f,
            ),
        )
        val rel = env.activeEvent!!.relations.single { it.type == EventReducer.REL_RETURNS }
        assertEquals(firstId, rel.relatedEventId)
        assertEquals(EnvironmentObservation.STATUS_OBSERVED, rel.evidenceLevel)
        assertTrue(rel.evidenceRefs.contains("loop"))
    }

    @Test
    fun trackingLostRejectsMovementRelation() {
        var env = EnvironmentState()
        env = ingest(
            env,
            10,
            look("走廊起点"),
            proposal(EventReducer.OP_START, "沿走廊前进"),
            SpatialEvidence(episodeId = "e1", tracking = "good"),
        )
        val id = env.activeEvent!!.id
        env = ingest(
            env,
            20,
            look("画面抖动"),
            proposal(
                EventReducer.OP_CONTINUE,
                "沿走廊前进",
                relations = listOf(
                    EventRelation(
                        relatedEventId = id,
                        type = EventReducer.REL_CONTINUES,
                        evidenceRefs = listOf("pose"),
                    ),
                ),
            ),
            SpatialEvidence(episodeId = "e2", tracking = "tracking_lost", movedMeters = 3f),
        )
        assertEquals(id, env.activeEvent?.id)
        assertTrue(env.activeEvent!!.relations.none { it.type == EventReducer.REL_CONTINUES })
    }

    @Test
    fun invalidProposalKeepsObservedEvidence() {
        var env = EnvironmentState()
        env = ingest(
            env,
            10,
            look(
                "正在装订样本",
                claims = listOf(ObservedClaim(id = "c1", text = "桌上有装订机", evidence = "scene")),
            ),
            EventProposal(operation = "teleport", summary = "瞬移去月球"),
        )
        assertEquals("正在装订样本", env.activeEvent?.summary)
        assertNotEquals("瞬移去月球", env.activeEvent?.summary)
        assertTrue(env.activeEvent!!.observedClaims.any { it.text.contains("装订机") })
    }

    @Test
    fun destinationHintIsNotEventIntent() {
        var env = EnvironmentState()
        env = ingest(
            env,
            10,
            look("走廊"),
            proposal(EventReducer.OP_START, "沿走廊前进", location = "走廊"),
            SpatialEvidence(episodeId = "e1", topologyNodeId = "n2", tracking = "good"),
        )
        assertEquals("沿走廊前进", env.activeEvent?.summary)
        assertEquals("走廊", env.activeEvent?.location)
        assertEquals("n2", env.activeEvent?.topologyNodeId)
    }

    @Test
    fun productionSourcesHaveNoClosedActivityOntology() {
        val banned = listOf(
            "PLACE_COFFEE",
            "PLACE_DESK",
            "PLACE_MEETING",
            "hasCoffee",
            "hasMeeting",
            "hasDesk",
            "从工位起身去接咖啡",
            "前往会议区域",
            "在工位操作电脑",
            "desk|corridor|coffee|meeting",
        )
        val files = listOf(
            "EnvironmentEventTracker.kt",
            "EventReducer.kt",
            "EventProposal.kt",
            "EventAnalysis.kt",
            "AgentToolCatalog.kt",
        )
        files.forEach { name ->
            val text = readAgentSrc(name)
            banned.forEach { token ->
                assertFalse("$name still contains $token", text.contains(token))
            }
        }
    }

    private fun ingest(
        prev: EnvironmentState,
        now: Long,
        look: EnvironmentLook,
        proposal: EventProposal? = null,
        spatial: SpatialEvidence = SpatialEvidence(),
    ): EnvironmentState {
        val withProposal = look.copy(eventProposal = proposal ?: look.eventProposal)
        val withScene = EnvironmentMerge.fromLook(withProposal.copy(confidence = 0.8f), prev, now)
        return EnvironmentEventTracker.ingest(withProposal, withScene, now, spatial)
    }

    private fun look(
        scene: String,
        objects: List<String> = emptyList(),
        actions: List<String> = emptyList(),
        claims: List<ObservedClaim> = emptyList(),
    ): EnvironmentLook {
        return EnvironmentLook(
            sceneBrief = scene,
            objects = objects,
            actions = actions,
            observedClaims = claims,
            confidence = 0.8f,
        )
    }

    private fun proposal(
        operation: String,
        summary: String,
        level: String = EnvironmentObservation.STATUS_OBSERVED,
        hypothesis: String = "",
        location: String = "",
        relations: List<EventRelation> = emptyList(),
    ): EventProposal {
        return EventProposal(
            operation = operation,
            summary = summary,
            hypothesis = hypothesis,
            evidenceLevel = level,
            location = location,
            relations = relations,
        )
    }

    private fun readAgentSrc(name: String): String {
        val candidates = listOf(
            File("src/main/java/com/glass/dining/shared/agent/$name"),
            File("shared/src/main/java/com/glass/dining/shared/agent/$name"),
        )
        return candidates.first { it.exists() }.readText()
    }
}
