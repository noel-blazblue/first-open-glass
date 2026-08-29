package com.glass.dining.shared.agent

import com.glass.dining.shared.place.PlaceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextAssemblerTest {
    @Test
    fun navigatingToHaidilaoIsDestinationNotCurrentPlace() {
        val env = EnvironmentMerge.fromLook(
            EnvironmentLook(sceneBrief = "办公桌上的电脑显示器", placeHint = "desk", confidence = 0.8f),
            EnvironmentState(),
            10,
        )
        val text = AgentContextAssembler.format(
            WorldContext(
                boundPlace = place("海底捞火锅"),
                environment = env,
                navActive = true,
                gpsPermission = true,
                hasGpsFix = true,
            ),
        )
        assertTrue(text.contains("【当前任务】正在步行导航；目的地=海底捞火锅"))
        assertTrue(text.contains("【业务对象】海底捞火锅（角色：导航目的地；不是用户当前位置）"))
        assertTrue(text.contains("语义地点=未知"))
        assertTrue(text.contains("【当前视野】办公桌上的电脑显示器"))
        assertFalse(text.contains("当前地点"))
        assertFalse(text.contains("当前地点=海底捞"))
        assertFalse(text.contains("已晋级事实"))
    }

    @Test
    fun viewingWithoutBindIsNotBusinessObject() {
        val text = AgentContextAssembler.format(
            WorldContext(
                viewingPlace = place("西红柿韩国料理"),
                viewingProfile = place("西红柿韩国料理").asProfile(rating = 4.5, avgCost = 80),
            ),
        )
        assertTrue(text.contains("【正在查看】西红柿韩国料理"))
        assertTrue(text.contains("不是导航目的地"))
        assertFalse(text.contains("【业务对象】"))
        assertTrue(text.contains("【门店资料】"))
    }

    @Test
    fun browsingStoreIsViewingNotLocation() {
        val text = AgentContextAssembler.format(
            WorldContext(boundPlace = place("海底捞火锅")),
        )
        assertTrue(text.contains("当前查看门店"))
        assertTrue(text.contains("不是用户当前位置"))
        assertFalse(text.contains("当前地点"))
        assertFalse(text.contains("【门店资料】"))
    }

    @Test
    fun boundAmapPlaceExposesAnswerableFacts() {
        val text = AgentContextAssembler.format(
            WorldContext(
                boundPlace = PlaceRef(
                    id = "B0HGUAEBKL",
                    name = "潮黄记·潮汕鲜牛肉火锅(望京店)",
                    address = "望京东园1区120号楼",
                    floor = "2F",
                ),
                boundProfile = PlaceRef(
                    id = "B0HGUAEBKL",
                    name = "潮黄记·潮汕鲜牛肉火锅(望京店)",
                    address = "望京东园1区120号楼",
                    floor = "2F",
                ).asProfile(
                    rating = 4.7,
                    avgCost = 152,
                    tel = "13581699848",
                    hoursToday = "11:00-23:00",
                    businessArea = "望京",
                ),
            ),
        )
        assertTrue(text.contains("【门店资料】"))
        assertTrue(text.contains("评分=4.7"))
        assertTrue(text.contains("人均=152"))
        assertTrue(text.contains("营业时间=11:00-23:00"))
        assertTrue(text.contains("电话=13581699848"))
        assertTrue(text.contains("楼层=2F"))
    }

    @Test
    fun menuAndPayUseServingRole() {
        val menu = AgentContextAssembler.format(
            WorldContext(boundPlace = place("巴奴")),
        )
        val pay = AgentContextAssembler.format(
            WorldContext(boundPlace = place("巴奴"), pendingPay = "待付88元给巴奴"),
        )
        assertTrue(menu.contains("当前查看门店"))
        assertTrue(pay.contains("当前服务门店"))
        assertTrue(pay.contains("待付88元给巴奴"))
        assertFalse(menu.contains("导航目的地"))
    }

    @Test
    fun stoppingNavKeepsBoundPlaceAsViewing() {
        val text = AgentContextAssembler.format(
            WorldContext(boundPlace = place("海底捞火锅"), navActive = false),
        )
        assertTrue(text.contains("当前查看门店"))
        assertFalse(text.contains("正在步行导航"))
        assertTrue(text.contains("当前查看门店"))
    }

    @Test
    fun gpsPermissionWithoutFixIsNotLocated() {
        val text = AgentContextAssembler.format(
            WorldContext(gpsPermission = true, hasGpsFix = false),
        )
        assertTrue(text.contains("定位权限=已授权"))
        assertTrue(text.contains("GPS坐标=未获取"))
        assertTrue(text.contains("语义地点=未知"))
        assertFalse(text.contains("有定位"))
    }

    @Test
    fun disambiguationAndSearchStaySeparate() {
        val text = AgentContextAssembler.format(
            WorldContext(
                disambiguation = listOf(place("海底捞(西湖店)", "a")),
                recentSearch = listOf(place("药店", "b").asProfile(), place("银行", "c").asProfile()),
            ),
        )
        assertTrue(text.contains("【待选择地点】海底捞(西湖店)"))
        assertTrue(text.contains("【最近搜索】药店、银行"))
        assertTrue(text.contains("不等于待选择"))
        assertFalse(text.contains("候选=海底捞"))
    }

    @Test
    fun userFloorBeatsVisualFloorInLocationLine() {
        val visual = EnvironmentMerge.fromLook(
            EnvironmentLook(
                sceneBrief = "4层标识",
                placeHint = "signage",
                floorCandidate = "4",
                floorEvidence = "4层",
                confidence = 0.8f,
            ),
            EnvironmentState(),
            10,
        )
        val spoken = EnvironmentMerge.fromSpeech("我在7楼", visual, 20)
        val text = AgentContextAssembler.format(WorldContext(environment = spoken, spokenFloor = "7"))
        assertTrue(text.contains("楼层=7（来源：用户确认）"))
        assertTrue(text.contains("楼层标识=4") || text.contains("楼层标识=7") || text.contains("【近期观察】"))
        val location = text.lineSequence().first { it.startsWith("【用户所在】") }
        assertTrue(location.contains("楼层=7"))
        assertFalse(location.contains("楼层=4"))
    }

    @Test
    fun storeCandidateObservationIsNotFact() {
        val text = AgentContextAssembler.format(
            WorldContext(
                observation = SceneObservation(
                    scene = SceneObservation.SCENE_STOREFRONT,
                    visibleText = "海底捞",
                    storeCandidate = "海底捞",
                    stability = 2,
                    isStoreFact = false,
                ),
            ),
        )
        assertTrue(text.contains("疑似门头=海底捞（不是已确认门店）"))
        assertFalse(text.contains("已晋级事实"))
        assertFalse(text.contains("【业务对象】海底捞"))
    }

    @Test
    fun changingViewDoesNotRewriteDestination() {
        val desk = EnvironmentMerge.fromLook(
            EnvironmentLook(sceneBrief = "电脑显示器", placeHint = "desk", confidence = 0.8f),
            EnvironmentState(),
            1,
        )
        val corridor = EnvironmentMerge.fromLook(
            EnvironmentLook(sceneBrief = "办公区走廊", placeHint = "corridor", confidence = 0.8f),
            desk,
            2,
        )
        val text = AgentContextAssembler.format(
            WorldContext(
                boundPlace = place("海底捞火锅"),
                environment = corridor,
                navActive = true,
            ),
        )
        assertTrue(text.contains("目的地=海底捞火锅"))
        assertTrue(text.contains("【当前视野】办公区走廊"))
        assertTrue(text.contains("语义地点=未知"))
    }

    @Test
    fun bindingDestinationDoesNotRewriteUserLocation() {
        val env = EnvironmentMerge.fromSpeech("我在7楼", EnvironmentState(), 1)
        val text = AgentContextAssembler.format(
            WorldContext(
                boundPlace = place("海底捞火锅"),
                environment = env,
                navActive = true,
            ),
        )
        assertTrue(text.contains("目的地=海底捞火锅"))
        assertTrue(text.contains("楼层=7（来源：用户确认）"))
        assertTrue(text.contains("语义地点=未知"))
        assertFalse(text.contains("当前地点=海底捞"))
    }

    @Test
    fun debugKeepsInternalFieldsOutOfModelContext() {
        val world = WorldContext(
            boundPlace = place("海底捞火锅").copy(source = PlaceRef.SOURCE_CATALOG),
            navActive = true,
            catalogCount = 12,
            observation = SceneObservation(
                scene = SceneObservation.SCENE_STOREFRONT,
                storeCandidate = "疑似",
                stability = 1,
                confidence = 0.4f,
            ),
        )
        val model = AgentContextAssembler.format(world)
        val debug = AgentContextAssembler.debug(world)
        assertFalse(model.contains("catalog=12"))
        assertFalse(model.contains("skill=nav"))
        assertTrue(debug.contains("nav=true"))
        assertTrue(debug.contains("catalog=12"))
        assertTrue(debug.contains("catalog"))
        assertTrue(model.contains("【已录入门店】12 家"))
    }

    @Test
    fun overlayLineTellsModelHowToLeave() {
        val draw = AgentContextAssembler.format(WorldContext(hudOverlay = "draw"))
        val confirm = AgentContextAssembler.format(
            WorldContext(hudOverlay = "confirm", pendingPay = "待付88元给巴奴"),
        )
        val nav = AgentContextAssembler.format(WorldContext(navActive = true))
        val drawOnNav = AgentContextAssembler.format(WorldContext(hudOverlay = "draw", navActive = true))
        val idle = AgentContextAssembler.format(WorldContext())
        assertTrue(draw.contains("【镜片覆盖】自绘画面"))
        assertTrue(draw.contains("close_hud"))
        assertTrue(confirm.contains("待确认支付或核销"))
        assertTrue(confirm.contains("close_hud"))
        assertTrue(nav.contains("stop_navigation"))
        assertTrue(drawOnNav.contains("close_hud"))
        assertTrue(drawOnNav.contains("回到导航"))
        assertFalse(drawOnNav.contains("停导航用 stop_navigation"))
        assertFalse(idle.contains("【镜片覆盖】"))
    }

    @Test
    fun approachingRoleWhenNavArrived() {
        val text = AgentContextAssembler.format(
            WorldContext(
                boundPlace = place("海底捞火锅"),
                navActive = true,
                navArrived = true,
            ),
        )
        assertTrue(text.contains("已接近，仍不是已确认到店事实"))
        assertFalse(text.contains("当前地点"))
    }

    private fun place(name: String, id: String = name): PlaceRef {
        return PlaceRef(id = id, name = name, source = PlaceRef.SOURCE_CATALOG)
    }
}
