package com.glass.dining.phone.tools

import com.glass.dining.phone.agent.PhoneWorld
import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.link.HudDraw
import com.glass.dining.shared.link.HudLayout
import org.json.JSONObject

class HudDrawToolProvider(private val world: PhoneWorld) {
    fun register(registry: ToolRegistry) {
        registry.register(AgentToolCatalog.DRAW_HUD, ::draw)
    }

    private fun draw(args: JSONObject): String {
        val scene = HudLayout.compile(args) ?: run {
            val payload = JSONObject()
                .put("seq", args.optLong("seq"))
                .put("ops", args.optJSONArray("ops"))
            HudDraw.parse(payload.toString())
        }
        if (scene == null) {
            return JSONObject()
                .put("ok", false)
                .put("error", "交 layout 列/行/字（不要填 x,y），或至少一条 path/circle/rect。")
                .toString()
        }
        world.showDraw(scene)
        return JSONObject()
            .put("ok", true)
            .put("seq", scene.seq)
            .put("ops", scene.ops.size)
            .put("layout", args.has("layout"))
            .put("shapes", scene.ops.count { it.type != "text" })
            .toString()
    }
}
