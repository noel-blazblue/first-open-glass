package com.glass.dining.phone.nav

import com.glass.dining.shared.model.Store
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lng: Double)

object Geo {
    private const val EARTH_M = 6_371_000.0

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun offset(origin: GeoPoint, meters: Double, bearingDeg: Double): GeoPoint {
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(origin.lat)
        val lng1 = Math.toRadians(origin.lng)
        val dr = meters / EARTH_M
        val lat2 = asin(sin(lat1) * cos(dr) + cos(lat1) * sin(dr) * cos(br))
        val lng2 = lng1 + atan2(
            sin(br) * sin(dr) * cos(lat1),
            cos(dr) - sin(lat1) * sin(lat2),
        )
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lng2))
    }

    fun pointAlong(a: GeoPoint, b: GeoPoint, t: Double): GeoPoint {
        val u = t.coerceIn(0.0, 1.0)
        return GeoPoint(a.lat + (b.lat - a.lat) * u, a.lng + (b.lng - a.lng) * u)
    }

    fun nearestOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): Pair<GeoPoint, Double> {
        val ab = haversineMeters(a, b)
        if (ab < 0.5) return a to haversineMeters(p, a)
        val ap = haversineMeters(p, a)
        val bp = haversineMeters(p, b)
        val t = ((ap * ap - bp * bp + ab * ab) / (2 * ab * ab)).coerceIn(0.0, 1.0)
        val snap = pointAlong(a, b, t)
        return snap to haversineMeters(p, snap)
    }
}

object StorePins {
    fun destOf(store: Store): GeoPoint? {
        if (!com.glass.dining.shared.catalog.StoreGeo.hasCoords(store)) return null
        return GeoPoint(store.lat, store.lng)
    }
}
