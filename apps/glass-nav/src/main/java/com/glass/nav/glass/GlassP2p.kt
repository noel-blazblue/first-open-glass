package com.glass.nav.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glass.dining.shared.nav.NavProtocol
import com.glass.dining.shared.nav.P2pOffer
import java.net.NetworkInterface

/**
 * 眼镜用 WifiP2pManager 当客户端加入手机 GO。
 * 不用 WifiNetworkSpecifier，避免弹出系统「找不到设备」页盖住 HUD。
 */
object GlassP2p {
    @Volatile var active: Boolean = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var send: ((String, String?) -> Unit)? = null
    private var offer: P2pOffer? = null
    private var connecting: Boolean = false
    private var receiverRegistered: Boolean = false
    private var lastP2pState: Int = -1
    private var lastWifiEnabled: Boolean = false
    private var wifiOffHinted: Boolean = false

    private val timeout = Runnable {
        if (active) {
            fail(
                if (!lastWifiEnabled) {
                    "眼镜 Wi-Fi 关着。USB 调试时常会关掉，请拔掉 USB 或在眼镜里打开 Wi-Fi"
                } else {
                    "眼镜没发现手机 Direct 组 wifi=$lastWifiEnabled p2pState=$lastP2pState"
                },
            )
        }
    }

    private val retryDiscover = object : Runnable {
        override fun run() {
            if (!active || connecting) return
            enableWifiAndDiscover()
            main.postDelayed(this, 1_500)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                    lastWifiEnabled = state == WifiManager.WIFI_STATE_ENABLED
                    Log.i(TAG, "wifi state=$state")
                    if (active && lastWifiEnabled && !connecting) {
                        startDiscovery()
                    }
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    lastP2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.i(TAG, "p2p state=$lastP2pState")
                    if (lastP2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED && active && !connecting) {
                        startDiscovery()
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (active) requestPeers()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (!active) return
                    val mgr = manager ?: return
                    val ch = channel ?: return
                    mgr.requestConnectionInfo(ch) { info ->
                        if (info?.groupFormed == true && !info.isGroupOwner) {
                            val ip = ipv4OnP2p().ifBlank { guessClientIp(offer?.goIp.orEmpty()) }
                            Log.i(TAG, "joined as client ip=$ip go=${info.groupOwnerAddress}")
                            succeed(ip)
                        }
                    }
                }
            }
        }
    }

    fun join(context: Context, next: P2pOffer, send: (String, String?) -> Unit) {
        stopInternal(notify = false)
        this.app = context.applicationContext
        this.offer = next
        this.send = send
        active = true
        connecting = false
        wifiOffHinted = false
        main.post {
            try {
                ensureManager(context.applicationContext)
                registerReceiver(context.applicationContext)
                enableWifiAndDiscover()
                main.removeCallbacks(timeout)
                main.removeCallbacks(retryDiscover)
                main.postDelayed(timeout, 20_000)
                main.postDelayed(retryDiscover, 1_500)
                Log.i(TAG, "join ssid=${next.ssid} mac=${next.goMac} name=${next.goName}")
            } catch (error: Exception) {
                Log.w(TAG, "join failed", error)
                fail("眼镜 Direct 启动失败: ${error.message}")
            }
        }
    }

    fun stop() {
        stopInternal(notify = false)
    }

    private fun stopInternal(notify: Boolean) {
        active = false
        connecting = false
        main.removeCallbacks(timeout)
        main.removeCallbacks(retryDiscover)
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            try {
                mgr.stopPeerDiscovery(ch, null)
            } catch (_: Exception) {
            }
            try {
                mgr.cancelConnect(ch, null)
            } catch (_: Exception) {
            }
        }
        app?.let { unregisterReceiver(it) }
        send = null
        offer = null
        if (notify) {
            reclaimHud()
        }
        Log.i(TAG, "p2p stopped")
    }

    private fun hint(message: String) {
        send?.invoke(NavProtocol.CMD_RTC_STAT, message)
    }

    private fun ensureManager(context: Context) {
        if (manager != null && channel != null) return
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        manager = mgr
        channel = mgr.initialize(context, Looper.getMainLooper()) {
            Log.w(TAG, "p2p channel disconnected")
        }
    }

    @Suppress("DEPRECATION")
    private fun enableWifiAndDiscover() {
        val context = app ?: return
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lastWifiEnabled = wifi.isWifiEnabled
        if (!wifi.isWifiEnabled) {
            val ok = wifi.setWifiEnabled(true)
            Log.i(TAG, "wifi was off, setWifiEnabled=$ok")
            if (!wifiOffHinted) {
                wifiOffHinted = true
                hint("眼镜 Wi-Fi 关着。USB 调试时常会关掉，请拔线或在眼镜里打开 Wi-Fi")
            }
            return
        }
        startDiscovery()
    }

    private fun startDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        if (lastP2pState == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
            Log.i(TAG, "skip discover, p2p still disabled")
            return
        }
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "discoverPeers ok")
                requestPeers()
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "discoverPeers failed reason=$reason p2pState=$lastP2pState wifi=$lastWifiEnabled")
            }
        })
    }

    private fun requestPeers() {
        val mgr = manager ?: return
        val ch = channel ?: return
        val target = offer ?: return
        mgr.requestPeers(ch) { list ->
            if (!active || connecting) return@requestPeers
            val devices = list?.deviceList.orEmpty()
            Log.i(TAG, "peers n=${devices.size} ${devices.joinToString { "${it.deviceName}/${it.deviceAddress}" }}")
            val match = devices.firstOrNull { matches(it, target) }
            if (match != null) {
                connect(match.deviceAddress)
            }
        }
    }

    private fun matches(device: WifiP2pDevice, target: P2pOffer): Boolean {
        val mac = device.deviceAddress.orEmpty()
        if (usableMac(target.goMac) && mac.equals(target.goMac, ignoreCase = true)) return true
        val name = device.deviceName.orEmpty()
        if (target.goName.isNotBlank() && name.contains(target.goName, ignoreCase = true)) return true
        if (target.goName.isNotBlank() && target.goName.contains(name) && name.length >= 4) return true
        if (target.ssid.isNotBlank() && name.contains(target.ssid, ignoreCase = true)) return true
        val token = target.ssid.substringAfter("DIRECT-").substringAfter("-")
        if (token.isNotBlank() && name.contains(token, ignoreCase = true)) return true
        return false
    }

    private fun connect(address: String) {
        if (connecting || !active) return
        val mgr = manager ?: return
        val ch = channel ?: return
        connecting = true
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        Log.i(TAG, "connect $address")
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "connect issued")
            }
            override fun onFailure(reason: Int) {
                connecting = false
                Log.w(TAG, "connect failed reason=$reason")
            }
        })
    }

    private fun succeed(ip: String) {
        if (!active) return
        main.removeCallbacks(timeout)
        main.removeCallbacks(retryDiscover)
        send?.invoke(NavProtocol.CMD_P2P_READY, NavProtocol.p2pReadyJson(ip))
        active = false
        connecting = false
        reclaimHud()
    }

    private fun fail(message: String) {
        if (!active && send == null) return
        val cb = send
        stopInternal(notify = true)
        cb?.invoke(NavProtocol.CMD_P2P_FAIL, message)
    }

    private fun reclaimHud() {
        val context = app ?: return
        try {
            val intent = Intent(context, NavActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "reclaim hud failed", error)
        }
    }

    private fun registerReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun unregisterReceiver(context: Context) {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }

    private fun usableMac(mac: String): Boolean {
        val parts = mac.lowercase().split(":")
        if (parts.size != 6) return false
        val bytes = parts.mapNotNull { it.toIntOrNull(16) }
        if (bytes.size != 6) return false
        if (bytes.drop(1).all { it == 0 }) return false
        return true
    }

    private fun ipv4OnP2p(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.name.startsWith("p2p") || it.name.startsWith("wlan") }
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress }
                .firstOrNull { it.startsWith("192.168.49.") }
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun guessClientIp(goIp: String): String {
        return if (goIp.startsWith("192.168.49.")) "192.168.49.2" else ""
    }

    private const val TAG = "GlassP2p"
}
