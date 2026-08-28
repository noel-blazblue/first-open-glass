package com.glass.dining.shared.vision

/**
 * 到餐 Agent 的通用视觉技能入口。
 * 认店、导航路牌、菜单、券、支付都映射到同一套抽帧 + 路由，而不是各自开相机。
 */
object VisionSkill {
    val LOOK_AT_SCENE = "look_at_scene"
    const val LOOK_STORE = "look_store"
    const val READ_SIGN = "read_sign"
    const val READ_MENU = "read_menu"
    const val SCAN_COUPON = "scan_coupon"
    const val CHECKOUT = "checkout"

    val TOOLS = listOf(LOOK_AT_SCENE, SCAN_COUPON, CHECKOUT)

    fun intentForTool(name: String, purpose: String = ""): VisionIntent? {
        val p = purpose.lowercase()
        return when (name) {
            LOOK_STORE -> VisionIntent.LOOK_STORE
            LOOK_AT_SCENE -> when (p) {
                "menu" -> VisionIntent.READ_MENU
                "store" -> VisionIntent.LOOK_STORE
                else -> VisionIntent.READ_SIGN
            }
            READ_SIGN -> VisionIntent.READ_SIGN
            READ_MENU -> VisionIntent.READ_MENU
            SCAN_COUPON -> VisionIntent.SCAN_COUPON
            CHECKOUT -> VisionIntent.CHECKOUT
            else -> null
        }
    }

    fun isVisionTool(name: String): Boolean {
        return name == LOOK_AT_SCENE || name == LOOK_STORE || name == READ_SIGN ||
            name == READ_MENU || name == SCAN_COUPON || name == CHECKOUT
    }
}
