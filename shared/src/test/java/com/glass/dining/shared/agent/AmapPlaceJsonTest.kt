package com.glass.dining.shared.agent

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
}
