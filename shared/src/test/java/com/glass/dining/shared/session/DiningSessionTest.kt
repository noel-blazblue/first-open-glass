package com.glass.dining.shared.session

import com.glass.dining.shared.catalog.MemoryStoreCatalog
import com.glass.dining.shared.catalog.StoreFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiningSessionTest {
    @Test
    fun dismissNavKeepsBoundPlace() {
        val session = DiningSession(MemoryStoreCatalog(StoreFixtures.twoNearby()))
        session.look(forceStoreId = "store_hotpot")
        assertTrue(session.startNav())
        assertTrue(session.navigating)
        val left = session.dismiss(DismissReason.REPLACE)
        assertEquals(HudSurface.NAV, left)
        assertFalse(session.navigating)
        assertNotNull(session.currentStore)
    }

    @Test
    fun dismissConfirmClearsPending() {
        val session = DiningSession(MemoryStoreCatalog(StoreFixtures.twoNearby()))
        session.look(forceStoreId = "store_hotpot")
        session.listCoupons()
        session.prepareCoupon(session.lastCoupons.first().id)
        assertTrue(session.hasPendingConfirm)
        session.dismiss(DismissReason.CANCEL)
        assertFalse(session.hasPendingConfirm)
        assertNotNull(session.currentStore)
    }
}
