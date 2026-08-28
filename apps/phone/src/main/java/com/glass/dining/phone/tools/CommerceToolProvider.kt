package com.glass.dining.phone.tools

import com.glass.dining.phone.agent.PhoneWorld
import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.hud.HudCard
import com.glass.dining.shared.vision.VisionIntent
import org.json.JSONObject

class CommerceToolProvider(private val world: PhoneWorld) {
    fun register(registry: ToolRegistry) {
        registry.register(AgentToolCatalog.LIST_COUPONS) { listCoupons() }
        registry.register(AgentToolCatalog.REDEEM, ::redeem)
        registry.register(AgentToolCatalog.CHECKOUT, ::checkout)
    }

    private fun listCoupons(): String {
        if (world.session.viewingStore == null) {
            return JSONObject().put("ok", false).put("error", "还没确认要服务的门店，先认店或推荐").toString()
        }
        if (!world.session.viewingStore!!.catalogBacked) {
            return JSONObject().put("ok", false).put("error", "公开地点没有券数据").toString()
        }
        val coupons = world.session.listCoupons()
        val card = HudCard.fromCoupons(world.session.viewingStore?.shortName.orEmpty(), coupons)
        world.publishSkillCard(card, card.wait.ifBlank { "这店暂无券" }, "list_coupons")
        return JSONObject()
            .put("ok", true)
            .put("store", world.session.viewingStore?.shortName)
            .put("mock", true)
            .put("coupons", coupons.joinToString("；") { "${it.id}:${it.title}¥${it.price}" })
            .put("need_confirm", true)
            .put("note", "演示券列表，确认后才核销")
            .toString()
    }

    private fun redeem(args: JSONObject): String {
        if (world.session.viewingStore == null) {
            return JSONObject().put("ok", false).put("error", "还没确认要服务的门店").toString()
        }
        if (world.session.lastCoupons.isEmpty()) {
            world.session.listCoupons()
        }
        val couponId = args.optString("coupon_id")
        val title = args.optString("title")
        val picked = when {
            couponId.isNotBlank() -> world.session.prepareCoupon(couponId)
            title.isNotBlank() -> world.session.lastCoupons.firstOrNull { it.title.contains(title) }?.also {
                world.session.prepareCoupon(it.id)
            }
            else -> world.session.pendingCoupon ?: world.session.lastCoupons.firstOrNull()?.also {
                world.session.prepareCoupon(it.id)
            }
        }
        if (picked == null) {
            return JSONObject().put("ok", false).put("error", "这店没有可核销的券").toString()
        }
        if (!args.optBoolean("confirm", false)) {
            val card = HudCard.fromCoupons(world.session.viewingStore?.shortName.orEmpty(), listOf(picked))
            world.publishSkillCard(card, "确认后才核销${picked.title}", "redeem_coupon")
            return JSONObject()
                .put("ok", true)
                .put("need_confirm", true)
                .put("mock", true)
                .put("coupon_id", picked.id)
                .put("title", picked.title)
                .put("price", picked.price)
                .put("message", "请用户确认是否核销${picked.title}")
                .toString()
        }
        val receipt = world.session.redeemCoupon()
        val card = HudCard.fromRedeem(receipt.title, receipt.ok)
        world.publishSkillCard(card, receipt.message, "redeem_coupon")
        return JSONObject()
            .put("ok", receipt.ok)
            .put("mock", true)
            .put("coupon_id", receipt.couponId)
            .put("title", receipt.title)
            .put("sequence_id", receipt.sequenceId)
            .put("message", receipt.message + "。这是演示回执，没有调美团验券接口")
            .toString()
    }

    private fun checkout(args: JSONObject): String {
        if (args.optBoolean("confirm", false)) {
            val done = world.session.confirmPay()
                ?: return JSONObject().put("ok", false).put("error", "还没有待确认的付款").put("need_confirm", true).toString()
            val card = HudCard.fromPayResult(done.storeName, done.amount)
            world.publishSkillCard(card, "已付${done.amount}元，这是演示", "checkout")
            return JSONObject()
                .put("ok", true)
                .put("mock", true)
                .put("paid", true)
                .put("amount", done.amount)
                .put("store", done.storeName)
                .put("message", "演示已付${done.amount}元，不是真扣款")
                .toString()
        }
        return world.capture(VisionIntent.CHECKOUT)
    }
}
