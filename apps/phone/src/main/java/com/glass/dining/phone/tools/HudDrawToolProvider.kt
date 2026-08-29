package com.glass.dining.phone.tools

import com.glass.dining.phone.agent.PhoneWorld
import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.link.HudDraw
import org.json.JSONObject

class HudDrawToolProvider(private val world: PhoneWorld) {
    fun register(registry: ToolRegistry) {
        registry.register(AgentToolCatalog.DRAW_HUD, ::draw)
    }

    private fun draw(args: JSONObject): String {
        val payload = JSONObject()
            .put("seq", args.optLong("seq"))
            .put("ops", args.optJSONArray("ops"))
        val scene = HudDraw.parse(payload.toString())
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "没有可画的线和字。ops 只需 path 的 d 或 text 的 v。")
                .toString()
        world.showDraw(scene)
        return JSONObject()
            .put("ok", true)
            .put("seq", scene.seq)
            .put("ops", scene.ops.size)
            .toString()
    }
}
