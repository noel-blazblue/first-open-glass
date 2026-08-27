package com.glass.dining.shared.agent

/**
 * 在稳定视野 episode 上维护一条可修正的活动线程。
 * 只消费结构化观察，不读 JPEG。
 */
object EnvironmentEventTracker {
    const val PLACE_DESK = "desk"
    const val PLACE_CORRIDOR = "corridor"
    const val PLACE_COFFEE = "coffee"
    const val PLACE_MEETING = "meeting"
    const val PLACE_ELEVATOR = "elevator"
    const val PLACE_SIGNAGE = "signage"
    const val PLACE_OUTDOOR = "outdoor"
    const val PLACE_OTHER = "other"

    fun ingest(look: EnvironmentLook, prev: EnvironmentState, now: Long): EnvironmentState {
        val place = classify(look)
        val active = prev.activeEvent
        if (active == null) {
            return prev.copy(activeEvent = start(place, look, now), updatedAt = now)
        }
        val op = decide(active, place, look)
        return when (op) {
            Op.Continue -> prev.copy(activeEvent = continueEvent(active, place, look, now), updatedAt = now)
            Op.Revise -> prev.copy(activeEvent = revise(active, place, look, now), updatedAt = now)
            Op.Complete -> complete(prev, active, place, look, now)
        }
    }

    fun classify(look: EnvironmentLook): String {
        val hint = look.placeHint.trim().lowercase()
        if (hint in knownPlaces) return hint
        val text = blob(look)
        return when {
            hasCoffeeMachine(text) -> PLACE_COFFEE
            hasMeeting(text) -> PLACE_MEETING
            hasDesk(text) -> PLACE_DESK
            hasCorridor(text) -> PLACE_CORRIDOR
            hasSignage(text) -> PLACE_SIGNAGE
            hasElevator(text) -> PLACE_ELEVATOR
            else -> PLACE_OTHER
        }
    }

    private enum class Op { Continue, Revise, Complete }

    private fun decide(active: EnvironmentEvent, place: String, look: EnvironmentLook): Op {
        val from = active.to.ifBlank { active.from }
        if (place == from) return Op.Continue
        if (transitional(place) && (from == PLACE_DESK || transitional(from))) return Op.Continue
        if (place == PLACE_COFFEE && (from == PLACE_DESK || transitional(from))) {
            return if (hasCoffeeEvidence(look)) Op.Continue else Op.Continue
        }
        if (place == PLACE_MEETING && (from == PLACE_DESK || transitional(from) || from == PLACE_COFFEE)) {
            return Op.Revise
        }
        if (place == PLACE_DESK && from != PLACE_DESK) return Op.Complete
        if (!transitional(place) && !transitional(from) && place != from) return Op.Complete
        return Op.Continue
    }

    private fun start(place: String, look: EnvironmentLook, now: Long): EnvironmentEvent {
        return EnvironmentEvent(
            id = "evt-$now",
            startedAt = now,
            updatedAt = now,
            summary = observedSummary(place, look),
            hypothesis = "",
            confidence = EnvironmentObservation.STATUS_OBSERVED,
            from = place,
            to = place,
            objects = look.objects.take(6),
            actions = look.actions.take(6),
            episodeCount = 1,
        )
    }

    private fun continueEvent(
        active: EnvironmentEvent,
        place: String,
        look: EnvironmentLook,
        now: Long,
    ): EnvironmentEvent {
        val from = active.from.ifBlank { active.to }
        val coffee = place == PLACE_COFFEE && hasCoffeeEvidence(look)
        val leaving = from == PLACE_DESK && transitional(place)
        val (summary, hypothesis, confidence) = when {
            coffee -> Triple("从工位起身去接咖啡", "", EnvironmentObservation.STATUS_CONFIRMED)
            leaving -> Triple("离开工位并移动中", "离开工位并移动中", "hypothesis")
            place == PLACE_COFFEE -> Triple(
                active.summary.ifBlank { "在咖啡区域" },
                "可能去接咖啡",
                "hypothesis",
            )
            else -> Triple(
                active.summary.ifBlank { observedSummary(place, look) },
                active.hypothesis,
                active.confidence,
            )
        }
        return active.copy(
            updatedAt = now,
            summary = summary,
            hypothesis = hypothesis,
            confidence = confidence,
            to = place,
            objects = (active.objects + look.objects).distinct().take(8),
            actions = (active.actions + look.actions).distinct().take(8),
            episodeCount = active.episodeCount + 1,
        )
    }

    private fun revise(
        active: EnvironmentEvent,
        place: String,
        look: EnvironmentLook,
        now: Long,
    ): EnvironmentEvent {
        val summary = if (place == PLACE_MEETING) {
            "从工位前往会议区域"
        } else {
            observedSummary(place, look)
        }
        return active.copy(
            updatedAt = now,
            summary = summary,
            hypothesis = "",
            confidence = EnvironmentObservation.STATUS_CONFIRMED,
            to = place,
            objects = (active.objects + look.objects).distinct().take(8),
            actions = (active.actions + look.actions).distinct().take(8),
            episodeCount = active.episodeCount + 1,
        )
    }

    private fun complete(
        prev: EnvironmentState,
        active: EnvironmentEvent,
        place: String,
        look: EnvironmentLook,
        now: Long,
    ): EnvironmentState {
        val closed = active.copy(updatedAt = now)
        val kept = (listOf(closed) + prev.recentEvents).take(5)
        return prev.copy(
            activeEvent = start(place, look, now).copy(
                summary = if (place == PLACE_DESK) "回到工位操作电脑" else observedSummary(place, look),
            ),
            recentEvents = kept,
            updatedAt = now,
        )
    }

    private fun observedSummary(place: String, look: EnvironmentLook): String {
        return when (place) {
            PLACE_DESK -> "在工位操作电脑"
            PLACE_COFFEE -> "在咖啡区域"
            PLACE_MEETING -> "在会议区域"
            PLACE_CORRIDOR -> "移动中"
            PLACE_SIGNAGE -> "在看标识"
            PLACE_ELEVATOR -> "在电梯附近"
            else -> look.sceneBrief.take(24).ifBlank { "在观察周围" }
        }
    }

    private fun transitional(place: String): Boolean {
        return place == PLACE_CORRIDOR || place == PLACE_ELEVATOR ||
            place == PLACE_SIGNAGE || place == PLACE_OTHER
    }

    private fun blob(look: EnvironmentLook): String {
        return buildString {
            append(look.placeHint).append(' ')
            append(look.sceneBrief).append(' ')
            append(look.salientText).append(' ')
            look.objects.forEach { append(it).append(' ') }
            look.actions.forEach { append(it).append(' ') }
        }
    }

    private fun hasCoffeeEvidence(look: EnvironmentLook): Boolean {
        val text = blob(look)
        val machine = hasCoffeeMachine(text)
        val take = listOf("杯", "接", "倒", "拿", "领取").any { text.contains(it) } ||
            look.actions.any { it.contains("接") || it.contains("拿") }
        return machine && take
    }

    private fun hasCoffeeMachine(text: String): Boolean {
        return listOf("咖啡机", "咖啡", "coffee").any { text.contains(it, ignoreCase = true) }
    }

    private fun hasMeeting(text: String): Boolean {
        return listOf("会议", "投影", "白板", "meeting").any { text.contains(it, ignoreCase = true) }
    }

    private fun hasDesk(text: String): Boolean {
        return listOf("电脑", "显示器", "键盘", "工位", "办公桌", "屏幕").any { text.contains(it) }
    }

    private fun hasCorridor(text: String): Boolean {
        return listOf("走廊", "过道", "走动", "起身").any { text.contains(it) }
    }

    private fun hasSignage(text: String): Boolean {
        return listOf("楼层标识", "导览", "指示牌").any { text.contains(it) }
    }

    private fun hasElevator(text: String): Boolean {
        return text.contains("电梯")
    }

    private val knownPlaces = setOf(
        PLACE_DESK, PLACE_CORRIDOR, PLACE_COFFEE, PLACE_MEETING,
        PLACE_ELEVATOR, PLACE_SIGNAGE, PLACE_OUTDOOR, PLACE_OTHER,
    )
}
