package com.glass.dining.phone.tools

import com.glass.dining.phone.agent.PhoneWorld
import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.hud.HudOverlay
import com.glass.dining.shared.link.HudLayout
import org.json.JSONObject

class HudDrawToolProvider(private val world: PhoneWorld) {
    fun register(registry: ToolRegistry) {
        registry.register(AgentToolCatalog.DRAW_HUD, ::draw)
        registry.register(AgentToolCatalog.CLOSE_HUD, ::close)
    }

    private fun draw(args: JSONObject): String {
        val scene = HudLayout.compile(args)
        if (scene == null) {
            return JSONObject()
                .put("ok", false)
                .put("error", "交 layout 一列 text/rule/row；示意图用 path/circle/rect。")
                .toString()
        }
        world.showDraw(scene)
        return JSONObject()
            .put("ok", true)
            .put("seq", scene.seq)
            .put("ops", scene.ops.size)
            .put("shapes", scene.ops.count { it.type != "text" })
            .toString()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun close(args: JSONObject): String {
        val kind = world.hudOverlay()
        if (!HudOverlay.canClose(kind)) {
            if (world.session.navigating) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "navigating")
                    .put("hint", "stop_navigation")
                    .put("message", "导航中，停导航用 stop_navigation")
                    .toString()
            }
            return JSONObject()
                .put("ok", true)
                .put("closed", "none")
                .put("message", "镜片上没有覆盖画面")
                .toString()
        }
        val resumeNav = world.session.navigating
        world.closeHud()
        return JSONObject()
            .put("ok", true)
            .put("closed", kind)
            .put("message", if (resumeNav) "已关掉，回到导航" else "已关掉镜片覆盖")
            .toString()
    }
}
