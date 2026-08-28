package com.glass.dining.shared.place

import com.glass.dining.shared.catalog.StoreFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceLookupTest {
    private val catalog = StoreFixtures.twoNearby()
    private val nearby = listOf(
        PlaceRef(id = "amap:1", name = "望京药店").asProfile(),
        PlaceRef(id = "amap:2", name = "望京银行").asProfile(),
    )

    @Test
    fun needPointerWhenNoHandle() {
        val result = PlaceLookup.find(catalog = catalog, nearby = nearby)
        assertTrue(result is PlaceLookupResult.NeedPointer)
    }

    @Test
    fun doesNotGuessFirstNearby() {
        val result = PlaceLookup.find(name = "", catalog = catalog, nearby = nearby)
        assertTrue(result is PlaceLookupResult.NeedPointer)
    }

    @Test
    fun findsByIndexWithoutBindingSemantics() {
        val result = PlaceLookup.find(index = 2, catalog = catalog, nearby = nearby)
        assertTrue(result is PlaceLookupResult.Found)
        assertEquals("望京银行", (result as PlaceLookupResult.Found).place.name)
    }

    @Test
    fun findsCatalogByName() {
        val result = PlaceLookup.find(name = catalog.first().shortName, catalog = catalog, nearby = emptyList())
        assertTrue(result is PlaceLookupResult.Found)
        assertEquals(catalog.first().id, (result as PlaceLookupResult.Found).place.id)
    }
}
