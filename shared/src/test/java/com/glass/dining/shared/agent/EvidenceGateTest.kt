package com.glass.dining.shared.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGateTest {
    @Test
    fun userSelectBindsWithoutObservation() {
        val decision = EvidenceGate.decideBind(
            EvidenceGate.BindProposal(
                source = EvidenceGate.BindSource.USER_SELECT,
                placeId = "s1",
                name = "青田小馆",
            ),
        )
        assertTrue(decision.accept)
    }

    @Test
    fun searchResultBinds() {
        val decision = EvidenceGate.decideBind(
            EvidenceGate.BindProposal(
                source = EvidenceGate.BindSource.SEARCH_RESULT,
                placeId = "amap-1",
                name = "某药店",
            ),
        )
        assertTrue(decision.accept)
    }

    @Test
    fun observationWithoutPromotionIsNotFact() {
        val decision = EvidenceGate.decideBind(
            EvidenceGate.BindProposal(
                source = EvidenceGate.BindSource.OBSERVATION,
                name = "巴奴",
                observation = SceneObservation(
                    scene = SceneObservation.SCENE_BANNER,
                    storeCandidate = "巴奴",
                    stability = 3,
                ),
                vlmName = "巴奴",
                vlmConfidence = 0.9f,
            ),
        )
        assertFalse(decision.accept)
        assertEquals("observation_is_not_fact", decision.reason)
    }

    @Test
    fun stableUniqueCatalogObservationCanBind() {
        val decision = EvidenceGate.decideBind(
            EvidenceGate.BindProposal(
                source = EvidenceGate.BindSource.OBSERVATION,
                placeId = "s1",
                name = "青田小馆",
                observation = SceneObservation(
                    scene = SceneObservation.SCENE_STOREFRONT,
                    storeCandidate = "青田小馆",
                    stability = 2,
                ),
                uniqueCatalogName = "青田小馆",
            ),
        )
        assertTrue(decision.accept)
    }

    @Test
    fun blankProposalRejected() {
        val decision = EvidenceGate.decideBind(
            EvidenceGate.BindProposal(source = EvidenceGate.BindSource.USER_SELECT),
        )
        assertFalse(decision.accept)
    }
}
