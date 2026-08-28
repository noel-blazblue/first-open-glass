package com.glass.dining.phone

import android.util.Log
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.nav.NavProtocol
import com.glass.dining.shared.place.PlaceProfile
import com.glass.dining.shared.place.PlaceWire
import java.util.concurrent.atomic.AtomicLong

internal object CxrHud {
    @Volatile var lastCard: HudCard = HudCard.idle()
        private set
    @Volatile var lastStore: PlaceProfile? = null
        private set
    @Volatile var lastCaption: String = ""
        private set
    private val hudSeq = AtomicLong(0)

    fun replay() {
        val store = lastStore
        when {
            lastCard.isNav -> showCard(lastCard)
            store != null -> showStore(store, lastCaption)
            else -> showCard(lastCard)
        }
    }

    fun dismissStore() {
        lastStore = null
        lastCaption = ""
    }

    fun showStore(place: PlaceProfile, caption: String = "") {
        lastStore = place
        lastCaption = caption
        lastCard = HudCard.idle()
        val seq = hudSeq.incrementAndGet()
        val json = PlaceWire.encode(place, caption)
        CxrLinkHost.main.post {
            if (seq != hudSeq.get()) return@post
            if (!CxrLinkHost.linkReady()) {
                Log.i(CxrLinkHost.TAG, "showStore skipped, CXR not ready name=${place.name}")
                return@post
            }
            if (!CxrLinkHost.hudOpened) {
                CxrLinkHost.ensureGlassApp("store")
                return@post
            }
            CxrLinkHost.sendCmd(NavProtocol.CMD_STORE, json)
            Log.i(CxrLinkHost.TAG, "hud.store name=${place.name} rating=${place.rating}")
        }
    }

    fun showCard(card: HudCard) {
        val clipped = card.clipped()
        lastCard = clipped
        if (clipped.isTalk && lastStore != null) {
            Log.i(CxrLinkHost.TAG, "hud.card talk kept store name=${lastStore?.name}")
            return
        }
        if (clipped.skill == "browse" && !clipped.isNav && !clipped.isTalk) {
            if (lastStore != null) {
                Log.i(CxrLinkHost.TAG, "hud.card browse skipped, store panel active")
                return
            }
            Log.i(CxrLinkHost.TAG, "hud.card browse skipped, use hud.store")
            return
        }
        if (!clipped.isNav && !clipped.isTalk) {
            lastStore = null
            lastCaption = ""
        }
        val seq = hudSeq.incrementAndGet()
        CxrLinkHost.main.post {
            if (seq != hudSeq.get()) return@post
            if (!CxrLinkHost.linkReady()) {
                Log.i(CxrLinkHost.TAG, "showCard skipped, CXR not ready title=${clipped.title}")
                return@post
            }
            if (!CxrLinkHost.hudOpened) {
                CxrLinkHost.ensureGlassApp("hud")
                return@post
            }
            CxrLinkHost.sendCmd(
                NavProtocol.CMD_HUD,
                NavProtocol.cardJson(
                    clipped.title,
                    clipped.meta,
                    clipped.wait,
                    clipped.extra,
                    clipped.skill,
                    clipped.layout,
                    clipped.speech,
                    clipped.turn,
                    clipped.meters,
                    clipped.remaining,
                    clipped.mode,
                    clipped.headingDeg,
                    clipped.elevationDeg,
                    clipped.stage,
                    clipped.sessionId,
                    clipped.tracking,
                    clipped.waypoints,
                    clipped.pose,
                ),
            )
            Log.i(CxrLinkHost.TAG, "hud.card layout=${clipped.layout} skill=${clipped.skill} title=${clipped.title}")
        }
    }
}
