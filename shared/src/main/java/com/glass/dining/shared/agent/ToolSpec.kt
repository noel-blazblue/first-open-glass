package com.glass.dining.shared.agent

enum class ToolRisk { READ, WRITE, CONFIRM }

data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
    val risk: ToolRisk = ToolRisk.READ,
    val timeoutMs: Long = 15_000,
    val requiredCapability: String = "",
    val parallelSafe: Boolean = true,
    val maxRetries: Int = 1,
)
