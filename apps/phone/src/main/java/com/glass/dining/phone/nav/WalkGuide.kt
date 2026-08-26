package com.glass.dining.phone.nav

import com.glass.dining.shared.nav.NavHint
import kotlin.math.roundToInt

object WalkGuide {
    const val ARRIVE_M = 20
    const val OFFROUTE_M = 40

    @Volatile private var route: WalkRoute? = null

    fun set(route: WalkRoute) {
        this.route = route
    }

    fun clear() {
        route = null
    }

    fun current(): WalkRoute? = route

    fun offRoute(pos: GeoPoint): Boolean {
        val r = route ?: return false
        return snap(r, pos).off > OFFROUTE_M
    }

    fun update(pos: GeoPoint): NavHint? {
        val r = route ?: return null
        if (Geo.haversineMeters(pos, r.destination) <= ARRIVE_M) {
            return arrive(r)
        }
        val snapped = snap(r, pos)
        val remaining = snapped.remaining.roundToInt().coerceAtLeast(0)
        if (remaining <= ARRIVE_M) return arrive(r)
        val step = r.steps.getOrElse(snapped.stepIndex) { r.steps.last() }
        val toTurn = snapped.toTurn.roundToInt().coerceAtLeast(1)
        return NavHint(
            turn = step.turn,
            meters = toTurn,
            text = shortInstruction(step.instruction),
            storeName = r.storeName,
            remaining = remaining,
        )
    }

    private fun arrive(route: WalkRoute): NavHint {
        return NavHint(
            turn = "arrive",
            meters = 0,
            text = "已到门口",
            storeName = route.storeName,
            remaining = 0,
        )
    }

    private fun shortInstruction(raw: String): String {
        val clean = raw.replace(Regex("<[^>]+>"), "").replace(" ", "")
        return clean.take(16).ifBlank { "跟着走" }
    }

    private data class Snap(
        val off: Double,
        val remaining: Double,
        val toTurn: Double,
        val stepIndex: Int,
    )

    private fun snap(route: WalkRoute, pos: GeoPoint): Snap {
        var bestOff = Double.MAX_VALUE
        var bestStep = 0
        var bestSeg = 0
        var bestT = 0.0
        route.steps.forEachIndexed { stepIndex, step ->
            val pts = step.points
            if (pts.size < 2) return@forEachIndexed
            for (i in 0 until pts.lastIndex) {
                val (snapPt, off) = Geo.nearestOnSegment(pos, pts[i], pts[i + 1])
                if (off < bestOff) {
                    bestOff = off
                    bestStep = stepIndex
                    bestSeg = i
                    val len = Geo.haversineMeters(pts[i], pts[i + 1]).coerceAtLeast(0.5)
                    bestT = Geo.haversineMeters(pts[i], snapPt) / len
                }
            }
        }
        val step = route.steps[bestStep]
        val pts = step.points
        var toTurn = 0.0
        if (pts.size >= 2) {
            val from = Geo.pointAlong(pts[bestSeg], pts[bestSeg + 1], bestT)
            toTurn += Geo.haversineMeters(from, pts[bestSeg + 1])
            for (i in (bestSeg + 1) until pts.lastIndex) {
                toTurn += Geo.haversineMeters(pts[i], pts[i + 1])
            }
        }
        var remaining = toTurn
        for (i in (bestStep + 1)..route.steps.lastIndex) {
            remaining += route.steps[i].distance.toDouble().coerceAtLeast(
                lengthOf(route.steps[i].points),
            )
        }
        return Snap(bestOff, remaining, toTurn, bestStep)
    }

    private fun lengthOf(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var sum = 0.0
        for (i in 0 until points.lastIndex) {
            sum += Geo.haversineMeters(points[i], points[i + 1])
        }
        return sum
    }
}
