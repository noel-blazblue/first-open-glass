package com.glass.dining.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glass.dining.shared.link.P2pJoinPolicy
import com.glass.dining.shared.nav.P2pOffer
import java.net.NetworkInterface

/**
 * 手机当 Wi-Fi Direct Group Owner。SSID/口令/MAC 经 CXR 给眼镜。
 */
object PhoneP2p {
    @Volatile var active: Boolean = false
        private set

    @Volatile var lastOffer: P2pOffer? = null
        private set

    @Volatile var attemptId: String = ""
        private set

    var onStatus: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var app: Context? = null
    private var waitingGroup: ((P2pOffer?) -> Unit)? = null
    private var receiverRegistered: Boolean = false
    private var createAttempts: Int = 0
    private var thisMac: String = ""
    private var thisName: String = ""
    @Volatile private var creating: Boolean = false

    private val giveUp = Runnable {
        fail("手机 20s 没建起 Direct 组。请打开手机 Wi-Fi 开关（不用连路由器）")
    }

    private val advertise = object : Runnable {
        override fun run() {
            if (!active) return
            advertiseGroup()
            main.postDelayed(this, 8_000)
        }
    }

    private val retryCreate = Runnable {
        if (!active || lastOffer != null) return@Runnable
        creating = false
        createOrReuseGroup()
    }

    private val pollGroup = object : Runnable {
        override fun run() {
            if (!active || lastOffer != null) return
            requestGroupOnce()
            main.postDelayed(this, 1_000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.i(TAG, "p2p state=$state")
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED && active) {
                        requestGroupOnce()
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = extraDevice(intent)
                    thisMac = device?.deviceAddress.orEmpty()
                    thisName = device?.deviceName.orEmpty()
                    Log.i(TAG, "this device mac=$thisMac name=$thisName")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = extraP2pInfo(intent)
                    val group = extraP2pGroup(intent)
                    val connected = extraNetworkInfo(intent)?.isConnected == true || info?.groupFormed == true
                    Log.i(
                        TAG,
                        "p2p conn formed=${info?.groupFormed} go=${info?.isGroupOwner} connected=$connected ssid=${group?.networkName}",
                    )
                    if (connected && info?.groupFormed == true && info.isGroupOwner) {
                        publishGroup(info, group)
                    }
                }
            }
        }
    }

    fun start(context: Context, attemptId: String, onGroup: (P2pOffer?) -> Unit) {
        val existing = lastOffer
        if (active && this.attemptId == attemptId && existing != null) {
            waitingGroup = null
            onGroup(existing)
            return
        }
        if (active && this.attemptId == attemptId) {
            waitingGroup = onGroup
            return
        }
        val appContext = context.applicationContext
        app = appContext
        waitingGroup = onGroup
        this.attemptId = attemptId
        active = true
        createAttempts = 0
        creating = false
        lastOffer = null
        main.removeCallbacks(giveUp)
        main.removeCallbacks(advertise)
        main.removeCallbacks(retryCreate)
        main.removeCallbacks(pollGroup)
        main.postDelayed(giveUp, 20_000)
        main.post(pollGroup)
        main.post {
            try {
                val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (!wifi.isWifiEnabled) {
                    fail("请打开手机 Wi-Fi 开关。Direct 用这块芯片，不用连家里路由器")
                    return@post
                }
                ensureManager(appContext)
                registerReceiver(appContext)
                createOrReuseGroup()
            } catch (error: Exception) {
                Log.w(TAG, "start failed", error)
                fail("Direct 启动失败: ${error.message}")
            }
        }
    }

    fun stop() {
        active = false
        waitingGroup = null
        creating = false
        lastOffer = null
        attemptId = ""
        main.removeCallbacks(giveUp)
        main.removeCallbacks(advertise)
        main.removeCallbacks(retryCreate)
        main.removeCallbacks(pollGroup)
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            try {
                mgr.removeGroup(ch, emptyListener("removeGroup"))
            } catch (error: Exception) {
                Log.w(TAG, "removeGroup failed", error)
            }
        }
        app?.let { unregisterReceiver(it) }
        Log.i(TAG, "p2p stopped")
    }

    private fun ensureManager(context: Context) {
        if (manager != null && channel != null) return
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        manager = mgr
        channel = mgr.initialize(context, Looper.getMainLooper()) {
            Log.w(TAG, "p2p channel disconnected")
        }
    }

    private fun createOrReuseGroup() {
        if (!P2pJoinPolicy.mayCreateGroup(
                sameAttempt = true,
                alreadyOffered = lastOffer != null,
                creating = creating,
            )
        ) {
            return
        }
        if (!active) return
        val mgr = manager ?: return fail("没有 WifiP2pManager")
        val ch = channel ?: return fail("P2P channel 为空")
        creating = true
        mgr.requestGroupInfo(ch) { existing ->
            if (!active) {
                creating = false
                return@requestGroupInfo
            }
            if (existing != null && existing.networkName.isNotBlank() && !existing.passphrase.isNullOrBlank()) {
                creating = false
                Log.i(TAG, "reuse group ssid=${existing.networkName}")
                mgr.requestConnectionInfo(ch) { info ->
                    publishGroup(info, existing)
                }
                return@requestGroupInfo
            }
            createAttempts += 1
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    creating = false
                    Log.i(TAG, "createGroup ok, wait broadcast")
                    status("手机已建 Direct 组，等 SSID")
                    main.postDelayed({ requestGroupOnce() }, 800)
                }
                override fun onFailure(reason: Int) {
                    creating = false
                    Log.w(TAG, "createGroup failed reason=$reason attempt=$createAttempts")
                    if (createAttempts < 5) {
                        status("Direct 组还在建，重试 $createAttempts")
                        main.postDelayed(retryCreate, 1_500)
                    } else {
                        fail("createGroup 失败 reason=$reason")
                    }
                }
            })
        }
    }

    private fun requestGroupOnce() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.requestConnectionInfo(ch) { info ->
            mgr.requestGroupInfo(ch) { group ->
                publishGroup(info, group)
            }
        }
    }

    private fun publishGroup(info: WifiP2pInfo?, group: WifiP2pGroup?) {
        if (!active) return
        val ssid = group?.networkName.orEmpty()
        val pass = group?.passphrase.orEmpty()
        if (ssid.isBlank() || pass.isBlank()) {
            Log.i(TAG, "group not ready yet ssidBlank=${ssid.isBlank()} passBlank=${pass.isBlank()}")
            return
        }
        val goIp = info?.groupOwnerAddress?.hostAddress.orEmpty()
        val ip = if (goIp.contains(':') || goIp.isBlank()) "192.168.49.1" else goIp
        val ifaceMac = ifaceHardwareMac(group?.`interface`)
        val mac = ifaceMac.ifBlank { group?.owner?.deviceAddress.orEmpty() }.ifBlank { thisMac }
        val name = group?.owner?.deviceName.orEmpty().ifBlank { thisName }
        Log.i(TAG, "group ready ssid=$ssid goIp=$ip mac=$mac iface=${group?.`interface`} name=$name")
        status("Direct 组 $ssid")
        val offer = P2pOffer(
            ssid = ssid,
            passphrase = pass,
            goIp = ip,
            goMac = mac,
            goName = name,
            attemptId = attemptId,
        )
        lastOffer = offer
        advertiseGroup()
        main.removeCallbacks(advertise)
        main.removeCallbacks(pollGroup)
        main.post(advertise)
        val cb = waitingGroup
        waitingGroup = null
        main.removeCallbacks(giveUp)
        cb?.invoke(offer)
    }

    private fun advertiseGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.discoverPeers(ch, emptyListener("discover as GO"))
    }

    private fun ifaceHardwareMac(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return try {
            val bytes = NetworkInterface.getByName(name)?.hardwareAddress ?: return ""
            bytes.joinToString(":") { b -> "%02x".format(b) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun fail(message: String) {
        main.removeCallbacks(giveUp)
        main.removeCallbacks(advertise)
        main.removeCallbacks(retryCreate)
        main.removeCallbacks(pollGroup)
        status(message)
        val cb = waitingGroup
        waitingGroup = null
        cb?.invoke(null)
    }

    private fun registerReceiver(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
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

    @Suppress("DEPRECATION")
    private fun extraNetworkInfo(intent: Intent): NetworkInfo? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
        } else {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
        }
    }

    private fun extraP2pInfo(intent: Intent): WifiP2pInfo? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
        }
    }

    private fun extraP2pGroup(intent: Intent): WifiP2pGroup? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
        }
    }

    private fun extraDevice(intent: Intent): android.net.wifi.p2p.WifiP2pDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, android.net.wifi.p2p.WifiP2pDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        }
    }

    private fun emptyListener(op: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            Log.i(TAG, "$op ok")
        }
        override fun onFailure(reason: Int) {
            Log.w(TAG, "$op failed reason=$reason")
        }
    }

    private fun status(line: String) {
        main.post { onStatus?.invoke(line) }
    }

    private const val TAG = "PhoneP2p"
}
