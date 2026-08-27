package com.glass.dining.phone.agent

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.glass.dining.phone.PhoneAi
import com.glass.dining.phone.vision.StreamFrame
import com.glass.dining.shared.agent.EnvironmentEpisode
import com.glass.dining.shared.agent.EnvironmentEventTracker
import com.glass.dining.shared.agent.EnvironmentLook
import com.glass.dining.shared.agent.EnvironmentMerge
import com.glass.dining.shared.agent.EnvironmentObservation
import com.glass.dining.shared.agent.EnvironmentProbe
import com.glass.dining.shared.agent.EnvironmentState
import com.glass.dining.shared.agent.EnvironmentStore
import com.glass.dining.shared.agent.EpisodeQueuePolicy
import com.glass.dining.shared.agent.ProbeFrame
import com.glass.dining.shared.agent.ProbeSignal
import com.glass.dining.shared.agent.ProbeState

/**
 * 转场后等待新视野稳定，选最佳帧入队。VLM 忙时不覆盖已确认 episode。
 */
object EnvironmentWatcher {
    private const val TAG = "GlassDiningPhone"
    private val worker = Handler(HandlerThread("env-watch").apply { start() }.looper)
    private val lock = Any()
    private val vlmTimes = ArrayList<Long>()
    private val queue = ArrayDeque<Queued>()
    private val recentJpeg = ArrayDeque<Pair<Long, ByteArray>>()
    private var probe = ProbeState()
    private var lastVlmAt: Long = 0
    private var lastYaw: Float = 0f
    private var yawAtIngest: Float = 0f
    private var haveYaw: Boolean = false
    @Volatile private var vlmBusy: Boolean = false

    fun onHeading(yaw: Float) {
        lastYaw = yaw
        haveYaw = true
    }

    fun ingest(frame: StreamFrame, headingDelta: Float = 0f) {
        val now = SystemClock.elapsedRealtime()
        val turn = headingDelta.takeIf { it != 0f } ?: headingSinceLast()
        val sample = ProbeFrame(
            atMs = now,
            grid = frame.extract.quality.visualGrid,
            ocr = frame.extract.ocrText,
            sharpness = frame.extract.quality.sharpness,
            brightness = frame.extract.quality.brightness,
            qualityOk = frame.extract.quality.ok,
            heading = turn,
        )
        rememberJpeg(now, frame.jpeg)
        val (next, signal) = EnvironmentProbe.step(probe, sample)
        probe = next
        when (signal) {
            is ProbeSignal.Hold -> Unit
            is ProbeSignal.TransitionStart -> {
                Log.i(TAG, "env transition_start visual=${"%.2f".format(signal.visual)}")
            }
            is ProbeSignal.Settled -> {
                val jpeg = jpegNear(signal.episode.bestFrameAt, frame.jpeg)
                Log.i(
                    TAG,
                    "env settled id=${signal.episode.id} visual=${"%.2f".format(signal.episode.visualFromAnchor)} " +
                        "sharp=${"%.0f".format(signal.episode.sharpness)} ocr=${signal.episode.bestOcr.length}",
                )
                enqueue(Queued(signal.episode, jpeg, urgent = false))
            }
        }
        drain()
    }

    fun requestFresh(frame: StreamFrame?) {
        if (frame == null || frame.jpeg.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val episode = EnvironmentEpisode(
            id = "ask-$now",
            startedAt = now,
            settledAt = now,
            grid = frame.extract.quality.visualGrid,
            ocrSamples = listOf(frame.extract.ocrText).filter { it.isNotBlank() },
            bestOcr = frame.extract.ocrText,
            sharpness = frame.extract.quality.sharpness,
            brightness = frame.extract.quality.brightness,
            visualFromAnchor = 0f,
            bestFrameAt = now,
        )
        enqueue(Queued(episode, frame.jpeg.copyOf(), urgent = true))
        drain()
    }

    fun shouldRefresh(): Boolean {
        synchronized(lock) { return queue.isNotEmpty() }
    }

    private fun headingSinceLast(): Float {
        if (!haveYaw) return 0f
        var delta = lastYaw - yawAtIngest
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        yawAtIngest = lastYaw
        return delta
    }

    private fun rememberJpeg(atMs: Long, jpeg: ByteArray) {
        if (jpeg.isEmpty()) return
        recentJpeg.addLast(atMs to jpeg.copyOf())
        while (recentJpeg.size > 8) recentJpeg.removeFirst()
    }

    private fun jpegNear(atMs: Long, fallback: ByteArray): ByteArray {
        val match = recentJpeg.minByOrNull { kotlin.math.abs(it.first - atMs) }
        return match?.second?.copyOf() ?: fallback.copyOf()
    }

    private fun enqueue(item: Queued) {
        synchronized(lock) {
            val existing = queue.toList()
            if (item.urgent) {
                queue.addFirst(item)
                while (queue.size > EpisodeQueuePolicy.CAPACITY) queue.removeLast()
            } else {
                val episodes = EpisodeQueuePolicy.enqueue(existing.map { it.episode }, item.episode)
                val byId = (existing + item).associateBy { it.episode.id }
                queue.clear()
                episodes.forEach { ep -> byId[ep.id]?.let { queue.addLast(it) } }
            }
            Log.i(TAG, "env episode_queued id=${item.episode.id} size=${queue.size} urgent=${item.urgent}")
        }
    }

    private fun drain() {
        if (!PhoneAi.ready) return
        val next: Queued
        synchronized(lock) {
            if (vlmBusy || queue.isEmpty()) return
            val gate = EnvironmentProbe.canCallVlm(
                now = SystemClock.elapsedRealtime(),
                lastVlmAt = lastVlmAt,
                vlmTimes = vlmTimes.toList(),
                vlmBusy = false,
            )
            val urgent = queue.first().urgent
            if (!gate.fire && !urgent) return
            next = queue.removeFirst()
            vlmBusy = true
            val now = SystemClock.elapsedRealtime()
            lastVlmAt = now
            vlmTimes.add(now)
            vlmTimes.removeAll { now - it >= 60_000L }
        }
        startVlm(next)
    }

    private fun startVlm(item: Queued) {
        val previous = EnvironmentStore.snapshot().currentBrief
        Log.i(TAG, "env vlm start id=${item.episode.id} ocr=${item.episode.bestOcr.length} brief=${previous.length}")
        worker.post {
            try {
                val raw = PhoneAi.envLook(item.jpeg, item.episode.bestOcr, previous)
                val look = EnvironmentMerge.parseLook(raw.orEmpty())
                if (look.sceneBrief.isBlank()) {
                    Log.w(TAG, "env vlm empty id=${item.episode.id}, keep previous")
                    return@post
                }
                val now = SystemClock.elapsedRealtime()
                val prev = EnvironmentStore.snapshot()
                var merged = EnvironmentMerge.fromLook(look, prev, now)
                merged = EnvironmentMerge.fromSignage(item.episode.ocrSamples, merged, now)
                merged = EnvironmentEventTracker.ingest(look, merged, now)
                EnvironmentStore.replace(merged)
                logCommitted(item.episode, prev, merged, look)
            } finally {
                synchronized(lock) { vlmBusy = false }
                drain()
            }
        }
    }

    private fun logCommitted(
        episode: EnvironmentEpisode,
        prev: EnvironmentState,
        next: EnvironmentState,
        look: EnvironmentLook,
    ) {
        Log.i(TAG, "env episode_committed id=${episode.id} scene=${next.currentBrief.take(36)}")
        val oldFloor = prev.recentObservations.filter { it.kind == EnvironmentObservation.KIND_FLOOR }
        val newFloor = next.recentObservations.filter { it.kind == EnvironmentObservation.KIND_FLOOR }
        if (newFloor != oldFloor) {
            val fact = newFloor.maxByOrNull { it.observedAt }
            Log.i(TAG, "env fact_promoted kind=floor_sign value=${fact?.value} conf=${fact?.confidence}")
        }
        if (look.floorCandidate.isNotBlank()) {
            Log.i(TAG, "env look floor=${look.floorCandidate} evidence=${look.floorEvidence.take(24)}")
        }
    }

    private data class Queued(
        val episode: EnvironmentEpisode,
        val jpeg: ByteArray,
        val urgent: Boolean,
    )
}
