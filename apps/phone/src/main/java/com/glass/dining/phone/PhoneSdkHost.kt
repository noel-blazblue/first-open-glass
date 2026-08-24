package com.glass.dining.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.glass.dining.shared.protocol.DiningIds
import com.rokid.security.phone.sdk.api.PSecuritySDK
import com.rokid.security.phone.sdk.api.msg.listener.IMessageListener
import com.rokid.security.phone.sdk.api.bluetooth.classic.listener.IClassicBTClientListener
import com.rokid.security.phone.sdk.base.data.EngineParam
import com.rokid.security.phone.sdk.base.data.EnvType
import com.rokid.security.phone.sdk.base.data.NetServiceType
import com.rokid.security.phone.sdk.base.data.UserAuthInfo
import java.nio.ByteBuffer

object PhoneSdkHost {
    private const val TAG = "DiningPhone"

    @Volatile
    var sdkReady: Boolean = false
        private set

    @Volatile
    var btConnected: Boolean = false
        private set

    var onTextMessage: ((String) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onDevices: ((List<BluetoothDevice>) -> Unit)? = null

    private val found = LinkedHashMap<String, BluetoothDevice>()

    private val btListener = object : IClassicBTClientListener {
        override fun onDeviceFound(device: BluetoothDevice) {
            val name = deviceName(device)
            if (!name.startsWith("Glass3_")) return
            found[device.address] = device
            onDevices?.invoke(found.values.toList())
        }

        override fun onScanFinished() {
            onStatus?.invoke("扫描结束，发现 ${found.size} 台眼镜")
        }

        override fun onConnect(success: Boolean) {
            btConnected = success
            onStatus?.invoke(if (success) "蓝牙已连接" else "蓝牙未连接")
        }

        override fun onConnectionRejected(reason: String, code: Int) {
            btConnected = false
            onStatus?.invoke("连接被拒绝：$reason")
        }
    }

    private val messageListener = object : IMessageListener {
        override fun onP2PTextMessage(msg: String, clientId: String) {
            onTextMessage?.invoke(msg)
        }

        override fun onClassicBTTextMessage(msg: String, clientId: String) {
            onTextMessage?.invoke(msg)
        }

        override fun onVideoH264Stream(buffer: ByteBuffer) {}
        override fun onAudioStream(buffer: ByteBuffer) {}
        override fun onNv21Data(data: ByteArray, width: Int, height: Int) {}
        override fun onClassicBTAudioStream(buffer: ByteArray) {}
        override fun onBTStreamDataReceived(tag: String, data: ByteArray, clientId: String) {}
    }

    fun init(context: Context) {
        val clientIds = arrayListOf(DiningIds.PHONE_CLIENT_ID, DiningIds.CLIENT_ID)
        val param = EngineParam(
            clientIds = clientIds,
            userAuthInfo = UserAuthInfo("", ""),
            envType = EnvType.PUBLIC,
            banServiceList = arrayListOf(NetServiceType.ALL),
        )
        PSecuritySDK.initSDK(param) { result ->
            sdkReady = result.isSuccess
            Log.d(TAG, "phone sdk initialized = ${PSecuritySDK.getMobileEngineService().isInit()}")
            onStatus?.invoke(if (sdkReady) "SDK 已初始化，可无眼镜演示" else "SDK 初始化失败，仍可本地演示")
            if (sdkReady) {
                PSecuritySDK.getMessageService()?.addMessageListener(messageListener)
                PSecuritySDK.getClassicBlueToothClientService()?.addClientListener(btListener)
            }
        }
    }

    fun scan() {
        found.clear()
        val bt = PSecuritySDK.getClassicBlueToothClientService()
        if (bt == null) {
            onStatus?.invoke("SDK 未就绪，本地 HUD 仍可用")
            return
        }
        onStatus?.invoke("正在扫描 Glass3…")
        bt.startScan(12_000L)
    }

    fun connect(device: BluetoothDevice) {
        PSecuritySDK.getClassicBlueToothClientService()?.connectToServer(device) { success ->
            btConnected = success == true
            onStatus?.invoke(if (btConnected) "已连接 ${deviceName(device)}" else "连接失败")
        }
    }

    fun sendToGlasses(json: String) {
        val msg = PSecuritySDK.getMessageService() ?: return
        msg.sendTextMessageByClassicBT(json, DiningIds.CLIENT_ID)
        msg.sendTextMessageByP2P(json, DiningIds.CLIENT_ID)
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String {
        return device.name ?: device.address
    }
}
