package com.glass.dining.shared.agent

/**
 * 单次 run 的代码级护栏：能力校验、确认、副作用去重、读工具有限重试。
 * 每次 Agent 提问新建实例，不要跨轮复用。
 */
class ActionPolicy(
    private val capabilities: Set<String> = emptySet(),
) {
    private val usedSideEffects = linkedSetOf<String>()

    fun guard(spec: ToolSpec?, name: String, argumentsJson: String, userText: String): String? {
        val required = spec?.requiredCapability.orEmpty()
        if (required.isNotBlank() && required !in capabilities) {
            return capabilityMessage(required)
        }
        val risk = spec?.risk ?: return unknownWriteGuard(name)
        if (risk == ToolRisk.CONFIRM) {
            return confirmGuard(name, argumentsJson, userText)
        }
        if (risk == ToolRisk.WRITE) {
            val requestId = requestIdOf(argumentsJson).ifBlank { "$name:${userText.hashCode()}" }
            if (requestId in usedSideEffects) {
                return "副作用 $name 已执行过，request_id=$requestId，禁止重复"
            }
        }
        return null
    }

    fun markExecuted(spec: ToolSpec?, name: String, argumentsJson: String, userText: String) {
        if (spec?.risk != ToolRisk.WRITE && spec?.risk != ToolRisk.CONFIRM) return
        val requestId = requestIdOf(argumentsJson).ifBlank { "$name:${userText.hashCode()}" }
        usedSideEffects.add(requestId)
    }

    fun canRetry(spec: ToolSpec?, result: ToolResult, attempt: Int): Boolean {
        if (spec == null || spec.risk != ToolRisk.READ) return false
        if (!result.retryable || result.ok || result.policyBlocked) return false
        return attempt < spec.maxRetries
    }

    fun usedIds(): Set<String> = usedSideEffects.toSet()

    private fun confirmGuard(name: String, argumentsJson: String, userText: String): String? {
        if (!isConfirmTrue(argumentsJson)) return null
        val text = userText.trim()
        if (NEGATIONS.any { text.contains(it) }) {
            return "用户本轮包含否定或取消表达，禁止确认操作"
        }
        val normalized = text.trim().trim('。', '！', '!', '？', '?', '，', ',')
        val ok = normalized in SHORT_CONFIRMATIONS || ACTION_CONFIRMATIONS.any { normalized.contains(it) }
        if (!ok) {
            return "用户本轮没有明确确认，$name 的 confirm 必须为 false"
        }
        return null
    }

    private fun unknownWriteGuard(name: String): String? {
        return if (name in CONFIRM_TOOLS) "未知高风险工具 $name" else null
    }

    private fun capabilityMessage(capability: String): String {
        return when (capability) {
            WorldContext.CAP_GPS -> "没有定位权限或尚未拿到定位，请在系统设置打开精确位置，或到开阔处再试。"
            else -> "当前缺少能力 $capability，无法调用该工具"
        }
    }

    companion object {
        val CONFIRM_TOOLS = setOf("checkout", "redeem_coupon")
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

        fun isConfirmTrue(argumentsJson: String): Boolean {
            val raw = argumentsJson.lowercase()
            return raw.contains("\"confirm\"") && (
                raw.contains("\"confirm\":true") ||
                    raw.contains("\"confirm\": true") ||
                    raw.contains("\"confirm\":\"true\"")
                )
        }

        fun requestIdOf(argumentsJson: String): String {
            val key = "\"request_id\""
            val start = argumentsJson.indexOf(key)
            if (start < 0) return ""
            val colon = argumentsJson.indexOf(':', start + key.length)
            if (colon < 0) return ""
            val from = argumentsJson.indexOf('"', colon + 1)
            val to = argumentsJson.indexOf('"', from + 1)
            if (from < 0 || to < 0) return ""
            return argumentsJson.substring(from + 1, to)
        }
    }
}
