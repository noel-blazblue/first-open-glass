package com.glass.dining.phone.hud

import android.util.Log
import com.glass.dining.phone.CxrLinkHost
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.hud.HudOverlay
import com.glass.dining.shared.link.GlassLink
import com.glass.dining.shared.link.HudDraw
import com.glass.dining.shared.link.HudDrawScene
import com.glass.dining.shared.link.HudWire
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
    @Volatile var lastDraw: HudDrawScene? = null
        private set
    @Volatile var lastNav: HudCard? = null
        private set
    private val hudSeq = AtomicLong(0)
    private val drawSeq = AtomicLong(0)

    fun replay() {
        val drawn = lastDraw
        val store = lastStore
        val nav = lastNav
        when {
            drawn != null -> showDraw(drawn)
            store != null -> showStore(store, lastCaption)
            lastCard.isNav -> showCard(lastCard)
            nav != null -> showCard(nav)
            else -> showCard(lastCard)
        }
    }

    fun dismissStore() {
        lastStore = null
        lastCaption = ""
    }

    fun overlayActive(): Boolean = hasOverlay()

    fun overlayKind(pendingConfirm: Boolean): String {
        return HudOverlay.kind(
            pendingConfirm = pendingConfirm,
            drawShowing = lastDraw != null,
            storeShowing = lastStore != null,
            cardShowing = !lastCard.isTalk && !lastCard.isNav,
        )
    }

    fun clearOverlay() {
        lastDraw = null
        lastStore = null
        lastCaption = ""
        if (!lastCard.isTalk && !lastCard.isNav) {
            lastCard = lastNav ?: HudCard.idle()
        }
    }

    fun clearNav() {
        lastNav = null
    }

    fun resumeNav() {
        val nav = lastNav ?: lastCard.takeIf { it.isNav } ?: return
        showCard(nav)
    }

    fun showDraw(scene: HudDrawScene) {
        val stamped = if (scene.seq > 0L) scene else scene.copy(seq = drawSeq.incrementAndGet())
        lastDraw = stamped
        lastStore = null
        lastCaption = ""
        if (!lastCard.isNav) lastCard = HudCard.idle()
        val seq = hudSeq.incrementAndGet()
        val json = HudDraw.encode(stamped)
        CxrLinkHost.main.post {
            if (seq != hudSeq.get()) return@post
            if (!ready("showDraw skipped, CXR not ready ops=${stamped.ops.size}")) return@post
            if (!CxrLinkHost.hudOpened) {
                CxrLinkHost.ensureGlassApp("draw")
                return@post
            }
            CxrLinkHost.sendCmd(GlassLink.CMD_DRAW, json)
            Log.i(
                CxrLinkHost.TAG,
                "hud.draw seq=${stamped.seq} ops=${stamped.ops.size} paths=${stamped.ops.count { it.type == "path" }}",
            )
        }
    }

    fun showStore(place: PlaceProfile, caption: String = "") {
        lastStore = place
        lastCaption = caption
        lastDraw = null
        if (!lastCard.isNav) lastCard = HudCard.idle()
        val seq = hudSeq.incrementAndGet()
        val json = PlaceWire.encode(place, caption)
        CxrLinkHost.main.post {
            if (seq != hudSeq.get()) return@post
            if (!ready("showStore skipped, CXR not ready name=${place.name}")) return@post
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
        if (clipped.isNav) {
            holdOrPushNav(clipped)
            return
        }
        if (clipped.isTalk && lastDraw != null) {
            Log.i(CxrLinkHost.TAG, "hud.card talk kept draw ops=${lastDraw?.ops?.size}")
            return
        }
        if (clipped.isTalk && (lastNav != null || lastCard.isNav)) {
            Log.i(CxrLinkHost.TAG, "hud.card talk kept nav title=${lastNav?.title ?: lastCard.title}")
            return
        }
        lastCard = clipped
        lastDraw = null
        if (clipped.isTalk && lastStore != null) {
            Log.i(CxrLinkHost.TAG, "hud.card talk kept store name=${lastStore?.name}")
            return
        }
        if (clipped.skill == "browse" && !clipped.isTalk) {
            if (lastStore != null) {
                Log.i(CxrLinkHost.TAG, "hud.card browse skipped, store panel active")
                return
            }
            Log.i(CxrLinkHost.TAG, "hud.card browse skipped, use hud.store")
            return
        }
        if (!clipped.isTalk) {
            lastStore = null
            lastCaption = ""
        }
        pushCard(clipped)
    }

    private fun holdOrPushNav(clipped: HudCard) {
        val alreadyNavigating = lastNav != null
        lastNav = clipped
        if (alreadyNavigating && hasOverlay()) {
            Log.i(CxrLinkHost.TAG, "hud.card nav held overlay=${overlayKind(false)}")
            return
        }
        lastCard = clipped
        lastDraw = null
        lastStore = null
        lastCaption = ""
        pushCard(clipped)
    }

    private fun hasOverlay(): Boolean {
        return lastDraw != null || lastStore != null || (!lastCard.isTalk && !lastCard.isNav)
    }

    private fun pushCard(clipped: HudCard) {
        val seq = hudSeq.incrementAndGet()
        CxrLinkHost.main.post {
            if (seq != hudSeq.get()) return@post
            if (!ready("showCard skipped, CXR not ready title=${clipped.title}")) return@post
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

    private fun ready(skip: String): Boolean {
        if (CxrLinkHost.linkReady()) return true
        Log.i(CxrLinkHost.TAG, skip)
        return false
    }
}
