package com.glass.dining.phone.agent

import com.glass.dining.shared.agent.AgentToolCatalog
import com.glass.dining.shared.agent.ToolResult
import com.glass.dining.shared.agent.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

class ToolRegistry {
    private val specs = LinkedHashMap<String, ToolSpec>()
    private val executors = LinkedHashMap<String, (JSONObject) -> String>()

    fun register(spec: ToolSpec, execute: (JSONObject) -> String) {
        specs[spec.name] = spec
        executors[spec.name] = execute
    }

    fun specOf(name: String): ToolSpec? {
        val canonical = AgentToolCatalog.ALIASES[name] ?: name
        return specs[canonical] ?: AgentToolCatalog.byName(canonical)
    }

    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        specs.values.forEach { spec ->
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("parameters", JSONObject(spec.parametersJson)),
                    ),
            )
        }
        return arr
    }

    fun execute(name: String, args: JSONObject): ToolResult {
        val canonical = AgentToolCatalog.ALIASES[name] ?: name
        val exec = executors[canonical]
            ?: return ToolResult.fail("未知工具 $name", code = "unknown_tool")
        return try {
            val raw = exec(args)
            val ok = !raw.contains("\"ok\":false") && !raw.contains("\"ok\": false")
            val retryable = raw.contains("amap_error") || raw.contains("timeout")
            ToolResult(ok, raw, error = if (ok) null else field(raw, "message").ifBlank { field(raw, "error") }, retryable = retryable)
        } catch (error: Exception) {
            ToolResult.fail(error.message ?: "工具 $canonical 失败", retryable = true)
        }
    }

    private fun field(json: String, key: String): String {
        val token = "\"$key\""
        val start = json.indexOf(token)
        if (start < 0) return ""
        val colon = json.indexOf(':', start + token.length)
        if (colon < 0) return ""
        val from = json.indexOf('"', colon + 1)
        val to = json.indexOf('"', from + 1)
        if (from < 0 || to < 0) return ""
        return json.substring(from + 1, to)
    }
}
