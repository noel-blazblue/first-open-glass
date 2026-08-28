package com.glass.dining.shared.agent

import com.glass.dining.shared.place.AmapPlaceJson
import com.glass.dining.shared.place.PlaceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapPlaceJsonTest {
    @Test
    fun parsesAroundPayload() {
        val raw = """
            {"status":"1","pois":[
              {"id":"p1","name":"海底捞(西湖店)","address":"文三路1号","location":"120.15,30.27","distance":"180","type":"餐饮"},
              {"id":"p2","name":"海底捞(城西店)","address":"丰潭路","location":"120.10,30.28","distance":"920","type":"餐饮"}
            ]}
        """.trimIndent()
        val result = AmapPlaceJson.parseAround(raw)
        assertTrue(result.ok)
        assertEquals(2, result.places.size)
        assertEquals("海底捞(西湖店)", result.places[0].name)
        assertEquals(30.27, result.places[0].lat, 0.0001)
        assertEquals(180, result.places[0].distanceMeters)
        assertEquals(PlaceRef.SOURCE_AMAP, result.places[0].source)
        assertFalse(result.places[0].catalogBacked)
        assertEquals(0.0, result.places[0].rating, 0.0)
    }

    @Test
    fun parsesBusinessAndIndoorFields() {
        val raw = """
            {"status":"1","pois":[
              {"id":"B0HGUAEBKL","name":"潮黄记·潮汕鲜牛肉火锅(望京店)",
               "address":"望京东园1区120号楼新荟城购物中心2F层2F-3",
               "location":"116.481716,39.998472","distance":"1500",
               "type":"餐饮服务;中餐厅;中餐厅",
               "business":{"business_area":"望京","cost":"152.00","keytag":"牛肉火锅",
                 "opentime_today":"11:00-23:00","opentime_week":"周一至周日 11:00-23:00",
                 "rating":"4.7","tag":"牛肉","tel":"13581699848"},
               "indoor":{"indoor_map":"1","floor":"2","truefloor":"2F"}}
            ]}
        """.trimIndent()
        val place = AmapPlaceJson.parseAround(raw).places.single()
        assertEquals(4.7, place.rating, 0.001)
        assertEquals(152, place.avgCost)
        assertEquals("11:00-23:00", place.hoursToday)
        assertEquals("望京", place.businessArea)
        assertEquals("2F", place.floor)
        assertEquals("13581699848", place.tel)
        assertEquals("牛肉火锅", place.keytag)
    }

    @Test
    fun failedStatusIsStructured() {
        val result = AmapPlaceJson.parseAround("""{"status":"0","info":"INVALID_USER_KEY"}""")
        assertFalse(result.ok)
        assertEquals("amap_error", result.error)
        assertTrue(result.message.contains("INVALID_USER_KEY"))
        assertTrue(result.places.isEmpty())
    }
}

class DegradedPlannerTest {
    @Test
    fun navHaidilaoCallsResolve() {
        val turn = DegradedPlanner.turn(
            listOf(LlmMessage("user", "导航去海底捞")),
            WorldContext(catalogCount = 0),
        )
        assertEquals("resolve_destination", turn.toolCalls.single().name)
        assertTrue(turn.toolCalls.single().argumentsJson.contains("海底捞"))
    }

    @Test
    fun needSearchThenCallsAmap() {
        val turn = DegradedPlanner.turn(
            listOf(
                LlmMessage("user", "导航去海底捞"),
                LlmMessage("tool", """{"ok":false,"error":"need_search"}""", toolCallId = "1"),
            ),
            WorldContext(),
        )
        assertEquals("search_nearby_places", turn.toolCalls.single().name)
    }

    @Test
    fun weatherDoesNotCallDiningTools() {
        val turn = DegradedPlanner.turn(
            listOf(LlmMessage("user", "今天天气适合走过去吗")),
            WorldContext(catalogCount = 0),
        )
        assertTrue(turn.toolCalls.isEmpty())
        assertTrue(turn.content.contains("密钥") || turn.content.contains("天气") || turn.content.contains("问答"))
    }

    @Test
    fun payConfirmCallsCheckout() {
        val turn = DegradedPlanner.turn(
            listOf(LlmMessage("user", "确认付款")),
            WorldContext(pendingPay = "待付88元给巴奴"),
        )
        assertEquals("checkout", turn.toolCalls.single().name)
        assertTrue(turn.toolCalls.single().argumentsJson.contains("confirm"))
    }

    @Test
    fun developerToolMessageIsNotSpoken() {
        val turn = DegradedPlanner.turn(
            listOf(
                LlmMessage("user", "附近有药店吗"),
                LlmMessage(
                    "tool",
                    """{"ok":false,"error":"empty_catalog","message":"请调用 search_nearby_places"}""",
                    toolCallId = "1",
                ),
            ),
            WorldContext(modelReady = true),
        )
        assertTrue(turn.toolCalls.isEmpty())
        assertFalse(turn.content.contains("请调用"))
        assertFalse(turn.content.contains("search_nearby_places"))
        assertTrue(turn.content.contains("搜") || turn.content.contains("查"))
    }
}
