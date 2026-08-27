package com.glass.dining.shared.agent

import com.glass.dining.shared.nav.LandmarkSignage

object EnvironmentMerge {
    fun fromSpeech(utterance: String, prev: EnvironmentState, now: Long): EnvironmentState {
        val spoken = LandmarkSignage.parseSpokenFloor(utterance)
        if (spoken.isBlank()) return prev
        val fact = "用户确认在${spoken}楼"
        val facts = (listOf(fact) + prev.userFacts.filterNot { it.contains("楼") }).take(4)
        return prev.copy(userFacts = facts, updatedAt = now)
    }

    fun fromVlmNote(raw: String, prev: EnvironmentState, now: Long): EnvironmentState {
        return fromLook(parseLook(raw), prev, now)
    }

    fun fromLook(look: EnvironmentLook, prev: EnvironmentState, now: Long): EnvironmentState {
        val scene = look.sceneBrief.trim()
        if (scene.isBlank()) return prev
        val change = look.change.trim()
        val keepChange = change.isNotBlank() &&
            !change.contains("没有明显变化") &&
            !change.contains("无变化")
        val recent = if (keepChange) {
            (listOf(EnvironmentChange(change.take(80), now)) + prev.recentChanges).take(4)
        } else {
            prev.recentChanges
        }
        val withScene = prev.copy(
            currentBrief = scene.take(280),
            recentChanges = recent,
            updatedAt = now,
        )
        val withText = mergeSalient(withScene, look, now)
        val space = look.spaceType.ifBlank { look.placeHint }.lowercase()
        val movedLevels = space in levelSpaces ||
            look.actions.any { it.contains("上楼") || it.contains("下楼") }
        val cleared = if (movedLevels && resolvedFloor(look).first.isBlank()) {
            staleFloors(withText)
        } else {
            withText
        }
        val (floor, evidence) = resolvedFloor(look)
        val floorOk = floor.isNotBlank() &&
            (look.confidence >= 0.45f ||
                FloorSignage.hasCue(evidence + look.salientText + look.floorCandidate) ||
                look.spaceType.equals("signage", true) ||
                look.spaceType.equals("elevator", true))
        return if (floorOk) {
            mergeFloor(cleared, floor, evidence, look.confidence, now)
        } else {
            cleared
        }
    }

    fun fromSignage(
        ocrSamples: List<String>,
        prev: EnvironmentState,
        now: Long,
        minHits: Int = 1,
    ): EnvironmentState {
        val candidate = FloorSignage.stabilize(ocrSamples, minHits) ?: return prev
        return mergeFloor(prev, candidate.floor, candidate.evidence, candidate.confidence, now)
    }

    fun parseLook(raw: String): EnvironmentLook = EventAnalysis.parseLook(raw)

    fun parseNote(raw: String): ParsedNote {
        val text = raw.trim().replace("```", "").trim()
        if (text.isBlank()) return ParsedNote()
        val scene = field(text, "当前场景")
        val change = field(text, "相对变化")
        if (scene.isNotBlank() || change.isNotBlank()) {
            return ParsedNote(scene.take(280), change.take(80))
        }
        return ParsedNote(text.take(280), "")
    }

    private fun mergeSalient(prev: EnvironmentState, look: EnvironmentLook, now: Long): EnvironmentState {
        val text = look.salientText.trim()
        if (text.isBlank()) return prev
        val others = prev.recentObservations.filterNot { it.kind == EnvironmentObservation.KIND_TEXT }
        val next = EnvironmentObservation(
            kind = EnvironmentObservation.KIND_TEXT,
            value = text.take(48),
            evidence = look.spaceType.ifBlank { look.placeHint },
            confidence = look.confidence,
            observedAt = now,
            status = EnvironmentObservation.STATUS_OBSERVED,
        )
        return prev.copy(recentObservations = (listOf(next) + others).take(8), updatedAt = now)
    }

    private fun resolvedFloor(look: EnvironmentLook): Pair<String, String> {
        val fromNav = LandmarkSignage.normalizeFloor(look.floorCandidate)
        if (fromNav.isNotBlank()) {
            return fromNav to look.floorEvidence.ifBlank { look.salientText }
        }
        val fromText = LandmarkSignage.parseVisibleFloor(look.salientText).ifBlank {
            LandmarkSignage.parseVisibleFloor(look.floorEvidence)
        }.ifBlank {
            look.observedClaims.firstNotNullOfOrNull { claim ->
                LandmarkSignage.parseVisibleFloor(claim.text).ifBlank { null }
            }.orEmpty()
        }
        return fromText to look.salientText.ifBlank { look.floorEvidence }
    }

    private fun staleFloors(prev: EnvironmentState): EnvironmentState {
        val next = prev.recentObservations.map { item ->
            if (item.kind == EnvironmentObservation.KIND_FLOOR) {
                item.copy(status = EnvironmentObservation.STATUS_STALE)
            } else {
                item
            }
        }
        return prev.copy(recentObservations = next)
    }

    private fun mergeFloor(
        prev: EnvironmentState,
        floor: String,
        evidence: String,
        confidence: Float,
        now: Long,
    ): EnvironmentState {
        val value = LandmarkSignage.normalizeFloor(floor)
        if (value.isBlank()) return prev
        val existing = prev.recentObservations.filter { it.kind == EnvironmentObservation.KIND_FLOOR }
        val latest = existing.maxByOrNull { it.observedAt }
        val others = prev.recentObservations.filterNot { it.kind == EnvironmentObservation.KIND_FLOOR }
        if (latest != null && latest.value == value && latest.status != EnvironmentObservation.STATUS_STALE) {
            val boosted = latest.copy(
                evidence = evidence.ifBlank { latest.evidence },
                confidence = maxOf(latest.confidence, confidence),
                observedAt = now,
                status = if (confidence >= 0.7f) EnvironmentObservation.STATUS_CONFIRMED else latest.status,
            )
            return prev.copy(recentObservations = listOf(boosted) + others, updatedAt = now)
        }
        val next = EnvironmentObservation(
            kind = EnvironmentObservation.KIND_FLOOR,
            value = value,
            evidence = evidence.take(80),
            confidence = confidence,
            observedAt = now,
            status = if (confidence >= 0.7f) {
                EnvironmentObservation.STATUS_CONFIRMED
            } else {
                EnvironmentObservation.STATUS_OBSERVED
            },
        )
        val staleOld = existing.map { it.copy(status = EnvironmentObservation.STATUS_STALE) }
        return prev.copy(
            recentObservations = (listOf(next) + staleOld + others).take(8),
            updatedAt = now,
        )
    }

    private fun field(text: String, label: String): String {
        val match = Regex("$label\\s*[：:]\\s*(.+)").find(text) ?: return ""
        return match.groupValues[1].trim()
    }

    data class ParsedNote(val scene: String = "", val change: String = "")

    private val levelSpaces = setOf("elevator", "stairs", "escalator")
}

object FloorSignage {
    data class Candidate(val floor: String, val evidence: String, val confidence: Float, val hits: Int)

    fun stabilize(ocrSamples: List<String>, minHits: Int = 2): Candidate? {
        val parsed = ocrSamples.mapNotNull { sample ->
            val floor = LandmarkSignage.parseVisibleFloor(sample)
            if (floor.isBlank() || !hasCue(sample)) return@mapNotNull null
            floor to sample
        }
        if (parsed.isEmpty()) return null
        val grouped = parsed.groupBy { it.first }
        val best = grouped.maxByOrNull { it.value.size } ?: return null
        if (best.value.size < minHits) return null
        val evidence = best.value.first().second.replace("\n", " ").take(60)
        return Candidate(
            floor = best.key,
            evidence = evidence,
            confidence = (0.45f + 0.15f * best.value.size).coerceAtMost(0.85f),
            hits = best.value.size,
        )
    }

    fun hasCue(text: String): Boolean {
        return Regex("""层|楼|[Ff]|电梯|导览|指示""").containsMatchIn(text)
    }
}

object FloorQueryPolicy {
    data class Answer(val floor: String, val source: String, val speak: String)

    fun resolve(env: EnvironmentState): Answer? {
        env.userFacts.forEach { fact ->
            val spoken = LandmarkSignage.parseSpokenFloor(fact).ifBlank {
                LandmarkSignage.parseVisibleFloor(fact)
            }
            if (spoken.isNotBlank()) {
                return Answer(spoken, "user", "你刚确认在${spoken}楼")
            }
        }
        val sign = env.recentObservations
            .filter { it.kind == EnvironmentObservation.KIND_FLOOR && it.status != EnvironmentObservation.STATUS_STALE }
            .maxByOrNull { it.observedAt }
            ?: return null
        if (sign.value.isBlank()) return null
        return Answer(sign.value, "sign", "刚才看到的楼层标识是${sign.value}楼")
    }

    fun isFloorAsk(text: String): Boolean {
        val q = text.trim()
        if (q.isBlank()) return false
        return q.contains("几楼") || q.contains("哪一层") || q.contains("楼层") ||
            q.contains("第几层") || q.contains("现在在几")
    }
}
