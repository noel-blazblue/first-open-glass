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
        val movedLevels = look.placeHint == "elevator" ||
            look.sceneBrief.contains("楼梯") ||
            look.actions.any { it.contains("上楼") || it.contains("下楼") }
        val cleared = if (movedLevels && look.floorCandidate.isBlank()) {
            staleFloors(withScene)
        } else {
            withScene
        }
        val floorOk = look.floorCandidate.isNotBlank() &&
            (look.confidence >= 0.6f || FloorSignage.hasCue(look.floorEvidence + look.salientText))
        return if (floorOk) {
            mergeFloor(cleared, look.floorCandidate, look.floorEvidence, look.confidence, now)
        } else {
            cleared
        }
    }

    fun fromSignage(
        ocrSamples: List<String>,
        prev: EnvironmentState,
        now: Long,
        minHits: Int = 2,
    ): EnvironmentState {
        val candidate = FloorSignage.stabilize(ocrSamples, minHits) ?: return prev
        return mergeFloor(prev, candidate.floor, candidate.evidence, candidate.confidence, now)
    }

    fun parseLook(raw: String): EnvironmentLook {
        val json = extractJson(raw)
        if (json != null) {
            return EnvironmentLook(
                sceneBrief = json["sceneBrief"].orEmpty().ifBlank { json["scene"].orEmpty() },
                change = json["change"].orEmpty(),
                salientText = json["salientText"].orEmpty(),
                objects = splitList(json["objects"]),
                actions = splitList(json["actions"]),
                placeHint = json["placeHint"].orEmpty(),
                floorCandidate = LandmarkSignage.normalizeFloor(json["floorCandidate"].orEmpty()),
                floorEvidence = json["floorEvidence"].orEmpty(),
                confidence = json["confidence"]?.toFloatOrNull() ?: 0f,
            )
        }
        val parsed = parseNote(raw)
        return EnvironmentLook(
            sceneBrief = parsed.scene,
            change = parsed.change,
            salientText = parsed.scene.take(80),
            confidence = if (parsed.scene.isNotBlank()) 0.4f else 0f,
        )
    }

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

    private fun extractJson(raw: String): Map<String, String>? {
        val match = Regex("""\{[\s\S]*\}""").find(raw.trim()) ?: return null
        val body = match.value
        fun str(key: String): String {
            val quoted = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
            if (quoted != null) return quoted
            val array = Regex("\"$key\"\\s*:\\s*(\\[[^\\]]*])").find(body)?.groupValues?.get(1)
            if (array != null) return array
            return Regex("\"$key\"\\s*:\\s*([^,}\\]]+)").find(body)?.groupValues?.get(1)?.trim().orEmpty()
        }
        if (!body.contains("sceneBrief") && !body.contains("\"scene\"")) return null
        return mapOf(
            "sceneBrief" to str("sceneBrief"),
            "scene" to str("scene"),
            "change" to str("change"),
            "salientText" to str("salientText"),
            "objects" to str("objects"),
            "actions" to str("actions"),
            "placeHint" to str("placeHint"),
            "floorCandidate" to str("floorCandidate"),
            "floorEvidence" to str("floorEvidence"),
            "confidence" to str("confidence"),
        )
    }

    private fun splitList(raw: String?): List<String> {
        val text = raw.orEmpty().trim()
        if (text.isBlank()) return emptyList()
        return text
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .take(8)
    }

    data class ParsedNote(val scene: String = "", val change: String = "")
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
