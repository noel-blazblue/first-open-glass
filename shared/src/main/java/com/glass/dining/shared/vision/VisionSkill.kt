package com.glass.dining.shared.vision

/**
 * 到餐 Agent 的通用视觉技能入口。
 * 认店、导航路牌、菜单、券、支付都映射到同一套抽帧 + 路由，而不是各自开相机。
 */
object VisionSkill {
    const val LOOK_AT_SCENE = "look_at_scene"
    const val LOOK_STORE = "look_store"
    const val READ_SIGN = "read_sign"
    const val READ_MENU = "read_menu"
    const val SCAN_COUPON = "scan_coupon"
    const val CHECKOUT = "checkout"

    val TOOLS = listOf(LOOK_STORE, READ_SIGN, READ_MENU, SCAN_COUPON, CHECKOUT)

    fun intentForTool(name: String): VisionIntent? {
        return when (name) {
            LOOK_AT_SCENE, LOOK_STORE -> VisionIntent.LOOK_STORE
            READ_SIGN -> VisionIntent.READ_SIGN
            READ_MENU -> VisionIntent.READ_MENU
            SCAN_COUPON -> VisionIntent.SCAN_COUPON
            CHECKOUT -> VisionIntent.CHECKOUT
            else -> null
        }
    }

    fun isVisionTool(name: String): Boolean = name == LOOK_AT_SCENE || intentForTool(name) != null
}
