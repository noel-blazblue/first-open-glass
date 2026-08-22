---
prev:
  text: 眼镜端 API 总览
  link: /terminal-sdk/api-reference/Glass3  SDK(眼镜端) API文档.md
next:
  text: 平台 OpenAPI
  link: /openapi/ApiKey.md
---

# 手机端 SDK API 接口说明

本文整理手机端 `phone.sdk` 对外能力，适用于 Android 手机 App 与 Rokid Glass3 眼镜配套开发。建议先阅读 [快速开始](/terminal-sdk/getting-started/快速开始.md) 完成 SDK 依赖集成，再按能力域查找对应接口。

手机端 SDK 的统一入口是 `PSecuritySDK`。大多数能力服务需要先完成 `PSecuritySDK.initSDK(...)` 初始化，初始化成功后再通过 `PSecuritySDK.getXxxService()` 获取具体服务对象。

## API 分类总览

| 分类 | 适合查什么 |
| --- | --- |
| [接入与初始化](#phone-sdk-init) | SDK 初始化、引擎状态、销毁 SDK |
| [PSecuritySDK 服务入口](#psecuritysdk-service-entry) | 通过 `PSecuritySDK` 获取手机端各能力服务 |
| [Wi-Fi P2P 与 AR Mix](#phone-p2p) | P2P 连接、设备发现、P2P 消息回调、P2P 保持连接、AR 叠加录制 |
| [蓝牙与指环](#phone-bluetooth) | 经典蓝牙、BLE、蓝牙指环连接与消息 |
| [消息与文件传输](#phone-message-file) | 发送消息、收发文件、上传文件、下载文件、APK 文件发送 |
| [设备与媒体流](#phone-device-media) | 设备信息、眼镜音视频流、媒体接收器 |
| [语音、AI 与翻译](#phone-voice-ai) | AI Chat、翻译语言设置 |
| [识别、采集与跟踪](#phone-recognition) | 手机端 provider、识别结果、采集回调、跟踪监听 |
| [OTA、日志与辅助能力](#phone-ota-provision) | OTA 下载、日志、通知、业务配置 |
| [参数取值与数据结构](#phone-parameter-models) | 初始化参数、下载/上传状态、翻译语言、眼镜 App 配置 |

<a id="phone-sdk-init"></a>

## 1 接入与初始化

在手机端应用模块的 `build.gradle` 中添加 SDK 依赖：

```groovy
dependencies {
    implementation ('com.rokid.security:phone.sdk:2.2.0-E') {
        exclude group: "org.slf4j"
    }
}
```

完整 Maven 仓库配置请参考 [快速开始 4.2 手机端 SDK](/terminal-sdk/getting-started/快速开始.md#quickstart-phone-sdk-dependency)。

初始化时需要传入 `EngineParam`。其中 `clientIds` 用于标识手机端和眼镜端应用，手机端与眼镜端需要保持一致；`userAuthInfo` 中的 API Key 鉴权信息请向商务或对接方申请。

```kotlin
val clientIds = arrayListOf("SecurityPhone", "GlassSample")
val userAuthInfo = UserAuthInfo(appId = "", secret = "")

val param = EngineParam(
    clientIds = clientIds,
    userAuthInfo = userAuthInfo,
    envType = EnvType.PUBLIC,
    banServiceList = arrayListOf(NetServiceType.ALL)
)

PSecuritySDK.initSDK(param) { result ->
    if (result.isSuccess) {
        // SDK 初始化成功
    } else {
        // SDK 初始化失败
    }
}
```

<a id="imobileengine"></a>

**IMobileEngine：手机端引擎服务**

| 方法 | 说明 |
| --- | --- |
| `IMobileEngine.initSDK(params, onResult)` | 初始化手机端 SDK。 |
| `IMobileEngine.setUserInfo(user)` | 设置用户信息，供眼镜端显示或同步。 |
| `IMobileEngine.isInit()` | 判断手机端 SDK 是否已经初始化。 |
| `IMobileEngine.destroy()` | 销毁手机端 SDK。 |

<a id="psecuritysdk-service-entry"></a>

## 2 PSecuritySDK 服务入口

`PSecuritySDK` 是手机端 SDK 的统一入口。除 `initSDK()`、`getMobileEngineService()` 和 `getOtaEngineService()` 外，多数服务入口会在 SDK 未初始化时返回空值，调用前建议先完成初始化并做好空值处理。

| 方法 | 返回服务与说明 |
| --- | --- |
| `PSecuritySDK.initSDK(params, onResult)` | `IMobileEngine`<br>初始化手机端 SDK。 |
| `PSecuritySDK.destroySDK()` | 销毁 SDK，引擎内部会释放资源。 |
| `PSecuritySDK.getMobileEngineService()` | `IMobileEngine`<br>获取手机端引擎服务。 |
| `PSecuritySDK.getWifiP2PClientService()` | `IWifiP2PClientOperate`<br>获取 Wi-Fi P2P 客户端服务。 |
| `PSecuritySDK.getClassicBlueToothClientService()` | `IClassicBluetoothClient`<br>获取经典蓝牙客户端服务。 |
| `PSecuritySDK.getMessageService()` | `IMessage`<br>获取消息和文件传输服务。 |
| `PSecuritySDK.getBluetoothRingService()` | `IBluetoothRing`<br>获取蓝牙指环服务。 |
| `PSecuritySDK.getAbsDeviceInfoService()` | `IDevice`<br>获取设备信息与音视频流服务。 |
| `PSecuritySDK.getAbsNotificationService()` | `INotification`<br>获取通知服务。 |
| `PSecuritySDK.getFileSystemService()` | `IFileSystem`<br>获取文件系统上传服务。 |
| `PSecuritySDK.getAbsAIChatService()` | `IAIChat`<br>获取 AI Chat 服务。 |
| `PSecuritySDK.getAbsTranslateService()` | `ITranslate`<br>获取翻译服务。 |
| `PSecuritySDK.getOtaEngineService()` | `IOta`<br>获取 OTA 服务。 |
| `PSecuritySDK.getTrackService()` | `ITrack`<br>获取人脸、车牌检测跟踪服务。 |
| `PSecuritySDK.getOnlineRecService()` | `IOnlineRec`<br>获取在线识别服务。 |
| `PSecuritySDK.getOfflineRecService()` | `IOfflineRec`<br>获取离线识别服务。 |
| `PSecuritySDK.getCollectService()` | `ICollect`<br>获取采集服务。 |
| `PSecuritySDK.getAbsIdentificationService()` | 获取身份识别相关服务。 |
| `PSecuritySDK.getAbsAuthService()` | 获取鉴权相关服务。 |
| `PSecuritySDK.getAbsSdkLogService()` | `ILog`<br>获取 SDK 日志上传服务。 |

<a id="phone-p2p"></a>

## 3 Wi-Fi P2P 与 AR Mix

<a id="iwifip2pclientoperate"></a>

**IWifiP2PClientOperate：Wi-Fi P2P 客户端服务**

| 方法 | 说明 |
| --- | --- |
| `IWifiP2PClientOperate.initialize(onResult)` | 初始化 P2P 通道。 |
| `IWifiP2PClientOperate.connectDevice(device, onResult)` | 连接指定 P2P 设备。 |
| `IWifiP2PClientOperate.sendConnectP2pRequest(action)` | 发送自动连接 P2P 请求，依赖蓝牙连接。 |
| `IWifiP2PClientOperate.disconnect()` | 断开 P2P 连接。 |
| `IWifiP2PClientOperate.startDiscoverPeers(onResult)` | 开始发现 P2P 设备。 |
| `IWifiP2PClientOperate.stopPeerDiscovery(onResult)` | 停止发现 P2P 设备。 |
| `IWifiP2PClientOperate.requestConnectionInfo(action)` | 获取当前 `WifiP2pInfo`。 |
| `IWifiP2PClientOperate.getGroupInfo(action)` | 获取当前 P2P 群组信息。 |
| `IWifiP2PClientOperate.isConnect(action)` | 查询 P2P 通讯是否已连接。 |
| `IWifiP2PClientOperate.setAutoDecodeH264ToNv21(enable)` | 设置接收 H264 视频流时是否自动解码为 NV21。 |
| `IWifiP2PClientOperate.isAutoDecodeH264ToNv21Enabled()` | 查询 H264 自动解码为 NV21 的开关状态。 |
| `IWifiP2PClientOperate.addWifiP2PClientListener(listener)` | 注册 P2P 客户端监听器。 |
| `IWifiP2PClientOperate.removeWifiP2PClientListener(listener)` | 移除 P2P 客户端监听器。 |
| `IWifiP2PClientOperate.getIP2PConnectControl()` | 获取 P2P 保持连接控制器。 |
| `IWifiP2PClientOperate.getIARMixControl()` | 获取 AR Mix 叠加录制控制器。 |

<a id="iwifip2pclientlistener"></a>

**IWifiP2PClientListener：Wi-Fi P2P 连接监听**

| 方法 | 说明 |
| --- | --- |
| `IWifiP2PClientListener.onWifiP2pEnabled(enabled)` | P2P 通道可用状态变化。 |
| `IWifiP2PClientListener.onConnectionInfoAvailable(wifiP2pInfo)` | 返回当前 P2P 连接信息。 |
| `IWifiP2PClientListener.onSelfDeviceAvailable(device)` | 返回当前手机端 P2P 设备信息。 |
| `IWifiP2PClientListener.onPeersAvailable(devices)` | 返回发现到的 P2P 设备列表。 |

<a id="iwifip2pmessagelistener"></a>

**IWifiP2PMessageListener：Wi-Fi P2P 消息与流回调**

| 方法 | 说明 |
| --- | --- |
| `IWifiP2PMessageListener.onP2PTextMessage(msg)` | 接收 P2P 文本消息。 |
| `IWifiP2PMessageListener.onVideoH264Stream(buffer)` | 接收 H264 视频流。 |
| `IWifiP2PMessageListener.onAudioStream(buffer)` | 接收音频流。 |
| `IWifiP2PMessageListener.onNv21Data(data, width, height)` | 接收 NV21 视频帧数据。 |

<a id="ip2pconnectcontrol"></a>

**IP2PConnectControl：P2P 保持连接控制**

| 方法 | 说明 |
| --- | --- |
| `IP2PConnectControl.setKeepP2PConnect(enable, action)` | 设置是否保持 P2P 常连接。调用前需要先连接蓝牙。 |
| `IP2PConnectControl.getKeepP2PConnectState(action, onFail)` | 获取 P2P 保持连接状态。 |

<a id="iarmixcontrol"></a>

**IARMixControl：AR Mix 叠加录制控制**

| 方法 | 说明 |
| --- | --- |
| `IARMixControl.setARMixEnabled(enable, action)` | 设置眼镜端录像是否启用 camera + screen 叠加画面。调用前需要先连接蓝牙。 |
| `IARMixControl.getARMixState(action, onFail)` | 获取 AR Mix 叠加录制开关状态。 |

<a id="phone-bluetooth"></a>

## 4 蓝牙与指环

<a id="iclassicbluetoothclient"></a>

**IClassicBluetoothClient：经典蓝牙客户端服务**

| 方法 | 说明 |
| --- | --- |
| `IClassicBluetoothClient.startScan(timeoutMillis)` | 开始经典蓝牙扫描。 |
| `IClassicBluetoothClient.stopScan()` | 停止经典蓝牙扫描。 |
| `IClassicBluetoothClient.connectToServer(device, action)` | 连接指定蓝牙设备。 |
| `IClassicBluetoothClient.disconnect()` | 断开蓝牙连接。 |
| `IClassicBluetoothClient.addClientListener(listener)` | 注册经典蓝牙连接监听器。 |
| `IClassicBluetoothClient.removeClientListener(listener)` | 移除经典蓝牙连接监听器。 |
| `IClassicBluetoothClient.isConnected()` | 判断蓝牙是否已连接。 |
| `IClassicBluetoothClient.isMessageChanelConnect()` | 判断蓝牙文本消息通道是否连接。 |
| `IClassicBluetoothClient.isAudioChanelConnect()` | 判断蓝牙音频通道是否连接。 |
| `IClassicBluetoothClient.isFileChanelConnect()` | 判断蓝牙文件通道是否连接。 |
| `IClassicBluetoothClient.isStreamChanelConnect()` | 判断蓝牙二进制流通道是否连接。 |

<a id="iclassicbtclientlistener"></a>

**IClassicBTClientListener：经典蓝牙连接监听**

| 方法 | 说明 |
| --- | --- |
| `IClassicBTClientListener.onDeviceFound(device)` | 扫描发现蓝牙设备。 |
| `IClassicBTClientListener.onScanFinished()` | 蓝牙扫描结束。 |
| `IClassicBTClientListener.onConnect(success)` | 蓝牙连接状态回调。 |
| `IClassicBTClientListener.onConnectionRejected(reason, code)` | 蓝牙连接被拒绝。 |

<a id="iclassicbtmessagelistener"></a>

**IClassicBTMessageListener：经典蓝牙消息回调**

| 方法 | 说明 |
| --- | --- |
| `IClassicBTMessageListener.onClassicBTTextMessage(msg)` | 接收经典蓝牙文本消息。 |
| `IClassicBTMessageListener.onClassicBTAudioStream(buffer)` | 接收经典蓝牙音频数据。 |
| `IClassicBTMessageListener.onBTStreamDataReceived(tag, data, clientId)` | 接收经典蓝牙二进制流数据。 |

<a id="ibleclient"></a>

**IBleClient：BLE 客户端服务**

| 方法 | 说明 |
| --- | --- |
| `IBleClient.startScan(timeoutMillis, fail, onScanResult)` | 开始 BLE 扫描。 |
| `IBleClient.stopScan()` | 停止 BLE 扫描。 |
| `IBleClient.connectToGattServer(device)` | 连接指定 BLE GATT 服务端设备。 |
| `IBleClient.disconnect()` | 断开 BLE 连接。 |
| `IBleClient.sendTextMessage(data)` | 通过 BLE 发送文本消息。 |
| `IBleClient.addBleClientListener(listener)` | 注册 BLE 连接监听器。 |
| `IBleClient.removeBleClientListener(listener)` | 移除 BLE 连接监听器。 |
| `IBleClient.isConnected()` | 判断 BLE 是否已连接。 |
| `IBleClient.release()` | 释放 BLE 客户端资源。 |

<a id="iblebtclientlistener"></a>

**IBleBTClientListener：BLE 连接监听**

| 方法 | 说明 |
| --- | --- |
| `IBleBTClientListener.onConnect(success)` | BLE 连接状态回调。 |

<a id="iblemessagelistener"></a>

**IBleMessageListener：BLE 消息回调**

| 方法 | 说明 |
| --- | --- |
| `IBleMessageListener.onBleTextMessage(msg)` | 接收 BLE 文本消息。 |

<a id="ibluetoothring"></a>

**IBluetoothRing：蓝牙指环服务**

| 方法 | 说明 |
| --- | --- |
| `IBluetoothRing.startScan(deviceNameFilter, timeoutMillis)` | 开始扫描蓝牙指环，默认设备名过滤为 `D01`。 |
| `IBluetoothRing.stopScan()` | 停止扫描蓝牙指环。 |
| `IBluetoothRing.connectToServer(device)` | 连接指定蓝牙指环设备。 |
| `IBluetoothRing.disconnect()` | 断开蓝牙指环连接。 |
| `IBluetoothRing.addClientListener(listener)` | 注册蓝牙指环监听器。 |
| `IBluetoothRing.removeClientListener(listener)` | 移除蓝牙指环监听器。 |

<a id="ibtringclientlistener"></a>

**IBTRingClientListener：蓝牙指环监听**

| 方法 | 说明 |
| --- | --- |
| `IBTRingClientListener.onDeviceFound(device)` | 扫描发现蓝牙指环设备。 |
| `IBTRingClientListener.onScanFinished()` | 蓝牙指环扫描结束。 |
| `IBTRingClientListener.onConnect(success)` | 蓝牙指环连接状态回调。 |
| `IBTRingClientListener.onConnect(extra)` | 返回带扩展信息的蓝牙指环连接状态。 |

<a id="phone-message-file"></a>

## 5 消息与文件传输

<a id="imessage"></a>

**IMessage：消息服务**

| 方法 | 说明 |
| --- | --- |
| `IMessage.addMessageListener(listener)` | 注册消息监听器。 |
| `IMessage.removeMessageListener(listener)` | 移除消息监听器。 |
| `IMessage.sendTextMessageByP2P(message, clientId)` | 通过 P2P 发送文本消息。`clientId` 为空时使用默认目标。 |
| `IMessage.sendTextMessageByClassicBT(message, clientId)` | 通过经典蓝牙发送文本消息。`clientId` 为空时使用默认目标。 |
| `IMessage.sendAudioStreamDataByP2P(streamData)` | 通过 P2P 发送音频流。 |
| `IMessage.sendAudioStreamDataByClassicBT(streamData)` | 通过经典蓝牙发送音频流。 |
| `IMessage.sendTextMessageByBle(message)` | 通过 BLE 发送文本消息。 |
| `IMessage.sendStreamData(tag, data, clientId, callback)` | 发送二进制流数据。 |
| `IMessage.getFileOperater()` | 获取 P2P 文件发送管理器。 |
| `IMessage.getBtFileOperater()` | 获取蓝牙文件发送管理器。 |
| `IMessage.getApkFileOperator()` | 获取 APK 文件发送管理器。 |

<a id="imessagelistener"></a>

**IMessageListener：消息监听器**

| 方法 | 说明 |
| --- | --- |
| `IMessageListener.onP2PTextMessage(msg, clientId)` | 接收 P2P 文本消息。 |
| `IMessageListener.onVideoH264Stream(buffer)` | 接收 H264 视频流。 |
| `IMessageListener.onAudioStream(buffer)` | 接收音频流。 |
| `IMessageListener.onNv21Data(data, width, height)` | 接收 NV21 视频帧数据。 |
| `IMessageListener.onClassicBTTextMessage(msg, clientId)` | 接收经典蓝牙文本消息。 |
| `IMessageListener.onClassicBTAudioStream(buffer)` | 接收经典蓝牙音频数据。 |
| `IMessageListener.onBTStreamDataReceived(tag, data, clientId)` | 接收经典蓝牙二进制流数据。 |

<a id="ifileoperate"></a>

**IFileOperate：文件发送与接收管理器**

| 方法 | 说明 |
| --- | --- |
| `IFileOperate.sendFile(dir, file, listener, onResult)` | 发送文件。`dir` 可指定对端接收目录。 |
| `IFileOperate.isSendingFile()` | 判断当前是否正在发送文件。 |
| `IFileOperate.stopSendFile()` | 停止发送文件。 |
| `IFileOperate.isReceivingFile()` | 判断当前是否正在接收文件。 |
| `IFileOperate.addFileReceiveListener(listener)` | 添加旧版文件接收监听器，建议优先使用 V2。 |
| `IFileOperate.removeFileReceiveListener(listener)` | 移除旧版文件接收监听器。 |
| `IFileOperate.addFileReceiveV2Listener(listener)` | 添加 V2 文件接收监听器。 |
| `IFileOperate.removeFileReceiveV2Listener(listener)` | 移除 V2 文件接收监听器。 |

<a id="filereceivelistener"></a>

**FileReceiveListener：旧版文件接收监听器**

| 方法 | 说明 |
| --- | --- |
| `FileReceiveListener.onStart()` | 开始接收文件。 |
| `FileReceiveListener.onProgressChanged(progress)` | 文件接收进度变化。 |
| `FileReceiveListener.onComplete(filePath)` | 文件接收完成。 |
| `FileReceiveListener.onFail()` | 文件接收失败。 |
| `FileReceiveListener.onCancel()` | 对端取消发送。 |

<a id="filereceivev2listener"></a>

**FileReceiveV2Listener：V2 文件接收监听器**

| 方法 | 说明 |
| --- | --- |
| `FileReceiveV2Listener.onStart(filePath)` | 开始接收指定文件。 |
| `FileReceiveV2Listener.onProgressChanged(filePath, progress)` | 指定文件接收进度变化。 |
| `FileReceiveV2Listener.onComplete(filePath)` | 指定文件接收完成。 |
| `FileReceiveV2Listener.onFail()` | 文件接收失败。 |
| `FileReceiveV2Listener.onCancel(filePath)` | 指定文件接收被取消。 |

<a id="iapkfileoperate"></a>

**IApkFileOperate：APK 文件发送管理器**

| 方法 | 说明 |
| --- | --- |
| `IApkFileOperate.sendFile(file, listener)` | 发送 APK 文件，并通过 `TransferProgressListener` 接收进度。 |

<a id="ifilesystem"></a>

**IFileSystem：文件系统上传服务**

| 方法 | 说明 |
| --- | --- |
| `IFileSystem.setFileUploadListener(listener)` | 设置文件上传监听器。 |
| `IFileSystem.addFileUploadListener(listener)` | 添加文件上传监听器，不覆盖已有监听器。 |
| `IFileSystem.removeFileUploadListener()` | 移除文件上传监听器。 |

<a id="fileuploadlistener"></a>

**FileUploadListener：文件上传监听器**

| 方法 | 说明 |
| --- | --- |
| `FileUploadListener.onStart(filePath, fileName)` | 文件上传开始。 |
| `FileUploadListener.onStatusUpdate(status, url, fileMD5, filePath, fileId)` | 文件上传状态更新，`status` 取值见 [UploadStatus](#uploadstatus)。 |
| `FileUploadListener.onProgress(progress)` | 文件上传进度，按百分比理解。 |

<a id="ifiledownload"></a>

**IFileDownload：文件下载服务**

| 方法 | 说明 |
| --- | --- |
| `IFileDownload.getDownloadFileApi(url)` | 获取下载文件信息。 |
| `IFileDownload.fileDownload(url, filePath, listener)` | 下载文件到指定路径。 |
| `IFileDownload.cancelDownload()` | 取消当前下载。 |

<a id="downloadlistener"></a>

**DownloadListener：下载监听器**

| 方法 | 说明 |
| --- | --- |
| `DownloadListener.onResult(status)` | 下载结果状态，`status` 取值见 [DownloadStatus](#downloadstatus)。 |
| `DownloadListener.onProgress(progress)` | 下载进度，按百分比理解。 |
| `DownloadListener.onProgress(progress, downloaded, total)` | 下载进度和已下载/总大小回调。 |

<a id="phone-device-media"></a>

## 6 设备与媒体流

<a id="idevice"></a>

**IDevice：设备信息与眼镜端音视频流服务**

| 方法 | 说明 |
| --- | --- |
| `IDevice.getGlassDeviceInfo()` | 获取当前连接眼镜的设备信息。 |
| `IDevice.requestVideoStream(tag, videoStreamParam, callback)` | 请求眼镜端视频流。 |
| `IDevice.stopVideoStream(tag, callback)` | 停止眼镜端视频流。 |
| `IDevice.requestAudioStream(tag, callback)` | 请求眼镜端音频流。 |
| `IDevice.stopAudioStream(tag, callback)` | 停止眼镜端音频流。 |
| `IDevice.setDeviceApp(defaultAppsConfig, app3rd, bootApp)` | 设置眼镜端默认应用显隐、第三方应用和开机应用。 |

<a id="imedia"></a>

**IMedia：媒体接收服务**

| 方法 | 说明 |
| --- | --- |
| `IMedia.setMediaReceiver(receiver)` | 设置媒体接收器。 |

<a id="imediareceiver"></a>

**IMediaReceiver：媒体流接收器**

| 方法 | 说明 |
| --- | --- |
| `IMediaReceiver.onH264StreamData(bytes)` | 接收 H264 视频流数据。 |
| `IMediaReceiver.onNv21Data(data, width, height)` | 接收 NV21 视频帧数据。 |

<a id="videostreamlistener"></a>

**VideoStreamListener：视频流文件传输监听器**

| 方法 | 说明 |
| --- | --- |
| `VideoStreamListener.onStreamData(chunk)` | 接收视频流数据块。 |
| `VideoStreamListener.onProgress(percent)` | 视频流传输进度。 |
| `VideoStreamListener.onComplete(file)` | 视频流传输完成。 |
| `VideoStreamListener.onError(e)` | 视频流传输错误。 |

<a id="phone-voice-ai"></a>

## 7 语音、AI 与翻译

<a id="iaichat"></a>

**IAIChat：AI Chat 服务**

| 方法 | 说明 |
| --- | --- |
| `IAIChat.toChat(question)` | 发送 AI Chat 问题。 |
| `IAIChat.setAiChatListener(listener)` | 设置 AI Chat 监听器。 |
| `IAIChat.removeAiChatListener()` | 移除 AI Chat 监听器。 |
| `IAIChat.getRunTimeChatList()` | 获取运行时聊天记录。 |

<a id="aichatlistener"></a>

**AiChatListener：AI Chat 监听器**

| 方法 | 说明 |
| --- | --- |
| `AiChatListener.onAIChatQuestion(questionId, question, imageId)` | 收到 AI 问题回调。`questionId` 可用于关联后续回答。 |
| `AiChatListener.onAiChatAnswer(questionId, answer, isEnd)` | 收到 AI 回答回调，`isEnd` 表示本次回答是否结束。 |
| `AiChatListener.onModify(questionId)` | 收到问题修改回调。 |

<a id="itranslate"></a>

**ITranslate：翻译语言配置服务**

| 方法 | 说明 |
| --- | --- |
| `ITranslate.setLanguage(src, dest)` | 设置翻译源语言和目标语言，取值见 [TranslateLanguage](#phonetranslatelanguage)。 |
| `ITranslate.getLanguageSrc()` | 获取当前源语言。 |
| `ITranslate.getLanguageDest()` | 获取当前目标语言。 |

<a id="phone-recognition"></a>

## 8 识别、采集与跟踪

<a id="itrack"></a>

**ITrack：人脸、车牌检测跟踪服务**

| 方法 | 说明 |
| --- | --- |
| `ITrack.init()` | 初始化人脸、车牌检测跟踪能力。 |
| `ITrack.addTrackListener(listener)` | 注册检测跟踪监听器。 |
| `ITrack.removeTrackListener(listener)` | 移除检测跟踪监听器。 |
| `ITrack.release()` | 释放检测跟踪能力。 |

<a id="itracklistener"></a>

**ITrackListener：检测跟踪监听器**

| 方法 | 说明 |
| --- | --- |
| `ITrackListener.onFaceTrack(faceModels)` | 返回人脸检测跟踪结果。 |
| `ITrackListener.onLPRTrack(lprModel)` | 返回车牌检测跟踪结果。 |
| `ITrackListener.onModeChange(mode)` | 识别/检测模式变化回调，`mode` 取值与 `AIRecgMode` 一致。 |

<a id="ionlinerec"></a>

**IOnlineRec：在线识别服务**

| 方法 | 说明 |
| --- | --- |
| `IOnlineRec.setRecProvider(recProvider)` | 设置在线识别 provider。 |

<a id="iofflinerec"></a>

**IOfflineRec：离线识别服务**

| 方法 | 说明 |
| --- | --- |
| `IOfflineRec.setRecProvider(recProvider)` | 设置离线识别 provider。 |

<a id="irecprovider"></a>

**IRecProvider：识别 provider**

| 方法 | 说明 |
| --- | --- |
| `IRecProvider.recognizeFace(param, callback)` | 手机端提供在线人脸识别能力，并通过回调返回识别结果。 |
| `IRecProvider.onRecognizedInfo(info)` | 接收离线识别结果信息。 |

<a id="icollect"></a>

**ICollect：采集服务**

| 方法 | 说明 |
| --- | --- |
| `ICollect.setProvider(provider)` | 设置采集 provider。 |

<a id="icollectprovider"></a>

**ICollectProvider：采集 provider**

| 方法 | 说明 |
| --- | --- |
| `ICollectProvider.onFaceCollectInfo(imageBase64, info, callback)` | 接收人脸采集信息，并回传采集处理结果。 |
| `ICollectProvider.onLprCollectInfo(imageBase64, info, callback)` | 接收车牌采集信息，并回传采集处理结果。 |

<a id="phone-ota-provision"></a>

## 9 OTA、日志与辅助能力

<a id="iota"></a>

**IOta：OTA 升级服务**

| 方法 | 说明 |
| --- | --- |
| `IOta.checkUpdate(version, osType, cpuType, deviceId, deviceTypeId)` | 检查 OTA 更新，返回 `Flow<OTAResponse>`。 |
| `IOta.otaDownload(url, filePath, listener)` | 下载 OTA 文件。 |
| `IOta.otaDownloadWithResume(url, filePath, listener, downloadedSize)` | 从指定已下载大小继续下载 OTA 文件。 |
| `IOta.cancelDownload()` | 取消 OTA 下载。 |

<a id="ilog"></a>

**ILog：SDK 日志服务**

| 方法 | 说明 |
| --- | --- |
| `ILog.uploadSDKLog(callback)` | 上传 SDK 日志。 |

<a id="inotification"></a>

**INotification：通知服务**

| 方法 | 说明 |
| --- | --- |
| `INotification.sendNotification(bean)` | 发送普通通知。 |
| `INotification.sendPersonnelAlertNotification(face1, face2, msg, callback)` | 发送人员预警通知。 |

<a id="ibusinessfeature"></a>

**IBusinessFeature：业务配置服务**

| 方法 | 说明 |
| --- | --- |
| `IBusinessFeature.getLauncherConfig(onSuccess, onFailure)` | 获取 Launcher 配置。 |
| `IBusinessFeature.getSwitchConfig(onSuccess, onFailure)` | 获取业务开关配置。 |

<a id="phone-parameter-models"></a>

## 10 参数取值与数据结构

这一节用于承接前面 API 中的常量、枚举和复杂参数。遇到初始化参数、下载状态、上传状态或语言码时，可以先到这里确认取值含义。

<a id="engineparam"></a>

**EngineParam：SDK 初始化参数**

| 字段 | 说明 |
| --- | --- |
| `clientIds` | 当前手机端应用使用的 clientId 列表，需要和眼镜端应用注册的 clientId 对应。 |
| `serviceBusConfig` | 服务总线配置。 |
| `envType` | 环境类型，取值见 [EnvType](#envtype)。 |
| `customHost` | 自定义环境地址。`envType = EnvType.CUSTOM` 时需要配置。 |
| `userAuthInfo` | API Key 鉴权信息。 |
| `banServiceList` | 不初始化的网络服务列表，取值见 [NetServiceType](#netservicetype)。 |

<a id="envtype"></a>

**EnvType：环境类型**

| 常量 | 说明 |
| --- | --- |
| `EnvType.PUBLIC` | 公网环境。 |
| `EnvType.CUSTOM` | 自定义或私有化环境，需要配置 `customHost`。 |

<a id="netservicetype"></a>

**NetServiceType：网络服务初始化控制**

| 常量 | 说明 |
| --- | --- |
| `NetServiceType.ALL` | 所有网络服务都不初始化。 |
| `NetServiceType.TranslateService` | 翻译服务。 |
| `NetServiceType.AsrService` | ASR 服务。 |
| `NetServiceType.TtsService` | TTS 服务。 |

<a id="downloadstatus"></a>

**DownloadStatus：下载状态**

| 常量 | 值 | 说明 |
| --- | ---: | --- |
| `DOWNLOAD_STATUS_DOWNLOADING` | `0` | 下载中。 |
| `DOWNLOAD_STATUS_DOWNLOADED` | `1` | 下载完成。 |
| `DOWNLOAD_STATUS_DOWNLOAD_FAILED` | `-1` | 下载失败。 |

<a id="uploadstatus"></a>

**UploadStatus：上传状态**

| 常量 | 值 | 说明 |
| --- | ---: | --- |
| `UPLOAD_STATUS_ING` | `0` | 上传中。 |
| `UPLOAD_STATUS_SUCCESS` | `1` | 上传成功。 |
| `UPLOAD_STATUS_FAILED` | `-1` | 上传失败。 |

<a id="phonetranslatelanguage"></a>

**TranslateLanguage：翻译语言码**

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `TranslateLanguage.auto` | `auto` | 自动识别。 |
| `TranslateLanguage.zh` | `zh` | 中文。 |
| `TranslateLanguage.en` | `en` | 英文。 |
| `TranslateLanguage.ja` | `ja` | 日文。 |
| `TranslateLanguage.yue` | `yue` | 粤语。 |
| `TranslateLanguage.ko` | `ko` | 韩语。 |
| `TranslateLanguage.de` | `de` | 德语。 |
| `TranslateLanguage.fr` | `fr` | 法语。 |
| `TranslateLanguage.it` | `it` | 意大利语。 |
| `TranslateLanguage.es` | `es` | 西班牙语。 |
| `TranslateLanguage.ug` | `ug` | 维语。 |
| `TranslateLanguage.ru` | `ru` | 俄语。 |
| `TranslateLanguage.ar` | `ar` | 阿拉伯语。 |
| `TranslateLanguage.th` | `th` | 泰语。 |
| `TranslateLanguage.vi` | `vi` | 越南语。 |

<a id="defaultglassapp"></a>

**IDevice.DefaultGlassApp：眼镜端默认应用 Key**

| 常量 | appKey | 说明 |
| --- | --- | --- |
| `AI_ASSISTANT` | `aiAssistant` | AI 工作助手。 |
| `AI_INDUSTRY` | `aiIndustry` | AI 巡检。 |
| `AI_CHAT` | `safetyInspection` | AI 问答。 |
| `OFFLINE_RECOGNIZE` | `offlineFace` | 离线人脸。 |
| `LPR` | `lprDetection` | 车牌识别。 |
| `TAKE_PHOTO` | `takephoto` | 拍照。 |

**IDevice.ConfigInfo：眼镜端应用配置**

| 字段 | 说明 |
| --- | --- |
| `key` | 默认应用 Key，取值见 `IDevice.DefaultGlassApp`。 |
| `rename` | 应用重命名；为空表示不重命名。 |
| `isHide` | 是否隐藏该应用。 |
