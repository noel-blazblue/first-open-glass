package com.glass.dining.shared.agent

/**
 * 校验事件生命周期与证据不变量。不按活动名称或地点词分支。
 */
object EventReducer {
    const val OP_START = "start"
    const val OP_CONTINUE = "continue"
    const val OP_TRANSITION = "transition"
    const val OP_COMPLETE = "complete"
    const val OP_REVISE = "revise"
    const val OP_NO_EVENT = "no_event"

    const val REL_CONTINUES = "continues"
    const val REL_SUPPORTS = "supports"
    const val REL_CONTRADICTS = "contradicts"
    const val REL_FOLLOWS = "follows"
    const val REL_RETURNS = "returns_to"
    const val REL_UNRELATED = "unrelated"

    const val MAX_RECENT = 5

    private val operations = setOf(
        OP_START, OP_CONTINUE, OP_TRANSITION, OP_COMPLETE, OP_REVISE, OP_NO_EVENT,
    )
    private val relations = setOf(
        REL_CONTINUES, REL_SUPPORTS, REL_CONTRADICTS, REL_FOLLOWS, REL_RETURNS, REL_UNRELATED,
    )
    private val movement = setOf(REL_CONTINUES, REL_FOLLOWS, REL_RETURNS)

    fun apply(
        proposal: EventProposal,
        prev: EnvironmentState,
        now: Long,
        spatial: SpatialEvidence = SpatialEvidence(),
        look: EnvironmentLook = EnvironmentLook(),
    ): EnvironmentState {
        val at = if (prev.updatedAt > 0L) maxOf(now, prev.updatedAt) else now
        val incoming = if (proposal.operation.lowercase() in operations) {
            proposal
        } else {
            conservative(look, prev, spatial).copy(
                observedClaims = proposal.observedClaims.ifEmpty {
                    conservative(look, prev, spatial).observedClaims
                },
            )
        }
        val op = coerce(incoming.operation.lowercase(), incoming, prev)
        val observed = demixObserved(incoming.observedClaims, incoming.inferredClaims)
        val inferred = incoming.inferredClaims.filter { claim ->
            observed.none { it.text == claim.text }
        }.take(8)
        val rels = sanitizeRelations(incoming.relations, prev, spatial, observed + inferred)
        val cleaned = incoming.copy(
            operation = op,
            observedClaims = observed,
            inferredClaims = inferred,
            relations = rels,
            objects = incoming.objects.ifEmpty { look.objects }.take(8),
            actions = incoming.actions.ifEmpty { look.actions }.take(8),
            location = incoming.location.ifBlank { look.location }.ifBlank { look.placeHint },
            actors = incoming.actors.ifEmpty { look.actors }.take(6),
        )
        return when (cleaned.operation) {
            OP_NO_EVENT -> prev.copy(updatedAt = at)
            OP_REVISE -> revise(prev, cleaned, spatial, at)
            OP_COMPLETE -> complete(prev, cleaned, spatial, look, at, startNext = false)
            OP_TRANSITION -> complete(prev, cleaned, spatial, look, at, startNext = true)
            OP_START -> startOrContinue(prev, cleaned, spatial, look, at)
            else -> continueEvent(prev, cleaned, spatial, look, at)
        }
    }

    fun conservative(
        look: EnvironmentLook,
        prev: EnvironmentState,
        spatial: SpatialEvidence = SpatialEvidence(),
    ): EventProposal {
        val active = prev.activeEvent
        val observed = look.observedClaims.ifEmpty {
            listOfNotNull(
                look.sceneBrief.takeIf { it.isNotBlank() }?.let {
                    ObservedClaim(id = "obs-${spatial.episodeId.ifBlank { "scene" }}", text = it.take(80), evidence = "scene")
                },
            )
        }
        return EventProposal(
            operation = if (active == null) OP_START else OP_CONTINUE,
            summary = active?.summary.orEmpty().ifBlank { look.sceneBrief.take(48) },
            hypothesis = "",
            observedClaims = observed,
            objects = look.objects,
            actions = look.actions,
            location = look.location.ifBlank { look.placeHint },
            actors = look.actors,
            evidenceLevel = EnvironmentObservation.STATUS_OBSERVED,
        )
    }

    private fun coerce(
        operation: String,
        proposal: EventProposal,
        prev: EnvironmentState,
    ): String {
        val active = prev.activeEvent ?: return if (operation == OP_NO_EVENT) OP_NO_EVENT else OP_START
        if (operation == OP_REVISE || operation == OP_COMPLETE || operation == OP_NO_EVENT) return operation
        if (operation == OP_START || operation == OP_TRANSITION) {
            val summary = proposal.summary.trim()
            if (summary.isBlank() || similar(summary, active.summary)) return OP_CONTINUE
            if (operation == OP_START) return OP_TRANSITION
        }
        return operation
    }

    private fun startOrContinue(
        prev: EnvironmentState,
        proposal: EventProposal,
        spatial: SpatialEvidence,
        look: EnvironmentLook,
        now: Long,
    ): EnvironmentState {
        if (prev.activeEvent != null) return continueEvent(prev, proposal, spatial, look, now)
        return prev.copy(activeEvent = newEvent(proposal, spatial, look, now), updatedAt = now)
    }

    private fun continueEvent(
        prev: EnvironmentState,
        proposal: EventProposal,
        spatial: SpatialEvidence,
        look: EnvironmentLook,
        now: Long,
    ): EnvironmentState {
        val active = prev.activeEvent ?: return startOrContinue(prev, proposal, spatial, look, now)
        val level = mergeLevel(active.confidence, proposal.evidenceLevel, proposal.hypothesis)
        val summary = proposal.summary.trim().ifBlank { active.summary.ifBlank { look.sceneBrief.take(48) } }
        val hypothesis = when {
            level == EnvironmentObservation.STATUS_HYPOTHESIS ->
                proposal.hypothesis.ifBlank { proposal.summary }.ifBlank { active.hypothesis }
            else -> proposal.hypothesis
        }
        val next = active.copy(
            updatedAt = now,
            summary = summary,
            hypothesis = hypothesis,
            confidence = level,
            to = proposal.location.ifBlank { active.to },
            location = proposal.location.ifBlank { active.location }.ifBlank { proposal.location },
            objects = (active.objects + proposal.objects).distinct().take(8),
            actions = (active.actions + proposal.actions).distinct().take(8),
            actors = (active.actors + proposal.actors).distinct().take(6),
            episodeCount = active.episodeCount + 1,
            episodeIds = mergeIds(active.episodeIds, spatial.episodeId),
            relations = proposal.relations.ifEmpty { active.relations },
            observedClaims = (active.observedClaims + proposal.observedClaims).distinctBy { it.text }.take(8),
            topologyNodeId = spatial.topologyNodeId.ifBlank { active.topologyNodeId },
            poseX = spatial.poseX,
            poseY = spatial.poseY,
            poseZ = spatial.poseZ,
        )
        return prev.copy(activeEvent = next, updatedAt = now)
    }

    private fun revise(
        prev: EnvironmentState,
        proposal: EventProposal,
        spatial: SpatialEvidence,
        now: Long,
    ): EnvironmentState {
        val active = prev.activeEvent ?: return prev.copy(
            activeEvent = newEvent(proposal, spatial, EnvironmentLook(), now),
            updatedAt = now,
        )
        val next = active.copy(
            updatedAt = now,
            summary = proposal.summary.trim().ifBlank { active.summary },
            hypothesis = proposal.hypothesis,
            confidence = proposal.evidenceLevel.ifBlank { EnvironmentObservation.STATUS_INFERRED },
            to = proposal.location.ifBlank { active.to },
            location = proposal.location.ifBlank { active.location },
            objects = (active.objects + proposal.objects).distinct().take(8),
            actions = (active.actions + proposal.actions).distinct().take(8),
            actors = (active.actors + proposal.actors).distinct().take(6),
            episodeCount = active.episodeCount + 1,
            episodeIds = mergeIds(active.episodeIds, spatial.episodeId),
            relations = proposal.relations,
            observedClaims = (proposal.observedClaims + active.observedClaims).distinctBy { it.text }.take(8),
            topologyNodeId = spatial.topologyNodeId.ifBlank { active.topologyNodeId },
            poseX = spatial.poseX,
            poseY = spatial.poseY,
            poseZ = spatial.poseZ,
        )
        return prev.copy(activeEvent = next, updatedAt = now)
    }

    private fun complete(
        prev: EnvironmentState,
        proposal: EventProposal,
        spatial: SpatialEvidence,
        look: EnvironmentLook,
        now: Long,
        startNext: Boolean,
    ): EnvironmentState {
        val active = prev.activeEvent
        val closed = active?.copy(updatedAt = now)
        val kept = listOfNotNull(closed) + prev.recentEvents
        val next = if (startNext || proposal.summary.isNotBlank() || look.sceneBrief.isNotBlank()) {
            newEvent(proposal, spatial, look, now, related = closed)
        } else {
            null
        }
        return prev.copy(
            activeEvent = next,
            recentEvents = kept.take(MAX_RECENT),
            updatedAt = now,
        )
    }

    private fun newEvent(
        proposal: EventProposal,
        spatial: SpatialEvidence,
        look: EnvironmentLook,
        now: Long,
        related: EnvironmentEvent? = null,
    ): EnvironmentEvent {
        val location = proposal.location.ifBlank { look.location }.ifBlank { look.placeHint }
        val rels = if (related != null && proposal.relations.none { it.relatedEventId == related.id }) {
            listOf(
                EventRelation(
                    relatedEventId = related.id,
                    type = proposal.relations.firstOrNull()?.type?.ifBlank { REL_FOLLOWS } ?: REL_FOLLOWS,
                    evidenceRefs = listOfNotNull(spatial.episodeId.takeIf { it.isNotBlank() }),
                    evidenceLevel = EnvironmentObservation.STATUS_INFERRED,
                ),
            ) + proposal.relations
        } else {
            proposal.relations
        }
        val summary = proposal.summary.trim().ifBlank { look.sceneBrief.take(48) }
        val level = proposal.evidenceLevel.ifBlank {
            if (proposal.hypothesis.isNotBlank()) EnvironmentObservation.STATUS_HYPOTHESIS
            else EnvironmentObservation.STATUS_OBSERVED
        }
        return EnvironmentEvent(
            id = "evt-$now",
            startedAt = now,
            updatedAt = now,
            summary = summary,
            hypothesis = proposal.hypothesis,
            confidence = level,
            from = location,
            to = location,
            location = location,
            objects = proposal.objects.ifEmpty { look.objects }.take(8),
            actions = proposal.actions.ifEmpty { look.actions }.take(8),
            actors = proposal.actors.ifEmpty { look.actors }.take(6),
            episodeCount = 1,
            episodeIds = mergeIds(emptyList(), spatial.episodeId),
            relations = rels.take(6),
            observedClaims = proposal.observedClaims.take(8),
            topologyNodeId = spatial.topologyNodeId,
            poseX = spatial.poseX,
            poseY = spatial.poseY,
            poseZ = spatial.poseZ,
        )
    }

    private fun sanitizeRelations(
        incoming: List<EventRelation>,
        prev: EnvironmentState,
        spatial: SpatialEvidence,
        claims: List<ObservedClaim>,
    ): List<EventRelation> {
        val knownEvents = buildSet {
            prev.activeEvent?.id?.let { add(it) }
            prev.recentEvents.forEach { add(it.id) }
        }
        val knownRefs = buildSet {
            claims.forEach { if (it.id.isNotBlank()) add(it.id) }
            if (spatial.episodeId.isNotBlank()) add(spatial.episodeId)
            if (spatial.topologyNodeId.isNotBlank()) add(spatial.topologyNodeId)
            if (spatial.topologyEdgeId.isNotBlank()) add(spatial.topologyEdgeId)
            if (spatial.hasPose) add("pose")
            if (spatial.loopClosed) add("loop")
            if (spatial.tracking.isNotBlank()) add("tracking")
            add("scene")
        }
        return incoming.mapNotNull { rel ->
            val type = rel.type.trim().lowercase()
            if (type !in relations) return@mapNotNull null
            if (rel.relatedEventId.isNotBlank() && rel.relatedEventId !in knownEvents) return@mapNotNull null
            val refs = rel.evidenceRefs.filter { it.isBlank() || it in knownRefs }
            if (rel.evidenceRefs.isNotEmpty() && refs.none { it.isNotBlank() }) return@mapNotNull null
            if (type in movement && spatial.trackingLost) return@mapNotNull null
            val related = rel.relatedEventId.ifBlank { prev.activeEvent?.id.orEmpty() }
            val level = if (type == REL_RETURNS && spatial.loopClosed) {
                EnvironmentObservation.STATUS_OBSERVED
            } else {
                rel.evidenceLevel.ifBlank { EnvironmentObservation.STATUS_INFERRED }
            }
            val withLoop = if (type == REL_RETURNS && spatial.loopClosed) {
                (refs + "loop").distinct()
            } else {
                refs
            }
            EventRelation(
                relatedEventId = related,
                type = type,
                evidenceRefs = withLoop,
                evidenceLevel = level,
            )
        }.take(6)
    }

    private fun demixObserved(
        observed: List<ObservedClaim>,
        inferred: List<ObservedClaim>,
    ): List<ObservedClaim> {
        return observed.filter { claim ->
            claim.text.isNotBlank() && inferred.none { it.id.isNotBlank() && it.id == claim.id && it.text != claim.text }
        }.distinctBy { it.text }.take(8)
    }

    private fun mergeLevel(current: String, incoming: String, hypothesis: String): String {
        if (incoming.isNotBlank()) return incoming
        if (hypothesis.isNotBlank()) return EnvironmentObservation.STATUS_HYPOTHESIS
        return current
    }

    private fun mergeIds(current: List<String>, next: String): List<String> {
        return (current + next).filter { it.isNotBlank() }.distinct().take(8)
    }

    private fun similar(a: String, b: String): Boolean {
        val left = a.trim()
        val right = b.trim()
        if (left.isBlank() || right.isBlank()) return false
        return left == right || left.contains(right) || right.contains(left)
    }
}
