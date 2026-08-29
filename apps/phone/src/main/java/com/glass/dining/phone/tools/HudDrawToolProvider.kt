package com.glass.dining.phone.tools

import com.glass.dining.phone.agent.PhoneWorld
import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.link.HudLayout
import org.json.JSONObject

class HudDrawToolProvider(private val world: PhoneWorld) {
    fun register(registry: ToolRegistry) {
        registry.register(AgentToolCatalog.DRAW_HUD, ::draw)
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
}
