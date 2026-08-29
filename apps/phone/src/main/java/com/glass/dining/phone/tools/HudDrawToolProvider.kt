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
                .put("error", "这一帧没有合法的线。必须至少一条 path（如 M40 180 L440 180），不能只交文字。")
                .toString()
        world.showDraw(scene)
        val paths = scene.ops.count { it.type == "path" }
        return JSONObject()
            .put("ok", true)
            .put("seq", scene.seq)
            .put("ops", scene.ops.size)
            .put("paths", paths)
            .toString()
    }
}
