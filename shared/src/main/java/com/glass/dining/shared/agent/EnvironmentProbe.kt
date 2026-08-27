package com.glass.dining.shared.agent

/**
 * 通用环境变化探针：画面结构为主，OCR 为辅。
 * 转场只开始收集，稳定且不同于上一视野后才提交 episode。
 */
object EnvironmentProbe {
    const val VLM_MIN_GAP_MS = 8_000L
    const val VLM_IDLE_GAP_MS = 30_000L
    const val MAX_VLM_PER_MIN = 6
    const val WINDOW = 3
    const val SETTLE_MS = 1_400L
    const val SETTLE_INTERNAL = 0.10f
    const val TRANSITION_VISUAL = 0.18f
    const val ANCHOR_VISUAL = 0.18f
    const val MAX_COLLECT = 8

    enum class Level { NONE, LOW, MEDIUM, HIGH }

    data class Score(
        val visual: Float,
        val ocr: Float,
        val heading: Float,
        val level: Level,
        val moving: Boolean,
    )

    data class Decision(
        val fire: Boolean,
        val reason: String,
    )

    fun ocrDigest(text: String): String {
        return tokens(text).sorted().joinToString(" ")
    }

    fun visualDiff(prev: IntArray, next: IntArray): Float {
        if (prev.isEmpty() || next.isEmpty() || prev.size != next.size) {
            return if (prev.isEmpty() && next.isEmpty()) 0f else 1f
        }
        var sum = 0
        for (i in prev.indices) {
            sum += kotlin.math.abs(prev[i] - next[i])
        }
        return (sum.toFloat() / (prev.size * 255f)).coerceIn(0f, 1f)
    }

    fun ocrChange(prevText: String, nextText: String): Float {
        val a = tokens(prevText)
        val b = tokens(nextText)
        if (a.isEmpty() && b.isEmpty()) return 0f
        if (a.isEmpty() || b.isEmpty()) return if (a.size + b.size <= 1) 0.15f else 0.55f
        val inter = a.intersect(b).size.toFloat()
        val union = a.union(b).size.toFloat().coerceAtLeast(1f)
        return (1f - inter / union).coerceIn(0f, 1f)
    }

    fun score(
        prevGrid: IntArray,
        nextGrid: IntArray,
        prevOcr: String,
        nextOcr: String,
        headingDelta: Float = 0f,
    ): Score {
        val visual = visualDiff(prevGrid, nextGrid)
        val ocr = ocrChange(prevOcr, nextOcr)
        val turn = kotlin.math.abs(headingDelta)
        var points = 0f
        when {
            visual >= 0.30f -> points += 0.80f
            visual >= 0.22f -> points += 0.65f
            visual >= 0.12f -> points += 0.40f
            visual >= 0.06f -> points += 0.15f
        }
        when {
            ocr >= 0.55f -> points += 0.35f
            ocr >= 0.30f -> points += 0.20f
            ocr >= 0.15f -> points += 0.08f
        }
        if (turn >= 35f) points += 0.18f
        val moving = visual >= 0.05f || turn >= 12f
        val level = when {
            visual >= 0.22f && (ocr >= 0.20f || visual >= 0.30f) -> Level.HIGH
            points >= 0.40f && visual >= 0.10f -> Level.MEDIUM
            points >= 0.40f && turn >= 35f && visual < 0.12f -> Level.MEDIUM
            points >= 0.15f -> Level.LOW
            else -> Level.NONE
        }
        return Score(visual, ocr, turn, level, moving)
    }

    fun stableChange(levels: List<Level>): Boolean {
        val window = levels.takeLast(WINDOW)
        if (window.size < 2) return false
        val strong = window.count { it == Level.MEDIUM || it == Level.HIGH }
        return strong >= 2
    }

    fun decide(
        levels: List<Level>,
        moving: Boolean,
        now: Long,
        lastVlmAt: Long,
        vlmTimes: List<Long>,
        vlmBusy: Boolean,
    ): Decision {
        val gate = canCallVlm(now, lastVlmAt, vlmTimes, vlmBusy)
        if (!gate.fire && gate.reason != "ready") return gate
        if (stableChange(levels)) return Decision(true, "change")
        if (moving && lastVlmAt > 0L && now - lastVlmAt >= VLM_IDLE_GAP_MS) {
            return Decision(true, "idle")
        }
        return Decision(false, "quiet")
    }

    fun canCallVlm(now: Long, lastVlmAt: Long, vlmTimes: List<Long>, vlmBusy: Boolean): Decision {
        if (vlmBusy) return Decision(false, "busy")
        val recent = vlmTimes.count { now - it < 60_000L }
        if (recent >= MAX_VLM_PER_MIN) return Decision(false, "rate")
        if (lastVlmAt > 0L && now - lastVlmAt < VLM_MIN_GAP_MS) return Decision(false, "cooldown")
        return Decision(true, "ready")
    }

    fun step(state: ProbeState, frame: ProbeFrame): Pair<ProbeState, ProbeSignal> {
        val anchor = state.anchor ?: return onFirstView(state, frame)
        val last = state.window.lastOrNull() ?: anchor
        val vsLast = score(last.grid, frame.grid, last.ocr, frame.ocr, frame.heading)
        val vsAnchor = visualDiff(anchor.grid, frame.grid)
        return when (state.phase) {
            ViewPhase.Stable -> onStable(state, frame, vsLast, vsAnchor)
            ViewPhase.Transition, ViewPhase.Settling -> onCollecting(state, frame, vsLast, vsAnchor)
        }
    }

    fun pickBest(window: List<ProbeFrame>): ProbeFrame {
        return window.maxBy { frame ->
            val sharp = frame.sharpness.coerceAtLeast(0.0)
            val text = frame.ocr.length.toDouble()
            val quality = if (frame.qualityOk) 40.0 else 0.0
            val exposure = 20.0 - kotlin.math.abs(frame.brightness - 110f)
            quality + sharp + text * 0.4 + exposure
        }
    }

    private fun onFirstView(state: ProbeState, frame: ProbeFrame): Pair<ProbeState, ProbeSignal> {
        if (!frame.qualityOk || frame.grid.isEmpty()) return state to ProbeSignal.Hold
        val window = (state.window + frame).takeLast(MAX_COLLECT)
        val tail = similarTail(window)
        val span = if (tail.size >= 2) tail.last().atMs - tail.first().atMs else 0L
        if (tail.size >= 2 && span >= SETTLE_MS) {
            val best = pickBest(tail)
            val episode = EnvironmentEpisode(
                id = "ep-${state.episodeSeq + 1}",
                startedAt = window.first().atMs,
                settledAt = best.atMs,
                grid = best.grid.copyOf(),
                ocrSamples = window.map { it.ocr }.filter { it.isNotBlank() }.takeLast(4),
                bestOcr = best.ocr,
                sharpness = best.sharpness,
                brightness = best.brightness,
                visualFromAnchor = 1f,
                bestFrameAt = best.atMs,
            )
            val next = state.copy(
                phase = ViewPhase.Stable,
                anchor = best,
                window = listOf(best),
                transitionAt = 0L,
                episodeSeq = state.episodeSeq + 1,
            )
            return next to ProbeSignal.Settled(episode)
        }
        return state.copy(
            phase = ViewPhase.Settling,
            window = window,
            transitionAt = window.first().atMs,
        ) to ProbeSignal.Hold
    }

    private fun onStable(
        state: ProbeState,
        frame: ProbeFrame,
        vsLast: Score,
        vsAnchor: Float,
    ): Pair<ProbeState, ProbeSignal> {
        val leaving = vsLast.visual >= TRANSITION_VISUAL ||
            vsAnchor >= 0.22f ||
            vsLast.level == Level.HIGH ||
            vsLast.heading >= 35f
        if (!leaving) {
            val nextWindow = (state.window + frame).takeLast(3)
            return state.copy(window = nextWindow) to ProbeSignal.Hold
        }
        val next = state.copy(
            phase = ViewPhase.Transition,
            window = listOf(frame),
            transitionAt = frame.atMs,
        )
        return next to ProbeSignal.TransitionStart(frame.atMs, vsLast.visual)
    }

    private fun onCollecting(
        state: ProbeState,
        frame: ProbeFrame,
        vsLast: Score,
        vsAnchor: Float,
    ): Pair<ProbeState, ProbeSignal> {
        val window = (state.window + frame).takeLast(MAX_COLLECT)
        val settled = settledView(window, state.anchor)
        if (settled != null) {
            val episode = toEpisode(state, settled, window)
            val next = state.copy(
                phase = ViewPhase.Stable,
                anchor = settled.best,
                window = listOf(settled.best),
                transitionAt = 0L,
                episodeSeq = state.episodeSeq + 1,
            )
            return next to ProbeSignal.Settled(episode)
        }
        val phase = if (vsLast.visual < SETTLE_INTERNAL && vsAnchor >= ANCHOR_VISUAL) {
            ViewPhase.Settling
        } else {
            ViewPhase.Transition
        }
        return state.copy(phase = phase, window = window) to ProbeSignal.Hold
    }

    private data class SettledView(val best: ProbeFrame, val spanMs: Long, val fromAnchor: Float)

    private fun settledView(window: List<ProbeFrame>, anchor: ProbeFrame?): SettledView? {
        if (window.size < 2 || anchor == null) return null
        val tail = similarTail(window)
        if (tail.size < 2) return null
        val span = tail.last().atMs - tail.first().atMs
        if (span < SETTLE_MS) return null
        val best = pickBest(tail)
        val fromAnchor = visualDiff(anchor.grid, best.grid)
        if (fromAnchor < ANCHOR_VISUAL && ocrChange(anchor.ocr, best.ocr) < 0.25f) return null
        return SettledView(best, span, fromAnchor)
    }

    private fun similarTail(window: List<ProbeFrame>): List<ProbeFrame> {
        if (window.isEmpty()) return emptyList()
        var start = window.lastIndex
        while (start > 0) {
            val a = window[start - 1]
            val b = window[start]
            if (visualDiff(a.grid, b.grid) > SETTLE_INTERNAL) break
            start -= 1
        }
        return window.subList(start, window.size)
    }

    private fun toEpisode(state: ProbeState, settled: SettledView, window: List<ProbeFrame>): EnvironmentEpisode {
        val samples = window.map { it.ocr }.filter { it.isNotBlank() }.takeLast(4)
        return EnvironmentEpisode(
            id = "ep-${state.episodeSeq + 1}",
            startedAt = state.transitionAt,
            settledAt = settled.best.atMs,
            grid = settled.best.grid.copyOf(),
            ocrSamples = samples,
            bestOcr = settled.best.ocr,
            sharpness = settled.best.sharpness,
            brightness = settled.best.brightness,
            visualFromAnchor = settled.fromAnchor,
            bestFrameAt = settled.best.atMs,
        )
    }

    private fun tokens(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[\\s，。！？、；：,.;:]+"), " ")
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
    }
}
