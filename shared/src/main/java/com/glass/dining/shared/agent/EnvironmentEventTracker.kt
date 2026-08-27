package com.glass.dining.shared.agent

/**
 * 把一次稳定观察交给通用 reducer。不识别具体活动名称。
 */
object EnvironmentEventTracker {
    fun ingest(
        look: EnvironmentLook,
        prev: EnvironmentState,
        now: Long,
        spatial: SpatialEvidence = SpatialEvidence(),
    ): EnvironmentState {
        return EventReducer.apply(
            proposal = look.eventProposal ?: EventReducer.conservative(look, prev, spatial),
            prev = prev,
            now = now,
            spatial = spatial,
            look = look,
        )
    }
}
