package com.glass.dining.shared.hud

import com.glass.dining.shared.model.MatchResult
import com.glass.dining.shared.model.QaResult
import com.glass.dining.shared.model.Store
import com.glass.dining.shared.model.StoreSummary

data class HudUiState(
    val idleHint: String = "看向店招，说「开始看店」",
    val store: Store? = null,
    val candidates: List<StoreSummary> = emptyList(),
    val confidence: Float? = null,
    val tts: String? = null,
    val answer: String? = null,
    val listening: Boolean = false,
    val recognizing: Boolean = false,
    val statusLine: String = "",
) {
    companion object {
        fun fromMatch(result: MatchResult, statusLine: String = ""): HudUiState {
            return HudUiState(
                store = result.store,
                candidates = result.candidates.map {
                    StoreSummary(it.id, it.shortName, it.category, it.distanceMeters)
                },
                confidence = result.confidence,
                tts = result.tts,
                statusLine = statusLine.ifBlank { result.tts },
            )
        }

        fun withAnswer(current: HudUiState, qa: QaResult): HudUiState {
            return current.copy(
                answer = qa.answer,
                tts = qa.tts,
                statusLine = qa.answer,
            )
        }
    }
}
