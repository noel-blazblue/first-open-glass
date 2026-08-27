package com.glass.dining.store

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.glass.dining.shared.catalog.StoreGeo
import com.glass.dining.shared.model.Deal
import com.glass.dining.shared.model.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class StoreForm(
    val id: String = "",
    val name: String = "",
    val shortName: String = "",
    val category: String = "",
    val address: String = "",
    val phone: String = "",
    val hours: String = "",
    val avgPrice: String = "",
    val rating: String = "",
    val waitMinutes: String = "",
    val waitTables: String = "",
    val signatures: String = "",
    val tags: String = "",
    val deals: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val openNow: Boolean = true,
    val hasPrivateRoom: Boolean = false,
)

data class StoreUi(
    val stores: List<Store> = emptyList(),
    val editing: StoreForm? = null,
    val status: String = "在店门口录入，保存后到餐 App 会立刻读到。",
)

class StoreViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(StoreUi())
    val ui: StateFlow<StoreUi> = _ui

    init {
        reload()
    }

    fun reload() {
        val stores = CatalogFiles.read(getApplication())
        _ui.update {
            it.copy(
                stores = stores,
                status = if (stores.isEmpty()) "还没有门店。点「新增」在店门口录入。" else "已录入 ${stores.size} 家。改完保存即可，不用重装到餐。",
            )
        }
    }

    fun startNew() {
        _ui.update { it.copy(editing = StoreForm(id = UUID.randomUUID().toString())) }
    }

    fun edit(store: Store) {
        _ui.update { it.copy(editing = store.toForm()) }
    }

    fun cancelEdit() {
        _ui.update { it.copy(editing = null) }
    }

    fun updateForm(block: (StoreForm) -> StoreForm) {
        val current = _ui.value.editing ?: return
        _ui.update { it.copy(editing = block(current)) }
    }

    fun deleteCurrent() {
        val form = _ui.value.editing ?: return
        val next = _ui.value.stores.filterNot { it.id == form.id }
        CatalogFiles.write(getApplication(), next)
        _ui.update { it.copy(stores = next, editing = null, status = "已删除，到餐下次打开会更新。") }
    }

    fun save() {
        val form = _ui.value.editing ?: return
        val store = form.toStore()
        if (store.name.isBlank()) {
            _ui.update { it.copy(status = "先填店名") }
            return
        }
        if (!StoreGeo.hasCoords(store)) {
            _ui.update { it.copy(status = "还没有经纬度。请在店门口点「记录当前位置」。") }
            return
        }
        val others = _ui.value.stores.filterNot { it.id == store.id }
        val next = others + store
        CatalogFiles.write(getApplication(), next)
        _ui.update {
            it.copy(
                stores = next,
                editing = null,
                status = "已保存 ${store.shortName}（${store.lat}, ${store.lng}）。不用重装到餐。",
            )
        }
    }

    fun captureGps() {
        val app = getApplication<Application>()
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _ui.update { it.copy(status = "请先允许定位权限") }
            return
        }
        val point = awaitFix(app)
        if (point == null) {
            _ui.update { it.copy(status = "还没有 GPS，请到开阔处再试") }
            return
        }
        updateForm { it.copy(lat = point.first, lng = point.second) }
        _ui.update { it.copy(status = "已记录 ${point.first}, ${point.second}") }
    }

    @SuppressLint("MissingPermission")
    private fun awaitFix(app: Application): Pair<Double, Double>? {
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        val cached = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (cached != null && System.currentTimeMillis() - cached.time < 60_000) {
            return cached.latitude to cached.longitude
        }
        val holder = arrayOfNulls<Location>(1)
        val latch = CountDownLatch(1)
        val thread = HandlerThread("store-gps").apply { start() }
        val once = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                holder[0] = location
                latch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        return try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, once, thread.looper)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, once, thread.looper)
            }
            latch.await(8, TimeUnit.SECONDS)
            val loc = holder[0] ?: cached
            loc?.let { it.latitude to it.longitude }
        } catch (_: Exception) {
            cached?.let { it.latitude to it.longitude }
        } finally {
            try {
                lm.removeUpdates(once)
            } catch (_: Exception) {
            }
            thread.quitSafely()
        }
    }
}

private fun Store.toForm(): StoreForm {
    return StoreForm(
        id = id,
        name = name,
        shortName = shortName,
        category = category,
        address = address,
        phone = phone,
        hours = hours,
        avgPrice = if (avgPrice > 0) avgPrice.toString() else "",
        rating = if (rating > 0) rating.toString() else "",
        waitMinutes = if (waitMinutes > 0) waitMinutes.toString() else "",
        waitTables = if (waitTables > 0) waitTables.toString() else "",
        signatures = signatures.joinToString("、"),
        tags = tags.joinToString("、"),
        deals = deals.joinToString("；") { "${it.title},${it.price},${it.original}" },
        lat = lat,
        lng = lng,
        openNow = openNow,
        hasPrivateRoom = hasPrivateRoom,
    )
}

private fun StoreForm.toStore(): Store {
    val names = name.trim()
    return Store(
        id = id.ifBlank { UUID.randomUUID().toString() },
        name = names,
        shortName = shortName.trim().ifBlank { names },
        category = category.trim(),
        rating = rating.toDoubleOrNull() ?: 0.0,
        reviewCount = 0,
        avgPrice = avgPrice.toIntOrNull() ?: 0,
        distanceMeters = 0,
        openNow = openNow,
        hours = hours.trim(),
        phone = phone.trim(),
        address = address.trim(),
        waitTables = waitTables.toIntOrNull() ?: 0,
        waitMinutes = waitMinutes.toIntOrNull() ?: 0,
        tags = splitList(tags),
        deals = parseDeals(deals),
        signatures = splitList(signatures),
        suitable = emptyList(),
        hasPrivateRoom = hasPrivateRoom,
        answers = emptyMap(),
        lat = lat,
        lng = lng,
    )
}

private fun splitList(raw: String): List<String> {
    return raw.split("、", ",", "，", ";", "；")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun parseDeals(raw: String): List<Deal> {
    return raw.split("；", ";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split(",", "，").map { it.trim() }
            val title = parts.getOrNull(0).orEmpty()
            if (title.isBlank()) null else Deal(
                title = title,
                price = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                original = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            )
        }
}
