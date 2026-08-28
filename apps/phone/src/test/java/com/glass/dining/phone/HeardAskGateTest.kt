package com.glass.dining.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class HeardAskGateTest {
    @Test
    fun finalTextIsShownBeforeCommit() {
        val shown = ArrayList<String>()
        val committed = ArrayList<String>()
        var scheduled: Runnable? = null
        val gate = HeardAskGate(
            postDelayed = { r, _ -> scheduled = r },
            cancelDelayed = { if (scheduled === it) scheduled = null },
            showHeard = { shown += it },
            commit = { committed += it },
            holdMs = 500L,
        )
        gate.onPartial("附近有什么门")
        gate.onFinal("附近有什么门店")
        assertEquals(listOf("附近有什么门", "附近有什么门店"), shown)
        assertEquals(emptyList<String>(), committed)
        scheduled!!.run()
        assertEquals(listOf("附近有什么门店"), committed)
    }

    @Test
    fun cancelDropsPendingCommit() {
        val committed = ArrayList<String>()
        var scheduled: Runnable? = null
        val gate = HeardAskGate(
            postDelayed = { r, _ -> scheduled = r },
            cancelDelayed = { if (scheduled === it) scheduled = null },
            showHeard = {},
            commit = { committed += it },
        )
        gate.onFinal("附近有什么门店")
        val pendingRun = scheduled
        gate.cancel()
        pendingRun?.run()
        assertEquals(emptyList<String>(), committed)
    }
}
