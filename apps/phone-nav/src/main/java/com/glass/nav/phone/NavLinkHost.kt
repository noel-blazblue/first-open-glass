package com.glass.nav.phone

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.glass.dining.shared.nav.NavHint
import com.glass.dining.shared.nav.NavProtocol
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo

object NavLinkHost {
    const val TAG = "GlassNavPhone"

    var sharedLink: CXRLink? = null
        private set

    @Volatile var cxrConnected: Boolean = false
        private set
    @Volatile var glassBtConnected: Boolean = false
        private set
    @Volatile var glassAppOpen: Boolean = false
        private set

    var onStatus: ((String) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onFrame: ((ByteArray) -> Unit)? = null
    var onPose: ((com.glass.dining.shared.nav.NavPose) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var connecting: Boolean = false

    private val linkCbk = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            cxrConnected = connected
            Log.i(TAG, "onCXRLConnected=$connected")
            publishLink()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            glassBtConnected = connected
            Log.i(TAG, "onGlassBtConnected=$connected")
            publishLink()
        }

        override fun onGlassDeviceInfo(deviceInfo: GlassInfo) {
            Log.i(TAG, "glass battery=${deviceInfo.batteryLevel} ver=${deviceInfo.systemVersion}")
        }

        override fun onGlassWearingStatus(wearing: Boolean) {
            Log.i(TAG, "wearing=$wearing")
        }

        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
        override fun onGlassAiInterrupt(interrupted: Boolean) = Unit
    }

    private val appCbk = object : IGlassAppCbk {
        override fun onQueryAppResult(installed: Boolean) {
            Log.i(TAG, "onQueryAppResult installed=$installed")
            onStatus?.invoke(if (installed) "眼镜 APK 已安装" else "眼镜还没装室内导航 APK，请先 adb 安装")
            if (installed && linkReady()) {
                startGlassApp()
            }
        }

        override fun onInstallAppResult(success: Boolean) {
            onStatus?.invoke(if (success) "眼镜 APK 安装成功" else "眼镜 APK 安装失败")
        }

        override fun onOpenAppResult(success: Boolean) {
            glassAppOpen = success
            Log.i(TAG, "onOpenAppResult=$success")
            onStatus?.invoke(if (success) "眼镜导航页已打开" else "启动眼镜导航页失败")
            if (success) {
                main.postDelayed({ sendCmd(NavProtocol.CMD_HELLO) }, 400)
            }
        }

        override fun onStopAppResult(success: Boolean) {
            glassAppOpen = false
            onStatus?.invoke("眼镜导航页已停止")
        }

        override fun onUnInstallAppResult(success: Boolean) {
            onStatus?.invoke(if (success) "已卸载眼镜 APK" else "卸载失败")
        }

        override fun onGlassAppResume(resumed: Boolean) {
            Log.i(TAG, "onGlassAppResume=$resumed")
            glassAppOpen = resumed
            if (resumed) {
                onStatus?.invoke("眼镜导航页回到前台")
                main.post { onReady?.invoke() }
            }
        }
    }

    private val cmdCbk = object : ICustomCmdCbk {
        override fun onCustomCmdResult(key: String?, payload: ByteArray?) {
            if (payload == null || payload.isEmpty()) return
            val caps = try {
                Caps.fromBytes(payload)
            } catch (error: Exception) {
                Log.w(TAG, "parse caps failed key=$key", error)
                return
            }
            val cmd = readString(caps, 0) ?: return
            Log.i(TAG, "up key=$key cmd=$cmd size=${caps.size()}")
            when (cmd) {
                NavProtocol.CMD_READY -> {
                    glassAppOpen = true
                    main.post { onReady?.invoke() }
                }
                NavProtocol.CMD_FRAME -> {
                    val jpeg = readBinary(caps, 1)
                    if (jpeg != null && jpeg.isNotEmpty()) {
                        main.post { onFrame?.invoke(jpeg) }
                    } else {
                        sendCmd(NavProtocol.CMD_FRAME_OK)
                    }
                }
                NavProtocol.CMD_POSE -> {
                    val parsed = NavProtocol.parsePose(readString(caps, 1))
                    if (parsed != null) main.post { onPose?.invoke(parsed) }
                }
                NavProtocol.CMD_ERROR -> {
                    val msg = readString(caps, 1).orEmpty()
                    main.post { onStatus?.invoke("眼镜: $msg") }
                }
            }
        }
    }

    fun connect(app: Application, token: String): String {
        if (token.isBlank()) return "token 为空，无法连接"
        if (cxrConnected && glassBtConnected && sharedLink != null) {
            main.post { queryAndStart() }
            return "CXR 已连接"
        }
        if (connecting && sharedLink != null) {
            return "正在连接眼镜…"
        }
        connecting = true
        cxrConnected = false
        glassBtConnected = false
        glassAppOpen = false
        try {
            sharedLink?.disconnect()
        } catch (_: Exception) {
        }
        val link = CXRLink(app).apply {
            val configured = configCXRSession(
                CxrDefs.CXRSession(
                    CxrDefs.CXRSessionType.CUSTOMAPP,
                    NavProtocol.GLASS_PACKAGE,
                ),
            )
            if (!configured) {
                Log.w(TAG, "configCXRSession CUSTOMAPP returned false")
            }
            setCXRLinkCbk(linkCbk)
            setCXRCustomCmdCbk(cmdCbk)
        }
        sharedLink = link
        (app as? NavPhoneApp)?.sharedLink = link
        val started = try {
            link.connect(token)
        } catch (error: Exception) {
            connecting = false
            Log.w(TAG, "connect failed", error)
            return "connect 异常: ${error.message}"
        }
        Log.i(TAG, "CUSTOMAPP connect started=$started pkg=${NavProtocol.GLASS_PACKAGE}")
        return if (started) {
            "正在通过乐奇连接眼镜（CustomApp）…"
        } else {
            connecting = false
            "connect 请求未发出，请确认乐奇已连上眼镜"
        }
    }

    fun queryAndStart() {
        val link = sharedLink ?: return
        if (!linkReady()) {
            onStatus?.invoke("还没连上眼镜蓝牙")
            return
        }
        try {
            link.appIsInstalled(appCbk)
        } catch (error: Exception) {
            Log.w(TAG, "appIsInstalled failed", error)
            onStatus?.invoke("查询眼镜 APK 失败: ${error.message}")
        }
    }

    fun startGlassApp() {
        val link = sharedLink ?: return
        if (!linkReady()) return
        try {
            link.appStart(NavProtocol.glassEntry(), appCbk)
            Log.i(TAG, "appStart ${NavProtocol.glassEntry()}")
        } catch (error: Exception) {
            Log.w(TAG, "appStart failed", error)
            onStatus?.invoke("启动眼镜页失败: ${error.message}")
        }
    }

    fun stopGlassApp() {
        val link = sharedLink ?: return
        sendCmd(NavProtocol.CMD_NAV_STOP)
        try {
            link.appStop(appCbk)
        } catch (error: Exception) {
            Log.w(TAG, "appStop failed", error)
        }
    }

    fun startIndoorNav(hint: NavHint) {
        sendCmd(NavProtocol.CMD_NAV_START, NavProtocol.hintJson(hint))
    }

    fun updateHint(hint: NavHint) {
        sendCmd(NavProtocol.CMD_NAV_UPDATE, NavProtocol.hintJson(hint))
    }

    fun ackFrame() {
        sendCmd(NavProtocol.CMD_FRAME_OK)
    }

    fun calibrate() {
        sendCmd(NavProtocol.CMD_CALIBRATE)
    }

    fun linkReady(): Boolean = cxrConnected && glassBtConnected && sharedLink != null

    private fun sendCmd(cmd: String, json: String? = null) {
        val link = sharedLink ?: return
        if (!linkReady()) {
            Log.i(TAG, "sendCmd skipped, link not ready cmd=$cmd")
            return
        }
        val caps = Caps()
        caps.write(cmd)
        if (!json.isNullOrBlank()) {
            caps.write(json)
        }
        val code = try {
            link.sendCustomCmd(NavProtocol.CHANNEL_DOWN, caps)
        } catch (error: Exception) {
            Log.w(TAG, "sendCustomCmd failed cmd=$cmd", error)
            null
        }
        Log.i(TAG, "sendCustomCmd cmd=$cmd code=$code")
    }

    private fun publishLink() {
        val status = when {
            cxrConnected && glassBtConnected -> {
                connecting = false
                if (!glassAppOpen) {
                    main.post { queryAndStart() }
                }
                "CXR 与眼镜蓝牙已就绪"
            }
            cxrConnected -> "CXR 已连，等待眼镜蓝牙"
            glassBtConnected -> "眼镜蓝牙已连，等待 CXR"
            else -> "CXR 未连接"
        }
        Log.i(TAG, status)
        onStatus?.invoke(status)
    }

    private fun readString(caps: Caps, index: Int): String? {
        if (caps.size() <= index) return null
        return try {
            caps.at(index).getString()
        } catch (_: Exception) {
            null
        }
    }

    private fun readFloat(caps: Caps, index: Int): Float? {
        if (caps.size() <= index) return null
        return try {
            caps.at(index).getFloat()
        } catch (_: Exception) {
            null
        }
    }

    private fun readBinary(caps: Caps, index: Int): ByteArray? {
        if (caps.size() <= index) return null
        return try {
            val bin = caps.at(index).getBinary() ?: return null
            val end = bin.offset + bin.length
            if (bin.data == null || bin.offset < 0 || end > bin.data.size) return null
            bin.data.copyOfRange(bin.offset, end)
        } catch (error: Exception) {
            Log.w(TAG, "read binary failed", error)
            null
        }
    }
}
