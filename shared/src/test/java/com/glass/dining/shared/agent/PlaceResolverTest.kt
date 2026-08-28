package com.glass.dining.shared.agent

import com.glass.dining.shared.catalog.StoreFixtures
import com.glass.dining.shared.model.Store
import com.glass.dining.shared.place.DestinationResolve
import com.glass.dining.shared.place.PlaceRef
import com.glass.dining.shared.place.PlaceResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceResolverTest {
    @Test
    fun emptyCatalogUniquePoiConfirms() {
        val poi = PlaceRef("p1", "海底捞(西湖店)", address = "文三路1号", lat = 30.27, lng = 120.15, distanceMeters = 180)
            .asProfile()
        val resolved = PlaceResolver.resolve(
            query = "海底捞",
            sessionPlace = null,
            catalog = emptyList(),
            nearby = listOf(poi),
            searchAttempted = true,
        )
        val unique = resolved as DestinationResolve.Unique
        assertEquals("p1", unique.place.id)
        assertFalse(unique.place.catalogBacked)
    }

    @Test
    fun multiplePoiAsksUser() {
        val nearby = listOf(
            PlaceRef("a", "海底捞(A店)", distanceMeters = 120).asProfile(),
            PlaceRef("b", "海底捞(B店)", distanceMeters = 400).asProfile(),
        )
        val resolved = PlaceResolver.resolve("海底捞", null, emptyList(), nearby, searchAttempted = true)
        val amb = resolved as DestinationResolve.Ambiguous
        assertEquals(2, amb.candidates.size)
    }

    @Test
    fun searchFailureIsNotEmptyCatalog() {
        val resolved = PlaceResolver.resolve(
            query = "海底捞",
            sessionPlace = null,
            catalog = emptyList(),
            nearby = emptyList(),
            searchAttempted = true,
            searchError = "amap_error",
        )
        val miss = resolved as DestinationResolve.NotFound
        assertEquals("amap_error", miss.reason)
        assertFalse(miss.reason.contains("目录"))
    }

    @Test
    fun needSearchWhenPoiNotTried() {
        val resolved = PlaceResolver.resolve("海底捞", null, emptyList(), emptyList(), searchAttempted = false)
        assertEquals("need_search", (resolved as DestinationResolve.NotFound).reason)
    }

    @Test
    fun noGpsStillAsksSearchFirst() {
        val resolved = PlaceResolver.resolve(
            query = "海底捞",
            sessionPlace = null,
            catalog = emptyList(),
            nearby = emptyList(),
            hasLocation = false,
            searchAttempted = false,
        )
        assertEquals("need_search", (resolved as DestinationResolve.NotFound).reason)
    }

    @Test
    fun noGpsAfterSearchNeedsLocation() {
        val resolved = PlaceResolver.resolve(
            query = "海底捞",
            sessionPlace = null,
            catalog = emptyList(),
            nearby = emptyList(),
            hasLocation = false,
            searchAttempted = true,
        )
        assertTrue(resolved is DestinationResolve.NeedLocation)
    }

    @Test
    fun catalogWinsCoordsAndFloor() {
        val local = StoreFixtures.twoNearby().first().copy(
            name = "海底捞",
            shortName = "海底捞",
            floor = "5F",
            lat = 31.23,
            lng = 121.47,
        )
        val poi = PlaceRef("amap1", "海底捞", lat = 1.0, lng = 2.0, distanceMeters = 90).asProfile()
        val merged = PlaceResolver.merge(listOf(local), listOf(poi))
        val catalog = merged.first { it.catalogBacked }
        assertEquals(31.23, catalog.lat, 0.0001)
        assertEquals("5F", catalog.floor)
        assertEquals(PlaceRef.SOURCE_CATALOG, catalog.source)
    }

    @Test
    fun catalogMissingCoordsTakesPoi() {
        val local = Store(
            id = "s1",
            name = "海底捞",
            shortName = "海底捞",
            category = "火锅",
            rating = 0.0,
            reviewCount = 0,
            avgPrice = 0,
            distanceMeters = 0,
            openNow = true,
            hours = "",
            phone = "",
            address = "商场5F",
            waitTables = 3,
            waitMinutes = 12,
            tags = emptyList(),
            deals = emptyList(),
            signatures = emptyList(),
            suitable = emptyList(),
            hasPrivateRoom = false,
            answers = emptyMap(),
            floor = "5F",
        )
        val poi = PlaceRef("amap1", "海底捞", lat = 30.2, lng = 120.1).asProfile()
        val merged = PlaceResolver.merge(listOf(local), listOf(poi)).first()
        assertEquals(30.2, merged.lat, 0.0001)
        assertEquals("5F", merged.floor)
        assertTrue(merged.catalogBacked)
    }

    @Test
    fun poiStoreDoesNotInventQueue() {
        val store = PlaceResolver.toStore(PlaceRef("p", "药店", distanceMeters = 80))
        assertFalse(store.catalogBacked)
        assertEquals(0, store.waitMinutes)
        assertEquals(0, store.avgPrice)
        assertTrue(store.deals.isEmpty())
        assertEquals(0.0, store.rating, 0.0)
        assertEquals("", store.hours)
        assertEquals("", store.phone)
        assertFalse(store.openNow)
    }

    @Test
    fun poiStoreKeepsAmapFacts() {
        val store = PlaceResolver.toStore(
            PlaceRef(
                id = "B0HGUAEBKL",
                name = "潮黄记·潮汕鲜牛肉火锅(望京店)",
                address = "望京东园1区120号楼",
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
            ),
        )
        assertFalse(store.catalogBacked)
        assertEquals(4.7, store.rating, 0.001)
        assertEquals(152, store.avgPrice)
        assertEquals("11:00-23:00", store.hours)
        assertEquals("13581699848", store.phone)
        assertEquals("2F", store.floor)
        assertEquals("望京", store.businessArea)
        assertEquals("牛肉火锅", store.category)
        assertEquals(0, store.waitMinutes)
        assertTrue(store.deals.isEmpty())
    }
}
