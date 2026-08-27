package com.glass.dining.shared.agent

data class EnvironmentEpisode(
    val id: String,
    val startedAt: Long,
    val settledAt: Long,
    val grid: IntArray,
    val ocrSamples: List<String>,
    val bestOcr: String,
    val sharpness: Double,
    val brightness: Float,
    val visualFromAnchor: Float,
    val bestFrameAt: Long,
)

data class ProbeFrame(
    val atMs: Long,
    val grid: IntArray,
    val ocr: String,
    val sharpness: Double = 0.0,
    val brightness: Float = 0f,
    val qualityOk: Boolean = true,
    val heading: Float = 0f,
)

enum class ViewPhase { Stable, Transition, Settling }

data class ProbeState(
    val phase: ViewPhase = ViewPhase.Stable,
    val anchor: ProbeFrame? = null,
    val window: List<ProbeFrame> = emptyList(),
    val transitionAt: Long = 0L,
    val episodeSeq: Int = 0,
)

sealed class ProbeSignal {
    data object Hold : ProbeSignal()
    data class TransitionStart(val atMs: Long, val visual: Float) : ProbeSignal()
    data class Settled(val episode: EnvironmentEpisode) : ProbeSignal()
}

object EpisodeQueuePolicy {
    const val CAPACITY = 5

    fun enqueue(current: List<EnvironmentEpisode>, next: EnvironmentEpisode): List<EnvironmentEpisode> {
        val merged = current + next
        return if (merged.size <= CAPACITY) merged else merged.takeLast(CAPACITY)
    }
}
