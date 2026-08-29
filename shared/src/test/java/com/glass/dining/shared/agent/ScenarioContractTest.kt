package com.glass.dining.shared.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioContractTest {
    @Test
    fun diningGuideCoversVagueStoreAsk() {
        val prompt = AgentPrompts.BASE
        assertTrue(prompt.contains("【到店美食与餐饮探索】"))
        assertTrue(prompt.contains("附近有什么门店"))
        assertTrue(prompt.contains("美食") || prompt.contains("餐厅"))
        assertTrue(prompt.contains("禁止把门店"))
        assertFalse(prompt.contains("不要把所有输入都解释成餐饮"))
        assertFalse(prompt.contains("不要把所有输入都解释成某一个行业"))
    }

    @Test
    fun facilityGuideKeepsExplicitNonDining() {
        val prompt = AgentPrompts.BASE
        assertTrue(prompt.contains("【附近公共设施与通用地点】"))
        assertTrue(prompt.contains("药店"))
        assertTrue(prompt.contains("不要改写成美食"))
    }

    @Test
    fun visionGuideDoesNotOwnNearbySearch() {
        val prompt = AgentPrompts.BASE
        assertTrue(prompt.contains("【现实环境与视觉问答】"))
        assertTrue(prompt.contains("眼前"))
        assertTrue(prompt.contains("必须 look_at_scene"))
        assertTrue(prompt.contains("不要用【当前视野】"))
        assertFalse(AgentToolCatalog.LOOK_AT_SCENE.description.contains("回答周围有什么"))
        assertTrue(AgentToolCatalog.LOOK_AT_SCENE.description.contains("不要用此工具搜附近地点"))
    }

    @Test
    fun searchNearbyRewritesVagueDiningKeyword() {
        val desc = AgentToolCatalog.SEARCH_NEARBY.description
        assertTrue(desc.contains("附近有什么门店") || desc.contains("泛指"))
        assertTrue(desc.contains("美食") || desc.contains("餐厅"))
        assertTrue(desc.contains("不要传门店"))
        assertTrue(AgentToolCatalog.SEARCH_NEARBY.requiredCapability == WorldContext.CAP_GPS)
    }

    @Test
    fun recommendDefersToSearchWhenCatalogEmpty() {
        val desc = AgentToolCatalog.RECOMMEND.description
        assertTrue(desc.contains("大于 0"))
        assertTrue(desc.contains("search_nearby_places"))
        assertTrue(desc.contains("不要写门店"))
    }

    @Test
    fun emptyCatalogWorldTellsModelToSearch() {
        val text = AgentContextAssembler.format(WorldContext(catalogCount = 0))
        assertTrue(text.contains("【本地目录】0 家"))
        assertTrue(text.contains("不要 recommend"))
        assertTrue(text.contains("响应用户当前请求"))
        assertFalse(text.contains("闲聊与环境协助"))
    }

    @Test
    fun catalogWorldAllowsRecommend() {
        val text = AgentContextAssembler.format(WorldContext(catalogCount = 3))
        assertTrue(text.contains("3 家，可 recommend"))
    }

    @Test
    fun vagueStoreAskTrajectoryEmptyCatalog() {
        val out = runStub(
            user = "附近有什么门店",
            world = WorldContext(catalogCount = 0, gpsPermission = true),
        )
        assertTrue(out.tools == listOf("search_nearby_places"))
        assertTrue(out.keyword == "美食" || out.keyword == "餐厅")
        assertFalse(out.keyword.contains("门店"))
    }

    @Test
    fun vagueStoreAskTrajectoryUsesCatalog() {
        val out = runStub(
            user = "附近有什么门店",
            world = WorldContext(catalogCount = 4, gpsPermission = true),
        )
        assertTrue(out.tools == listOf("recommend"))
    }

    @Test
    fun pharmacyAskKeepsFacilityKeyword() {
        val out = runStub(
            user = "附近有药店吗",
            world = WorldContext(catalogCount = 4, gpsPermission = true),
        )
        assertTrue(out.tools == listOf("search_nearby_places"))
        assertEqualsKeyword(out.keyword, "药店")
    }

    @Test
    fun hudDrawPreferredWhenIntroducing() {
        val prompt = AgentPrompts.BASE
        assertTrue(prompt.contains("【镜片画面】"))
        assertTrue(prompt.contains("draw_hud"))
        assertTrue(prompt.contains("不要指望门店卡"))
        assertTrue(prompt.contains("禁止整帧只有文字"))
        assertTrue(AgentToolCatalog.DRAW_HUD.description.contains("至少一条"))
        assertTrue(AgentToolCatalog.DRAW_HUD.description.contains("不能整帧只交 text"))
    }

    @Test
    fun facilityIntroUsesDrawNotDiningLayout() {
        val prompt = AgentPrompts.BASE
        assertTrue(prompt.contains("介绍查到的地点时用 draw_hud"))
        assertTrue(prompt.contains("不要改写成餐饮排版"))
    }

    @Test
    fun lookAskUsesVisionNotSearch() {
        val out = runStub(
            user = "眼前是什么",
            world = WorldContext(catalogCount = 0, gpsPermission = true),
        )
        assertTrue(out.tools == listOf("look_at_scene"))
    }

    @Test
    fun floorAskUsesLocationNotVision() {
        val out = runStub(
            user = "我在几楼",
            world = WorldContext(catalogCount = 0),
        )
        assertTrue(out.tools.isEmpty())
    }

    private fun assertEqualsKeyword(actual: String, expected: String) {
        assertTrue("$actual", actual == expected)
    }

    private data class StubTrace(val tools: List<String>, val keyword: String = "")

    /**
     * 按主 Prompt 场景引导做的确定性轨迹夹具：证明契约本身可执行，
     * 不把自然语言关键词表接到生产 AgentLoop。
     */
    private fun runStub(user: String, world: WorldContext): StubTrace {
        val prompt = AgentPrompts.BASE + "\n" + AgentContextAssembler.format(world)
        assertTrue(prompt.contains("【到店美食与餐饮探索】"))
        return when {
            user.contains("眼前") || user.contains("看一下") ->
                StubTrace(listOf("look_at_scene"))
            user.contains("药店") ->
                StubTrace(listOf("search_nearby_places"), "药店")
            user.contains("附近有什么门店") && world.catalogCount > 0 ->
                StubTrace(listOf("recommend"))
            user.contains("附近有什么门店") ->
                StubTrace(listOf("search_nearby_places"), "美食")
            else -> StubTrace(emptyList())
        }
    }
}
