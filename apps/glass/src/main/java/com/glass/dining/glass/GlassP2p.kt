package com.glass.dining.glass

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
import com.glass.dining.shared.link.P2pJoinPolicy
import com.glass.dining.shared.link.P2pOffer
import java.net.NetworkInterface
import com.glass.dining.shared.link.GlassLink
import com.glass.dining.shared.link.MediaWire

/**
 * 眼镜加入手机 Direct GO。唯一产品路径：原生 WifiP2pManager.discoverPeers/connect。
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
    private var joined: Boolean = false
    private var discoverDelayMs: Long = 2_000

    private val timeout = Runnable {
        if (active && !joined) {
            fail(
                if (!lastWifiEnabled) {
                    "眼镜 Wi-Fi 一直没打开。USB 调试连上后固件常会关 Wi-Fi，请保持数据线"
                } else {
                    "眼镜 30s 没加入手机 Direct 组 wifi=$lastWifiEnabled p2pState=$lastP2pState"
                },
            )
        }
    }

    private val retryDiscover = object : Runnable {
        override fun run() {
            if (!P2pJoinPolicy.mayDiscover(active, joined) || connecting) return
            startDiscovery()
            discoverDelayMs = (discoverDelayMs + 1_000).coerceAtMost(5_000)
            main.postDelayed(this, discoverDelayMs)
        }
    }

    private val connectWatch = Runnable {
        if (!active || joined || !connecting) return@Runnable
        connecting = false
        Log.w(TAG, "connect watch: no group yet, retry discover")
        startDiscovery()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                    lastWifiEnabled = state == WifiManager.WIFI_STATE_ENABLED
                    Log.i(TAG, "wifi state=$state")
                    if (active && lastWifiEnabled && P2pJoinPolicy.mayDiscover(active, joined)) {
                        startDiscovery()
                    }
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    lastP2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.i(TAG, "p2p state=$lastP2pState")
                    if (lastP2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED &&
                        P2pJoinPolicy.mayDiscover(active, joined) &&
                        !connecting
                    ) {
                        startDiscovery()
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (P2pJoinPolicy.mayDiscover(active, joined)) requestPeers()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (!active || joined) return
                    val mgr = manager ?: return
                    val ch = channel ?: return
                    mgr.requestConnectionInfo(ch) { info ->
                        val ip = ipv4OnP2p()
                        val ready = P2pJoinPolicy.readyIp(
                            groupFormed = info?.groupFormed == true,
                            isGroupOwner = info?.isGroupOwner == true,
                            ipv4 = ip,
                        )
                        if (ready != null) {
                            Log.i(TAG, "joined as p2p client ip=$ready go=${info?.groupOwnerAddress}")
                            succeed(ready)
                        } else if (info?.groupFormed == true && info.isGroupOwner != true) {
                            Log.i(TAG, "group formed but no 192.168.49.x yet ip=$ip")
                            main.postDelayed({ checkJoinedIp() }, 800)
                        }
                    }
                }
            }
        }
    }

    fun join(context: Context, next: P2pOffer, send: (String, String?) -> Unit) {
        if (P2pJoinPolicy.ignoreOffer(
                joined,
                offer?.ssid,
                next.ssid,
                offer?.attemptId.orEmpty(),
                next.attemptId,
            )
        ) {
            this.send = send
            this.offer = next
            Log.i(TAG, "join ignored, already on ${next.ssid} attempt=${next.attemptId}")
            return
        }
        if (P2pJoinPolicy.keepCurrentOffer(offer?.ssid, joined, next.ssid)) {
            this.send = send
            this.offer = next
            Log.i(TAG, "join same ssid, keep trying")
            GlassWifi.ensure()
            return
        }
        stopInternal(notify = false)
        this.app = context.applicationContext
        this.offer = next
        this.send = send
        active = true
        connecting = false
        joined = false
        discoverDelayMs = 2_000
        main.post {
            try {
                ensureManager(context.applicationContext)
                registerReceiver(context.applicationContext)
                GlassWifi.ensure()
                startDiscovery()
                main.removeCallbacks(timeout)
                main.removeCallbacks(retryDiscover)
                main.postDelayed(timeout, 30_000)
                main.postDelayed(retryDiscover, discoverDelayMs)
                hint("正在加入 ${next.ssid}")
                Log.i(TAG, "join ssid=${next.ssid} mac=${next.goMac} name=${next.goName} attempt=${next.attemptId}")
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
        joined = false
        main.removeCallbacks(timeout)
        main.removeCallbacks(retryDiscover)
        main.removeCallbacks(connectWatch)
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
            try {
                mgr.removeGroup(ch, null)
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
        send?.invoke(GlassLink.CMD_RTC_STAT, message)
    }

    private fun ensureManager(context: Context) {
        if (manager != null && channel != null) return
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        manager = mgr
        channel = mgr.initialize(context, Looper.getMainLooper()) {
            Log.w(TAG, "p2p channel disconnected")
        }
    }

    private fun startDiscovery() {
        if (!P2pJoinPolicy.mayDiscover(active, joined)) return
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
            if (P2pJoinPolicy.mayConnect(active, joined, connecting)) {
                val devices = list?.deviceList.orEmpty()
                Log.i(TAG, "peers n=${devices.size} ${devices.joinToString { "${it.deviceName}/${it.deviceAddress}" }}")
                val match = devices.firstOrNull { matches(it, target) }
                if (match != null) {
                    connect(match.deviceAddress)
                }
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
        if (!P2pJoinPolicy.mayConnect(active, joined, connecting)) return
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
                main.removeCallbacks(connectWatch)
                main.postDelayed(connectWatch, 8_000)
            }
            override fun onFailure(reason: Int) {
                connecting = false
                Log.w(TAG, "connect failed reason=$reason")
            }
        })
    }

    private fun checkJoinedIp() {
        if (!active || joined) return
        val ready = P2pJoinPolicy.readyIp(true, false, ipv4OnP2p())
        if (ready != null) succeed(ready)
    }

    private fun succeed(ip: String) {
        if (!active || joined) return
        joined = true
        connecting = false
        main.removeCallbacks(timeout)
        main.removeCallbacks(retryDiscover)
        main.removeCallbacks(connectWatch)
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            try {
                mgr.stopPeerDiscovery(ch, null)
            } catch (_: Exception) {
            }
        }
        val attempt = offer?.attemptId.orEmpty()
        send?.invoke(GlassLink.CMD_P2P_READY, MediaWire.p2pReadyJson(ip, attempt))
        active = false
        reclaimHud()
        Log.i(TAG, "p2p ready ip=$ip attempt=$attempt")
    }

    private fun fail(message: String) {
        if (!active && send == null) return
        val attempt = offer?.attemptId.orEmpty()
        val cb = send
        stopInternal(notify = true)
        cb?.invoke(GlassLink.CMD_P2P_FAIL, MediaWire.p2pFailJson(message, attempt))
    }

    private fun reclaimHud() {
        val context = app ?: return
        try {
            val intent = Intent(context, GlassActivity::class.java)
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
                .filter { it.name.startsWith("p2p") }
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress }
                .firstOrNull { it.startsWith(P2pJoinPolicy.DIRECT_PREFIX) }
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private const val TAG = "GlassP2p"
}
