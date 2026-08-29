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
        assertNotNull(session.viewingStore)
        assertTrue(session.startNav())
        assertTrue(session.navigating)
        val left = session.dismiss(DismissReason.REPLACE)
        assertEquals(HudSurface.NAV, left)
        assertFalse(session.navigating)
        assertNotNull(session.currentStore)
    }

    @Test
    fun lookShowsWithoutBinding() {
        val session = DiningSession(MemoryStoreCatalog(StoreFixtures.twoNearby()))
        session.look(forceStoreId = "store_hotpot")
        assertNotNull(session.viewingStore)
        assertEquals(null, session.currentStore)
        session.select("store_hotpot")
        assertEquals("store_hotpot", session.currentStore?.id)
    }

    @Test
    fun startNavCommitsViewing() {
        val session = DiningSession(MemoryStoreCatalog(StoreFixtures.twoNearby()))
        session.look(forceStoreId = "store_hotpot")
        assertEquals(null, session.currentStore)
        assertTrue(session.startNav())
        assertEquals("store_hotpot", session.currentStore?.id)
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
        assertNotNull(session.viewingStore)
        assertEquals(null, session.currentStore)
    }

    @Test
    fun cancelOverlayClearsConfirmAndMenuKeepsPlace() {
        val session = DiningSession(MemoryStoreCatalog(StoreFixtures.twoNearby()))
        session.look(forceStoreId = "store_hotpot")
        session.startMenu()
        session.listCoupons()
        session.prepareCoupon(session.lastCoupons.first().id)
        assertTrue(session.hasPendingConfirm)
        assertTrue(session.lastMenu.isNotEmpty())
        session.cancelOverlay()
        assertFalse(session.hasPendingConfirm)
        assertTrue(session.lastMenu.isEmpty())
        assertNotNull(session.viewingStore)
        assertEquals(null, session.currentStore)
    }

    @Test
    fun cancelOverlayDoesNotStopNav() {
        val session = DiningSession(MemoryStoreCatalog(StoreFixtures.twoNearby()))
        session.look(forceStoreId = "store_hotpot")
        assertTrue(session.startNav())
        session.cancelOverlay()
        assertTrue(session.navigating)
        assertNotNull(session.currentStore)
    }

    @Test
    fun cancelOverlayClearsConfirmDuringNav() {
        val session = DiningSession(MemoryStoreCatalog(StoreFixtures.twoNearby()))
        session.look(forceStoreId = "store_hotpot")
        assertTrue(session.startNav())
        session.listCoupons()
        session.prepareCoupon(session.lastCoupons.first().id)
        assertTrue(session.hasPendingConfirm)
        session.cancelOverlay()
        assertTrue(session.navigating)
        assertFalse(session.hasPendingConfirm)
        assertNotNull(session.currentStore)
    }
}
