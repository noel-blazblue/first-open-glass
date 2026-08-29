package com.glass.dining.shared.hud

/** 镜片盖层。导航是底层，不算覆盖；关掉盖层后回到导航或待机。 */
object HudOverlay {
    const val NONE = ""
    const val DRAW = "draw"
    const val STORE = "store"
    const val CONFIRM = "confirm"
    const val CARD = "card"
    const val NAV = "nav"
    const val TALK = "talk"

    fun kind(
        pendingConfirm: Boolean,
        drawShowing: Boolean,
        storeShowing: Boolean,
        cardShowing: Boolean = false,
    ): String = when {
        pendingConfirm -> CONFIRM
        drawShowing -> DRAW
        storeShowing -> STORE
        cardShowing -> CARD
        else -> NONE
    }

    /** 镜片实际画哪一层：盖层压过导航。 */
    fun face(
        drawShowing: Boolean,
        storeShowing: Boolean,
        cardIsNav: Boolean,
        cardIsTalk: Boolean,
    ): String = when {
        drawShowing -> DRAW
        storeShowing -> STORE
        cardIsNav -> NAV
        cardIsTalk -> TALK
        else -> CARD
    }

    fun canClose(kind: String): Boolean {
        return kind == DRAW || kind == STORE || kind == CONFIRM || kind == CARD
    }

    fun shouldHoldNavPush(kind: String, alreadyNavigating: Boolean = true): Boolean {
        return alreadyNavigating && canClose(kind)
    }

    fun contextLine(kind: String, navActive: Boolean = false): String? {
        val back = if (navActive) "，关掉后回到导航" else ""
        return when (kind) {
            DRAW -> "【镜片覆盖】自绘画面，用户不要这页时 close_hud$back"
            STORE -> "【镜片覆盖】门店卡，用户不要这页时 close_hud$back"
            CONFIRM -> {
                val extra = if (navActive) "，取消后回到导航" else ""
                "【镜片覆盖】待确认支付或核销，用户取消时 close_hud，不要带 confirm=true$extra"
            }
            CARD -> "【镜片覆盖】资料卡，用户不要这页时 close_hud$back"
            NONE, NAV -> if (navActive) "【镜片覆盖】导航中，停导航用 stop_navigation" else null
            else -> null
        }
    }
}
