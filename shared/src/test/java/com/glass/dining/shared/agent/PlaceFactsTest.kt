package com.glass.dining.shared.agent

import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.place.PlaceFacts
import com.glass.dining.shared.place.PlaceRef
import com.glass.dining.shared.place.PlaceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceFactsTest {
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
    fun detailCardUsesPresentAmapFieldsOnly() {
        val card = PlaceFacts.detailCard(PlaceResolver.toStore(chaohuangji))
        assertEquals("潮黄记·潮汕鲜牛肉火锅(望京店)", card.title)
        assertEquals("4.7分 · 人均152", card.meta)
        assertEquals("今日 11:00-23:00", card.wait)
        assertEquals("望京 · 2F", card.extra)
    }

    @Test
    fun listCardPutsDistanceBeforeScore() {
        val card = PlaceFacts.listCard(PlaceResolver.toStore(chaohuangji), "说去哪家")
        assertEquals("约1500米 · 牛肉火锅", card.meta)
        assertEquals("4.7分 · 人均152", card.wait)
        assertEquals("说去哪家", card.extra)
    }

    @Test
    fun missingFieldsDoNotShowZeros() {
        val store = PlaceResolver.toStore(PlaceRef("p", "无名小馆", distanceMeters = 80, category = "中餐"))
        val card = PlaceFacts.detailCard(store)
        assertEquals("无名小馆", card.title)
        assertFalse(card.meta.contains("0分"))
        assertFalse(card.meta.contains("人均0"))
        assertEquals("", card.wait)
        assertEquals("约80米", card.meta)
    }

    @Test
    fun hudCardForPublicPlaceIsDetailLayout() {
        val hud = HudCard.fromStore(PlaceResolver.toStore(chaohuangji))
        assertEquals("4.7分 · 人均152", hud.meta)
        assertEquals("今日 11:00-23:00", hud.wait)
        assertEquals("望京 · 2F", hud.extra)
    }

    @Test
    fun spokenSummaryIncludesAnswerableFacts() {
        val text = PlaceFacts.spokenSummary(PlaceResolver.toStore(chaohuangji))
        assertTrue(text.contains("评分4.7"))
        assertTrue(text.contains("152"))
        assertTrue(text.contains("11:00-23:00"))
        assertTrue(text.contains("13581699848"))
        assertFalse(text.contains("排队"))
    }
}
