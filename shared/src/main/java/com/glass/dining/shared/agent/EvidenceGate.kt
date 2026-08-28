package com.glass.dining.shared.agent

/**
 * 状态落库前的证据门：只校验不变量，不猜用户意图。
 * Observation 不能直接变成已绑定门店；用户点选、搜索命中、目录命中可以绑定。
 */
object EvidenceGate {
    enum class BindSource {
        USER_SELECT,
        SEARCH_RESULT,
        CATALOG_MATCH,
        OBSERVATION,
    }

    data class BindProposal(
        val source: BindSource,
        val placeId: String = "",
        val name: String = "",
        val observation: SceneObservation? = null,
        val uniqueCatalogName: String = "",
        val vlmName: String = "",
        val vlmConfidence: Float = 0f,
        val userConfirmed: Boolean = false,
        val ocrContainsName: Boolean = false,
    )

    data class Decision(
        val accept: Boolean,
        val reason: String,
    )

    fun decideBind(proposal: BindProposal): Decision {
        if (proposal.placeId.isBlank() && proposal.name.isBlank() && proposal.uniqueCatalogName.isBlank()) {
            return Decision(false, "没有可绑定的地点")
        }
        return when (proposal.source) {
            BindSource.USER_SELECT,
            BindSource.SEARCH_RESULT,
            BindSource.CATALOG_MATCH,
            -> Decision(true, proposal.source.name.lowercase())
            BindSource.OBSERVATION -> decideObservation(proposal)
        }
    }

    private fun decideObservation(proposal: BindProposal): Decision {
        val observation = proposal.observation
            ?: return Decision(false, "observation_is_not_fact")
        val ok = ScenePerception.canPromoteStore(
            observation = observation,
            uniqueCatalogName = proposal.uniqueCatalogName,
            vlmName = proposal.vlmName,
            vlmConfidence = proposal.vlmConfidence,
            userConfirmed = proposal.userConfirmed,
            ocrContainsName = proposal.ocrContainsName,
        )
        return if (ok) {
            Decision(true, "observation_promoted")
        } else {
            Decision(false, "observation_is_not_fact")
        }
    }
}
