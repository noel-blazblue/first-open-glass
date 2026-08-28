package com.glass.dining.shared.agent

data class AgentEvent(
    val runId: String,
    val step: Int,
    val kind: String,
    val name: String = "",
    val latencyMs: Long = 0,
    val detail: String = "",
)

data class AgentOutcome(
    val speak: String,
    val steps: Int,
    val messages: List<LlmMessage>,
    val reachedLimit: Boolean = false,
    val failed: Boolean = false,
    val cancelled: Boolean = false,
    val events: List<AgentEvent> = emptyList(),
)
