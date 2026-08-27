package com.glass.dining.phone.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object PhoneGps {
    private const val TAG = "GlassDiningPhone"
    private const val FRESH_MS = 30 * 60 * 1_000L
    private val gpsThread = HandlerThread("phone-gps").apply { start() }
    private val lock = Any()

    @Volatile private var startCount = 0
    @Volatile private var manager: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val point = accept(location)
            onUpdate?.invoke(point)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    @Volatile var last: GeoPoint? = null
        private set
    @Volatile var lastAccuracyM: Float = Float.MAX_VALUE
        private set
    @Volatile var onUpdate: ((GeoPoint) -> Unit)? = null

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun awaitFix(context: Context, timeoutMs: Long = 8_000): GeoPoint? {
        if (!hasPermission(context)) {
            Log.w(TAG, "gps await skipped: no permission")
            return null
        }
        last?.let {
            Log.i(TAG, "gps await hit memory acc=$lastAccuracyM")
            return it
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        cached(lm, FRESH_MS)?.let {
            last = it
            return it
        }
        val holder = AtomicReference<GeoPoint?>()
        val latch = CountDownLatch(1)
        val once = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                holder.set(accept(location))
                latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        Log.i(
            TAG,
            "gps await start timeout=${timeoutMs}ms gpsOn=${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)} " +
                "netOn=${lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)}",
        )
        try {
            requestUpdates(lm, 0L, 0f, once)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            Log.w(TAG, "gps await ${error.message}")
        } finally {
            try {
                lm.removeUpdates(once)
            } catch (_: Exception) {
            }
        }
        val fresh = holder.get() ?: cached(lm, FRESH_MS)
        if (fresh != null) {
            last = fresh
            Log.i(TAG, "gps await ok acc=$lastAccuracyM")
            return fresh
        }
        val stale = cached(lm, Long.MAX_VALUE)
        if (stale != null) {
            last = stale
            Log.w(TAG, "gps await using stale lastKnown acc=$lastAccuracyM")
            return stale
        }
        Log.w(TAG, "gps await miss")
        return null
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (!hasPermission(context)) {
            Log.w(TAG, "gps start skipped: no permission")
            return
        }
        synchronized(lock) {
            startCount++
            if (startCount > 1) {
                Log.i(TAG, "gps start count=$startCount keep listening last=${last != null}")
                return
            }
            listen(context)
        }
    }

    fun stop() {
        synchronized(lock) {
            if (startCount <= 0) return
            startCount--
            if (startCount > 0) {
                Log.i(TAG, "gps stop count=$startCount keep listening")
                return
            }
            unlisten()
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        cached(lm, FRESH_MS)?.let { last = it }
        requestUpdates(lm, 1_000L, 3f, listener)
        Log.i(TAG, "gps listening last=${last != null} acc=$lastAccuracyM")
    }

    private fun unlisten() {
        val lm = manager ?: return
        try {
            lm.removeUpdates(listener)
        } catch (_: Exception) {
        }
        manager = null
        Log.i(TAG, "gps stopped")
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(lm: LocationManager, minTime: Long, minDistance: Float, listener: LocationListener) {
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTime, minDistance, listener, gpsThread.looper)
            }
        } catch (_: Exception) {
        }
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTime, minDistance, listener, gpsThread.looper)
            }
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, minTime, minDistance, listener, gpsThread.looper)
                }
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun cached(lm: LocationManager, maxAgeMs: Long): GeoPoint? {
        val best = lastKnown(lm) ?: return null
        val ageMs = System.currentTimeMillis() - best.time
        if (ageMs > maxAgeMs) {
            Log.i(TAG, "gps lastKnown too old ageMs=$ageMs maxMs=$maxAgeMs acc=${best.accuracy}")
            return null
        }
        Log.i(TAG, "gps lastKnown ageMs=$ageMs acc=${best.accuracy}")
        return accept(best)
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? {
        val gps = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            null
        }
        val net = try {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            null
        }
        val fused = if (Build.VERSION.SDK_INT >= 31) {
            try {
                lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return listOfNotNull(gps, net, fused).maxByOrNull { it.time }
    }

    private fun accept(location: Location): GeoPoint {
        lastAccuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        val point = GeoPoint(location.latitude, location.longitude)
        last = point
        return point
    }
}
