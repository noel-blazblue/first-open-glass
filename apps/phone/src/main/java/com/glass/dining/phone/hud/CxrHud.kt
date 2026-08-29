package com.glass.dining.phone.hud

import android.util.Log
import com.glass.dining.phone.CxrLinkHost
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.place.PlaceProfile
import com.glass.dining.shared.place.PlaceWire
import java.util.concurrent.atomic.AtomicLong
import com.glass.dining.shared.link.GlassLink
import com.glass.dining.shared.link.HudDraw
import com.glass.dining.shared.link.HudDrawScene
import com.glass.dining.shared.link.HudWire

internal object CxrHud {
    @Volatile var lastCard: HudCard = HudCard.idle()
        private set
    @Volatile var lastStore: PlaceProfile? = null
        private set
    @Volatile var lastCaption: String = ""
        private set
    @Volatile var lastDraw: HudDrawScene? = null
        private set
    private val hudSeq = AtomicLong(0)
    private val drawSeq = AtomicLong(0)

    fun replay() {
        val drawn = lastDraw
        val store = lastStore
        when {
            drawn != null -> showDraw(drawn)
            lastCard.isNav -> showCard(lastCard)
            store != null -> showStore(store, lastCaption)
            else -> showCard(lastCard)
        }
    }

    fun dismissStore() {
        lastStore = null
        lastCaption = ""
    }

    fun showDraw(scene: HudDrawScene) {
        val stamped = if (scene.seq > 0L) scene else scene.copy(seq = drawSeq.incrementAndGet())
        lastDraw = stamped
        lastStore = null
        lastCaption = ""
        lastCard = HudCard.idle()
        val seq = hudSeq.incrementAndGet()
        val json = HudDraw.encode(stamped)
        CxrLinkHost.main.post {
            if (seq != hudSeq.get()) return@post
            if (!CxrLinkHost.linkReady()) {
                Log.i(CxrLinkHost.TAG, "showDraw skipped, CXR not ready ops=${stamped.ops.size}")
                return@post
            }
            if (!CxrLinkHost.hudOpened) {
                CxrLinkHost.ensureGlassApp("draw")
                return@post
            }
            CxrLinkHost.sendCmd(GlassLink.CMD_DRAW, json)
            Log.i(CxrLinkHost.TAG, "hud.draw seq=${stamped.seq} ops=${stamped.ops.size}")
        }
    }

    fun showStore(place: PlaceProfile, caption: String = "") {
        lastStore = place
        lastCaption = caption
        lastCard = HudCard.idle()
        lastDraw = null
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
            CxrLinkHost.sendCmd(GlassLink.CMD_STORE, json)
            Log.i(CxrLinkHost.TAG, "hud.store name=${place.name} rating=${place.rating}")
        }
    }

    fun showCard(card: HudCard) {
        val clipped = card.clipped()
        if (clipped.isTalk && lastDraw != null) {
            Log.i(CxrLinkHost.TAG, "hud.card talk kept draw ops=${lastDraw?.ops?.size}")
            return
        }
        lastCard = clipped
        lastDraw = null
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
                GlassLink.CMD_HUD,
                HudWire.cardJson(
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
