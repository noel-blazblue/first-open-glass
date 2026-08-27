package com.glass.dining.shared.agent

import com.glass.dining.shared.nav.LandmarkSignage

object EventAnalysis {
    private val observationKeys = listOf(
        "sceneBrief", "scene", "change", "salientText", "objects", "actions",
        "location", "actors", "observedClaims", "placeHint",
    )
    private val navigationKeys = listOf(
        "spaceType", "exits", "guideDir", "storeNames", "blocked",
        "floorCandidate", "floorEvidence",
    )
    private val proposalKeys = listOf(
        "operation", "eventSummary", "summary", "hypothesis", "observedClaims",
        "inferredClaims", "actors", "objects", "actions", "location",
        "confidence", "evidence", "evidenceLevel", "relations",
    )
    private val topKeys = observationKeys + navigationKeys + proposalKeys + listOf(
        "placeHint", "confidence",
    )

    fun parseLook(raw: String): EnvironmentLook {
        val root = JsonLite.objectBody(raw)
        if (root != null && (root.contains("sceneBrief") || root.contains("\"scene\"") || root.contains("observation"))) {
            val observation = JsonLite.child(root, "observation")
            val navigation = JsonLite.child(root, "navigation")
            val proposalBody = JsonLite.child(root, "eventProposal")
            val obs = if (observation != null) JsonLite.known(observation, observationKeys) else emptyMap()
            val nav = if (navigation != null) JsonLite.known(navigation, navigationKeys) else emptyMap()
            val flat = JsonLite.known(root, topKeys)
            fun pick(key: String, extra: Map<String, String> = emptyMap()): String {
                return extra[key].orEmpty().ifBlank { flat[key].orEmpty() }
            }
            val location = pick("location", obs).ifBlank { pick("placeHint", obs) }
            val objects = JsonLite.splitList(pick("objects", obs).ifBlank { flat["objects"] })
            val actions = JsonLite.splitList(pick("actions", obs).ifBlank { flat["actions"] })
            val claimsRaw = pick("observedClaims", obs).ifBlank { flat["observedClaims"] }
            return EnvironmentLook(
                sceneBrief = pick("sceneBrief", obs).ifBlank { pick("scene", obs) },
                change = pick("change", obs),
                salientText = pick("salientText", obs),
                objects = objects,
                actions = actions,
                actors = JsonLite.splitList(pick("actors", obs)),
                location = location,
                placeHint = pick("placeHint", obs).ifBlank { location },
                observedClaims = parseClaims(claimsRaw),
                floorCandidate = LandmarkSignage.normalizeFloor(
                    pick("floorCandidate", nav),
                ),
                floorEvidence = pick("floorEvidence", nav),
                confidence = pick("confidence").toFloatOrNull()
                    ?: pick("confidence", obs).toFloatOrNull()
                    ?: 0f,
                spaceType = pick("spaceType", nav),
                guideDir = pick("guideDir", nav),
                storeNames = JsonLite.splitList(pick("storeNames", nav)),
                blocked = pick("blocked", nav).equals("true", true),
                exits = JsonLite.splitList(pick("exits", nav)),
                eventProposal = proposalBody?.let { parseProposal(it) },
            )
        }
        val parsed = EnvironmentMerge.parseNote(raw)
        return EnvironmentLook(
            sceneBrief = parsed.scene,
            change = parsed.change,
            salientText = parsed.scene.take(80),
            location = "",
            confidence = if (parsed.scene.isNotBlank()) 0.4f else 0f,
        )
    }

    fun parseProposal(body: String): EventProposal {
        val json = JsonLite.known(body, proposalKeys)
        val summary = json["eventSummary"].orEmpty().ifBlank { json["summary"].orEmpty() }
        val level = json["evidenceLevel"].orEmpty().ifBlank {
            json["confidence"].orEmpty().takeIf { it in evidenceLevels }.orEmpty()
        }
        return EventProposal(
            operation = json["operation"].orEmpty().trim().lowercase(),
            summary = summary,
            hypothesis = json["hypothesis"].orEmpty(),
            observedClaims = parseClaims(json["observedClaims"]),
            inferredClaims = parseClaims(json["inferredClaims"]),
            actors = JsonLite.splitList(json["actors"]),
            objects = JsonLite.splitList(json["objects"]),
            actions = JsonLite.splitList(json["actions"]),
            location = json["location"].orEmpty(),
            relations = parseRelations(json["relations"]),
            evidenceLevel = level.ifBlank { EnvironmentObservation.STATUS_OBSERVED },
            confidence = json["confidence"]?.toFloatOrNull() ?: 0f,
        )
    }

    private fun parseClaims(raw: String?): List<ObservedClaim> {
        return JsonLite.objects(raw.orEmpty()).mapNotNull { obj ->
            val json = JsonLite.known(obj, listOf("id", "text", "evidence"))
            val text = json["text"].orEmpty()
            if (text.isBlank()) null else ObservedClaim(
                id = json["id"].orEmpty(),
                text = text,
                evidence = json["evidence"].orEmpty(),
            )
        }.take(8)
    }

    private fun parseRelations(raw: String?): List<EventRelation> {
        return JsonLite.objects(raw.orEmpty()).mapNotNull { obj ->
            val json = JsonLite.known(obj, listOf("relatedEventId", "type", "relation", "evidenceRefs", "evidenceLevel"))
            val type = json["type"].orEmpty().ifBlank { json["relation"].orEmpty() }
            if (type.isBlank()) null else EventRelation(
                relatedEventId = json["relatedEventId"].orEmpty(),
                type = type.trim().lowercase(),
                evidenceRefs = JsonLite.splitList(json["evidenceRefs"]),
                evidenceLevel = json["evidenceLevel"].orEmpty().ifBlank {
                    EnvironmentObservation.STATUS_INFERRED
                },
            )
        }.take(6)
    }

    private val evidenceLevels = setOf(
        EnvironmentObservation.STATUS_OBSERVED,
        EnvironmentObservation.STATUS_INFERRED,
        EnvironmentObservation.STATUS_HYPOTHESIS,
        EnvironmentObservation.STATUS_CONFIRMED,
    )
}
