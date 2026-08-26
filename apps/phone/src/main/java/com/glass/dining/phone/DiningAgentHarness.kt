package com.glass.dining.phone

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 到餐 Agent 的轻量运行时。
 *
 * 业务状态仍由 DiningSession 持有；这里仅负责有界模型循环、工具执行、
 * 高风险动作护栏和逐步追踪，避免把安全性寄托在 system prompt 上。
 */
internal class DiningAgentHarness(
    private val maxSteps: Int = 3,
    private val maxToolCallsPerStep: Int = 4,
) {
    data class Outcome(
        val finalMessage: JSONObject? = null,
        val steps: Int,
        val reachedLimit: Boolean = false,
        val failed: Boolean = false,
    )

    fun run(
        messages: JSONArray,
        tools: JSONArray,
        userText: String,
        requestModel: (JSONArray, JSONArray) -> JSONObject?,
        orderCalls: (JSONArray) -> JSONArray,
        executeTool: (String, JSONObject) -> String,
    ): Outcome {
        for (step in 1..maxSteps) {
            val startedAt = System.currentTimeMillis()
            val message = requestModel(messages, tools)
            if (message == null) {
                trace(step, "model_error", startedAt)
                return Outcome(steps = step, failed = true)
            }
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                trace(step, "final", startedAt)
                return Outcome(finalMessage = message, steps = step)
            }

            // 原样回灌 assistant message，兼容 DeepSeek thinking 模式要求的 reasoning_content。
            messages.put(message)
            val ordered = orderCalls(calls)
            val count = minOf(ordered.length(), maxToolCallsPerStep)
            for (index in 0 until count) {
                val call = ordered.optJSONObject(index) ?: continue
                val id = call.optString("id")
                val function = call.optJSONObject("function")
                val name = function?.optString("name").orEmpty()
                val args = parseArgs(function?.optString("arguments"))
                val blocked = guard(name, args, userText)
                val result = when {
                    id.isBlank() || name.isBlank() -> errorResult("工具调用格式不完整")
                    blocked != null -> errorResult(blocked, policyBlocked = true)
                    else -> try {
                        executeTool(name, args)
                    } catch (error: Exception) {
                        errorResult(error.message ?: "工具 $name 执行失败")
                    }
                }
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result),
                )
                Log.i(TAG, "agent step=$step tool=$name blocked=${blocked != null}")
            }
            if (ordered.length() > count) {
                for (index in count until ordered.length()) {
                    val call = ordered.optJSONObject(index) ?: continue
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.optString("id"))
                            .put("content", errorResult("单步工具调用过多，已停止执行")),
                    )
                }
            }
            trace(step, "tools=$count", startedAt)
        }
        Log.w(TAG, "agent reached maxSteps=$maxSteps")
        return Outcome(steps = maxSteps, reachedLimit = true)
    }

    private fun guard(name: String, args: JSONObject, userText: String): String? {
        if (name !in CONFIRM_TOOLS || !args.optBoolean("confirm", false)) return null
        val text = userText.trim()
        if (NEGATIONS.any { text.contains(it) }) {
            return "用户本轮包含否定或取消表达，禁止确认操作"
        }
        val normalized = text.trim().trim('。', '！', '!', '？', '?', '，', ',')
        val explicitlyConfirmed = normalized in SHORT_CONFIRMATIONS ||
            ACTION_CONFIRMATIONS.any { normalized.contains(it) }
        if (!explicitlyConfirmed) {
            return "用户本轮没有明确确认，confirm 必须为 false"
        }
        return null
    }

    private fun parseArgs(raw: String?): JSONObject {
        if (raw.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun errorResult(message: String, policyBlocked: Boolean = false): String {
        return JSONObject()
            .put("ok", false)
            .put("error", message)
            .put("policy_blocked", policyBlocked)
            .toString()
    }

    private fun trace(step: Int, result: String, startedAt: Long) {
        Log.i(TAG, "agent step=$step result=$result elapsed=${System.currentTimeMillis() - startedAt}ms")
    }

    companion object {
        private const val TAG = "GlassDiningPhone"
        private val CONFIRM_TOOLS = setOf("checkout", "redeem_coupon")
        private val SHORT_CONFIRMATIONS = setOf(
            "确认", "确定", "同意", "好的", "可以", "行", "付吧", "用吧",
        )
        private val ACTION_CONFIRMATIONS = listOf(
            "确认付款", "确认支付", "确认核销", "付款吧", "付钱吧", "支付吧", "核销吧",
            "就付", "就用这张", "可以付款", "可以支付", "可以核销",
        )
        private val NEGATIONS = listOf(
            "不确认", "不付", "别付", "不要付", "不核销", "别核销", "不要核销", "取消", "等等", "先别",
        )
    }
}
