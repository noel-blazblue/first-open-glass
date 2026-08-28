package com.glass.dining.shared.agent

import com.glass.dining.shared.hud.StorePanel
import com.glass.dining.shared.hud.StorePanelLine
import com.glass.dining.shared.place.PlaceRef
import com.glass.dining.shared.place.PlaceWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceWireTest {
    private val chaohuangji = PlaceRef(
        id = "B0HGUAEBKL",
        name = "潮黄记·潮汕鲜牛肉火锅(望京店)",
        address = "望京东园1区120号楼新荟城购物中心2F层2F-3",
        distanceMeters = 1500,
        floor = "2F",
    ).asProfile(
        rating = 4.7,
        avgCost = 152,
        tel = "13581699848",
        hoursToday = "11:00-23:00",
        businessArea = "望京",
        keytag = "牛肉火锅",
        tag = "牛肉",
    )

    @Test
    fun roundTripKeepsAmapFacts() {
        val json = PlaceWire.encode(chaohuangji, "说去哪家")
        val payload = PlaceWire.decode(json)
        assertNotNull(payload)
        val place = payload!!.place
        assertEquals("B0HGUAEBKL", place.id)
        assertEquals("潮黄记·潮汕鲜牛肉火锅(望京店)", place.name)
        assertEquals(4.7, place.rating, 0.001)
        assertEquals(152, place.avgCost)
        assertEquals("11:00-23:00", place.hoursToday)
        assertEquals("望京", place.businessArea)
        assertEquals("2F", place.floor)
        assertEquals("13581699848", place.tel)
        assertEquals("说去哪家", payload.caption)
        assertFalse(json.contains("wait_minutes"))
        assertEquals(0, place.waitMinutes)
    }

    @Test
    fun blankNameIsRejected() {
        assertNull(PlaceWire.decode("""{"place_id":"x"}"""))
        assertNull(PlaceWire.decode(""))
        assertNull(PlaceWire.decode(null))
    }

    @Test
    fun missingScoreDoesNotWriteZeros() {
        val place = PlaceRef("p", "无名小馆", distanceMeters = 80, category = "中餐").asProfile()
        val json = PlaceWire.encode(place)
        assertFalse(json.contains("\"rating\""))
        assertFalse(json.contains("avg_cost"))
        val decoded = PlaceWire.decode(json)!!.place
        assertEquals(0.0, decoded.rating, 0.0)
        assertEquals(0, decoded.avgCost)
    }

    @Test
    fun publicPanelOmitsQueueAndZeros() {
        val lines = StorePanel.lines(chaohuangji)
        val texts = lines.map { it.text }
        assertTrue(texts.contains("潮黄记·潮汕鲜牛肉火锅(望京店)"))
        assertTrue(texts.contains("4.7分 · 人均152"))
        assertTrue(texts.contains("今日 11:00-23:00"))
        assertTrue(texts.contains("望京 · 2F · 约1500米"))
        assertTrue(texts.contains("电话 13581699848"))
        assertTrue(texts.contains(StorePanel.DEFAULT_HINT))
        assertFalse(texts.any { it.contains("排队") })
        assertFalse(texts.any { it.contains("券") })
        assertEquals(StorePanelLine.Kind.SOURCE, lines.first().kind)
    }

    @Test
    fun sparsePlaceOmitsEmptyRows() {
        val place = PlaceRef("p", "无名小馆", distanceMeters = 80, category = "中餐").asProfile()
        val texts = StorePanel.lines(place).map { it.text }
        assertTrue(texts.contains("无名小馆"))
        assertTrue(texts.contains("约80米"))
        assertFalse(texts.any { it.contains("0分") })
        assertFalse(texts.any { it.contains("人均0") })
        assertFalse(texts.any { it.contains("今日") })
        assertFalse(texts.any { it.contains("电话") })
        assertFalse(texts.any { it.contains("排队") })
    }

    @Test
    fun catalogWaitShowsOnlyWhenPresent() {
        val local = PlaceRef(
            id = "s1",
            name = "已录入火锅",
            source = PlaceRef.SOURCE_CATALOG,
        ).asProfile(waitMinutes = 12, dealLine = "午市套餐 ¥88")
        val texts = StorePanel.lines(local).map { it.text }
        assertTrue(texts.contains("排队约12分钟"))
        assertTrue(texts.contains("午市套餐 ¥88"))
        val publicWait = PlaceRef("p", "公开店").asProfile(waitMinutes = 12, dealLine = "券")
        val publicTexts = StorePanel.lines(publicWait).map { it.text }
        assertFalse(publicTexts.any { it.contains("排队") })
        assertFalse(publicTexts.any { it.contains("券") })
    }
}
