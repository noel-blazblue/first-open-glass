package com.glass.dining.phone.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object PhoneGps {
    private val gpsThread = HandlerThread("phone-gps").apply { start() }

    @Volatile private var manager: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val point = GeoPoint(location.latitude, location.longitude)
            last = point
            onUpdate?.invoke(point)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    @Volatile var last: GeoPoint? = null
        private set
    @Volatile var onUpdate: ((GeoPoint) -> Unit)? = null

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun awaitFix(context: Context, timeoutMs: Long = 8_000): GeoPoint? {
        if (!hasPermission(context)) return null
        last?.let { return it }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        cached(lm)?.let {
            last = it
            return it
        }
        val holder = AtomicReference<GeoPoint?>()
        val latch = CountDownLatch(1)
        val once = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                holder.set(GeoPoint(location.latitude, location.longitude))
                latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, once, gpsThread.looper)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, once, gpsThread.looper)
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            try {
                lm.removeUpdates(once)
            } catch (_: Exception) {
            }
        }
        val point = holder.get() ?: cached(lm)
        last = point
        return point
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (!hasPermission(context)) return
        stop()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        cached(lm)?.let { last = it }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 3f, listener, gpsThread.looper)
            }
        } catch (_: Exception) {
        }
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1_000L, 5f, listener, gpsThread.looper)
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        val lm = manager ?: return
        try {
            lm.removeUpdates(listener)
        } catch (_: Exception) {
        }
        manager = null
    }

    @SuppressLint("MissingPermission")
    private fun cached(lm: LocationManager): GeoPoint? {
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
        val best = listOfNotNull(gps, net).maxByOrNull { it.time } ?: return null
        if (System.currentTimeMillis() - best.time > 60_000) return null
        return GeoPoint(best.latitude, best.longitude)
    }
}
