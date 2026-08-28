package com.glass.dining.phone

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.glass.dining.shared.link.GlassForegroundPolicy
import com.glass.dining.shared.nav.NavProtocol
import com.rokid.cxr.link.callbacks.IGlassAppCbk

/**
 * 眼镜 CustomApp 的查询/拉起/停止。前台判定在 [GlassForegroundPolicy]。
 * 手机到餐在前台且 CXR 已连时，不在镜片前台就 appStart。
 */
class GlassAppSession(
    private val main: Handler,
    private val nowMs: () -> Long = { SystemClock.uptimeMillis() },
) {
    private val policy = GlassForegroundPolicy()
    @Volatile var phoneWantsGlass: Boolean = false

    val opened: Boolean
        get() = policy.opened
    val ready: Boolean
        get() = policy.ready

    var onStatus: ((String) -> Unit)? = null
    var onOpened: (() -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onLost: (() -> Unit)? = null

    private var lastOpenedNotifyAt: Long = 0L
    private val expireGrace = Runnable { onGraceExpired() }

    val appCbk = object : IGlassAppCbk {
        override fun onQueryAppResult(installed: Boolean) {
            Log.i(TAG, "onQueryAppResult installed=$installed")
            onStatus?.invoke(
                if (installed) {
                    "眼镜到餐页已安装"
                } else {
                    "眼镜还没装到餐 APK，请用电脑 adb 安装 apps/glass（installGlassAdb）"
                },
            )
            if (installed && CxrLinkHost.linkReady()) {
                startGlassApp()
            }
        }

        override fun onInstallAppResult(success: Boolean) {
            Log.w(TAG, "onInstallAppResult=$success ignored; glass APK is ADB-only")
            onStatus?.invoke("眼镜 APK 只能 ADB 安装，已忽略 CXR 安装回调")
        }

        override fun onOpenAppResult(success: Boolean) {
            Log.i(TAG, "onOpenAppResult=$success")
            onStatus?.invoke(if (success) "镜片闲置卡已打开" else "启动眼镜到餐页失败")
            if (success) {
                policy.markOpened()
                main.postDelayed({
                    CxrLinkHost.sendHelloAndWifi()
                    notifyOpened()
                }, 400)
            }
        }

        override fun onStopAppResult(success: Boolean) {
            leaveForeground("stop")
            onStatus?.invoke("眼镜到餐页已停止")
        }

        override fun onUnInstallAppResult(success: Boolean) {
            onStatus?.invoke(if (success) "已卸载眼镜 APK" else "卸载失败")
        }

        override fun onGlassAppResume(resumed: Boolean) {
            Log.i(TAG, "onGlassAppResume=$resumed")
            if (resumed) {
                policy.markOpened()
                CxrLinkHost.sendHelloAndWifi()
                main.post { notifyOpened() }
            } else {
                leaveForeground("resume-false")
            }
        }
    }

    fun reset() {
        main.removeCallbacks(expireGrace)
        policy.reset()
        lastOpenedNotifyAt = 0L
    }

    fun ensure(reason: String) {
        val now = nowMs()
        val close = CxrLinkHost.closeGlassWhenReady
        val cxr = CxrLinkHost.linkReady()
        val start = policy.shouldStart(phoneWantsGlass, cxr, close, now)
        Log.i(
            TAG,
            "ensure reason=$reason start=$start opened=${policy.opened} ready=${policy.ready} " +
                "grace=${policy.inGrace} want=$phoneWantsGlass cxr=$cxr close=$close",
        )
        if (close) {
            CxrLinkHost.closeGlassWhenReady = false
            phoneWantsGlass = false
            stopGlassApp()
            return
        }
        if (!start) return
        policy.noteStart(now)
        queryInstalled()
    }

    fun onCmdReady() {
        policy.markReady()
        notifyOpened()
        onReady?.invoke()
    }

    fun onAiAssistStop() {
        leaveForeground("ai-stop")
    }

    fun onWearing(wearing: Boolean) {
        if (wearing && !policy.opened) {
            ensure("wearing")
        }
    }

    fun stopGlassApp() {
        val link = CxrLinkHost.sharedLink ?: return
        CxrLinkHost.sendRtc(NavProtocol.CMD_P2P_STOP)
        CxrLinkHost.sendRtc(NavProtocol.CMD_RTC_STOP)
        try {
            link.appStop(appCbk)
            Log.i(TAG, "appStop issued")
        } catch (error: Exception) {
            Log.w(TAG, "appStop failed", error)
        }
    }

    private fun queryInstalled() {
        val link = CxrLinkHost.sharedLink ?: return
        if (!CxrLinkHost.linkReady()) return
        try {
            link.appIsInstalled(appCbk)
        } catch (error: Exception) {
            Log.w(TAG, "appIsInstalled failed", error)
            onStatus?.invoke("查询眼镜 APK 失败: ${error.message}")
        }
    }

    private fun startGlassApp() {
        val link = CxrLinkHost.sharedLink ?: return
        if (!CxrLinkHost.linkReady()) return
        if (CxrLinkHost.closeGlassWhenReady) {
            CxrLinkHost.closeGlassWhenReady = false
            phoneWantsGlass = false
            stopGlassApp()
            return
        }
        try {
            link.appStart(NavProtocol.glassEntry(), appCbk)
            Log.i(TAG, "appStart ${NavProtocol.glassEntry()}")
        } catch (error: Exception) {
            Log.w(TAG, "appStart failed", error)
            onStatus?.invoke("启动眼镜页失败: ${error.message}")
        }
    }

    private fun leaveForeground(reason: String) {
        policy.markLeaving(nowMs())
        Log.i(TAG, "glass left foreground reason=$reason")
        main.removeCallbacks(expireGrace)
        main.postDelayed(expireGrace, GlassForegroundPolicy.GRACE_MS)
    }

    private fun onGraceExpired() {
        val expired = policy.expireGrace(nowMs())
        Log.i(TAG, "glass grace expired=$expired opened=${policy.opened}")
        if (!expired || policy.opened) return
        onLost?.invoke()
        ensure("grace")
    }

    private fun notifyOpened() {
        val now = nowMs()
        if (policy.opened && now - lastOpenedNotifyAt < 1_500) return
        lastOpenedNotifyAt = now
        policy.markOpened()
        onOpened?.invoke()
    }

    companion object {
        private const val TAG = "GlassDiningPhone"
    }
}
