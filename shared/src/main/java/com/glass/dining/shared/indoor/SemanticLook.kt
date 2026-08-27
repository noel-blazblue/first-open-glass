package com.glass.dining.shared.indoor

import com.glass.dining.shared.agent.JsonLite
import com.glass.dining.shared.nav.LandmarkSignage

object SemanticLook {
    fun parse(raw: String): SemanticObservation {
        val root = JsonLite.objectBody(raw) ?: return SemanticObservation()
        val navigation = JsonLite.child(root, "navigation")
        val nav = if (navigation != null) {
            JsonLite.known(navigation, navKeys)
        } else {
            emptyMap()
        }
        val flat = JsonLite.known(root, navKeys + listOf("salientText", "objects"))
        fun pick(key: String): String = nav[key].orEmpty().ifBlank { flat[key].orEmpty() }
        val space = pick("spaceType")
        val stores = JsonLite.splitList(pick("storeNames")).ifEmpty {
            JsonLite.splitList(flat["objects"]).filter { it.length >= 2 }
        }
        return SemanticObservation(
            spaceType = space,
            exits = exits(pick("exits")),
            guideDir = pick("guideDir"),
            storeNames = stores,
            blocked = pick("blocked").equals("true", true),
            floorCandidate = LandmarkSignage.normalizeFloor(pick("floorCandidate")),
            evidence = flat["salientText"].orEmpty().ifBlank { pick("floorEvidence") },
            confidence = flat["confidence"]?.toFloatOrNull()
                ?: nav["confidence"]?.toFloatOrNull()
                ?: 0f,
        )
    }

    fun fromLook(look: com.glass.dining.shared.agent.EnvironmentLook): SemanticObservation {
        return SemanticObservation(
            spaceType = look.spaceType,
            exits = look.exits.map { ExitHint(dir = it, label = it) },
            guideDir = look.guideDir,
            storeNames = look.storeNames,
            blocked = look.blocked,
            floorCandidate = look.floorCandidate,
            evidence = look.salientText.ifBlank { look.floorEvidence },
            confidence = look.confidence,
        )
    }

    fun parseExits(raw: String): List<ExitHint> = exits(raw)

    private fun exits(raw: String?): List<ExitHint> {
        val text = raw.orEmpty()
        if (text.isBlank()) return emptyList()
        val objects = JsonLite.objects(text).map { chunk ->
            ExitHint(
                dir = JsonLite.child(chunk, "dir").orEmpty(),
                label = JsonLite.child(chunk, "label").orEmpty(),
            )
        }.filter { it.dir.isNotBlank() || it.label.isNotBlank() }
        if (objects.isNotEmpty()) return objects
        return JsonLite.splitList(text).map { ExitHint(dir = it, label = it) }
    }

    private val navKeys = listOf(
        "spaceType", "guideDir", "storeNames", "blocked",
        "floorCandidate", "floorEvidence", "confidence", "exits",
    )
}
