package com.glass.nav.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * USB 调试连上后固件常把眼镜 Wi-Fi 关掉。视频和 Direct 都需要它。
 * 由到餐 App 自己打开并盯着，被关了再打开。不要在电脑上手动 svc。
 */
object GlassWifi {
    private const val TAG = "GlassP2p"
    private const val ENABLE_RETRY_MS = 4_000L
    private val main = Handler(Looper.getMainLooper())
    private val worker = Handler(
        HandlerThread("glass-wifi").apply { start() }.looper,
    )
    private var app: Context? = null
    private var wifi: WifiManager? = null
    private var lock: WifiManager.WifiLock? = null
    private var onStatus: ((String) -> Unit)? = null
    @Volatile var enabled: Boolean = false
        private set
    @Volatile private var holding: Boolean = false
    private var receiverRegistered: Boolean = false
    private var lastHint: String = ""
    private var lastHintAt: Long = 0
    private var lastEnableAt: Long = 0

    private val keep = object : Runnable {
        override fun run() {
            if (!holding) return
            ensure()
            val state = wifiState()
            val gap = when (state) {
                WifiManager.WIFI_STATE_ENABLED -> 2_500L
                WifiManager.WIFI_STATE_ENABLING -> 600L
                else -> 1_200L
            }
            main.postDelayed(this, gap)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!holding) return
            if (intent?.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return
            val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
            enabled = state == WifiManager.WIFI_STATE_ENABLED
            Log.i(TAG, "wifi keep state=$state enabled=$enabled")
            when (state) {
                WifiManager.WIFI_STATE_ENABLED -> hint("眼镜 Wi-Fi 已开")
                WifiManager.WIFI_STATE_ENABLING -> hint("眼镜 Wi-Fi 正在打开")
                WifiManager.WIFI_STATE_DISABLED,
                WifiManager.WIFI_STATE_DISABLING,
                -> {
                    hint("眼镜 Wi-Fi 被关掉了，到餐页正在打开")
                    ensure()
                }
            }
        }
    }

    fun hold(context: Context, onStatus: ((String) -> Unit)? = null) {
        this.app = context.applicationContext
        this.wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (onStatus != null) {
            this.onStatus = onStatus
        }
        holding = true
        register(context.applicationContext)
        holdLock()
        ensure()
        main.removeCallbacks(keep)
        main.post(keep)
        Log.i(TAG, "wifi hold start enabled=$enabled state=${wifiState()}")
    }

    fun release() {
        holding = false
        main.removeCallbacks(keep)
        app?.let { unregister(it) }
        lock?.let { wlock ->
            try {
                if (wlock.isHeld) wlock.release()
            } catch (_: Exception) {
            }
        }
        lock = null
        onStatus = null
        wifi = null
        app = null
        Log.i(TAG, "wifi hold stop")
    }

    fun ensure() {
        worker.post { enableNow() }
    }

    @Suppress("DEPRECATION")
    private fun enableNow() {
        if (!holding) return
        val state = wifiState()
        if (state == WifiManager.WIFI_STATE_ENABLED || isRadioOn()) {
            enabled = true
            return
        }
        if (state == WifiManager.WIFI_STATE_ENABLING) {
            enabled = false
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastEnableAt < ENABLE_RETRY_MS) return
        lastEnableAt = now
        enabled = false
        hint("眼镜 Wi-Fi 关着，到餐页正在打开")
        var ok = false
        try {
            ok = wifi?.setWifiEnabled(true) == true
        } catch (error: Exception) {
            Log.w(TAG, "setWifiEnabled ${error.message}")
        }
        Log.i(TAG, "setWifiEnabled=$ok state=$state")
        if (ok || wifiState() == WifiManager.WIFI_STATE_ENABLING) return
        try {
            Settings.Global.putInt(app?.contentResolver, Settings.Global.WIFI_ON, 1)
        } catch (error: Exception) {
            Log.i(TAG, "settings wifi_on ${error.message}")
        }
        exec("svc", "wifi", "enable")
        exec("cmd", "wifi", "set-wifi-enabled", "enabled")
        exec("settings", "put", "global", "wifi_on", "1")
    }

    @Suppress("DEPRECATION")
    private fun isRadioOn(): Boolean {
        return try {
            wifi?.isWifiEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun wifiState(): Int {
        return try {
            wifi?.wifiState ?: WifiManager.WIFI_STATE_UNKNOWN
        } catch (_: Exception) {
            WifiManager.WIFI_STATE_UNKNOWN
        }
    }

    @Suppress("DEPRECATION")
    private fun holdLock() {
        val wm = wifi ?: return
        if (lock != null) return
        try {
            val next = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "glass-wifi-keep")
            next.setReferenceCounted(false)
            next.acquire()
            lock = next
        } catch (error: Exception) {
            Log.w(TAG, "wifi lock ${error.message}")
        }
    }

    private fun exec(vararg args: String) {
        try {
            val proc = Runtime.getRuntime().exec(args)
            val code = proc.waitFor()
            Log.i(TAG, "${args.joinToString(" ")} exit=$code")
        } catch (error: Exception) {
            Log.w(TAG, "${args.joinToString(" ")} ${error.message}")
        }
    }

    private fun hint(message: String) {
        val now = SystemClock.uptimeMillis()
        if (message == lastHint && now - lastHintAt < 8_000) return
        lastHint = message
        lastHintAt = now
        val cb = onStatus ?: return
        main.post { cb(message) }
    }

    private fun register(context: Context) {
        if (receiverRegistered) return
        context.registerReceiver(receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
        receiverRegistered = true
    }

    private fun unregister(context: Context) {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }
}
