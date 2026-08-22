# Glass3 SDK 使用方法

整理自 Rokid Sprite Enterprise 官方文档（SDK `2.2.0-E`，2026-8-6）。完整原文在 [`official/`](./official/)，来源见 [SOURCE.md](./SOURCE.md)。

Glass3 SDK 分两端协作：

| 端 | 统一入口 | Maven 坐标 | 职责 |
| --- | --- | --- | --- |
| 眼镜端 | `GlassSdk` | `com.rokid.security:glass3.open.sdk:2.2.0-E` | 运行在眼镜上：媒体、语音、识别、收发消息、设备状态 |
| 手机端 | `PSecuritySDK` | `com.rokid.security:phone.sdk:2.2.0-E` | 运行在 Android 手机上：扫描连接、蓝牙/P2P、消息文件、媒体流接收、OTA |

```text
手机应用  --蓝牙扫描/配对-->  Glass3
                |
                +--协商 P2P-->  消息 / 文件 / 音视频流 / 远程控制
```

眼镜连上 P2P 后通常不直接访问公网，典型路径是：`眼镜 → P2P → 手机 → 蜂窝/Wi-Fi → 业务服务器`。

两端必须使用**相同的 `clientId`**，手机才能把数据发给对应的眼镜应用。官方 Demo 使用 `GlassSample`（眼镜）和 `SecurityPhone` / `GlassSample`（手机 `clientIds` 列表）。

---

## 1. 开发环境

| 项目 | 要求 |
| --- | --- |
| Android Studio | 2022 或更高 |
| JDK | 17+ |
| Kotlin / Gradle | Kotlin 1.8.22+，Gradle 7.4.2+（FAQ 建议） |
| 系统 | Android 8.0+（FAQ 建议 API 24+） |
| 设备 | Rokid Glass3（企业版系统）+ Android 手机 |
| 调试线 | **必须用 Glass3 数据调试线**，充电线无法被 Android Studio 识别 |
| 投屏 | 有线用 `scrcpy`；无线用 RokidMirror + 眼镜「扫一扫」 |

企业版与消费版 **不是同一套 SDK**。企业版系统版本号通常含 `e`。眼镜被其他手机占用时，本机往往扫描不到。

官方 Demo：

```bash
git clone https://github.com/RokidSuuport/glass3_sdk_demo.git
```

仓库内两个独立工程，需分别打开：

- `glassdemo`：眼镜端，入口 `com.rokid.glass.HomeActivity`
- `glass3sdkphonedemo`：手机端，入口 `com.rokid.phone.ui.MainPhoneActivity`

建议先跑手机端，再跑眼镜端。在线能力需要把 `UserAuthInfo("", "")` 换成商务提供的 API Key。

---

## 2. 接入依赖

### 2.1 Maven 仓库

Gradle 7.0+，在 `settings.gradle`：

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://maven.rokid.com/repository/maven-public/' }
    }
}
```

### 2.2 眼镜端

```groovy
dependencies {
    implementation ('com.rokid.security:glass3.open.sdk:2.2.0-E') {
        exclude group: "org.slf4j"
    }
}
```

native 库冲突时：

```groovy
android {
    packagingOptions {
        pickFirst 'lib/arm64-v8a/libr2aud.so'
        pickFirst 'lib/armeabi-v7a/libr2aud.so'
    }
}
```

### 2.3 手机端

```groovy
dependencies {
    implementation ('com.rokid.security:phone.sdk:2.2.0-E') {
        exclude group: "org.slf4j"
    }
}
```

权限清单见 [快速开始](./official/references/docs/terminal-sdk/getting-started/快速开始.md)。蓝牙、定位、Nearby、相机、麦克风、存储属于危险权限，Android 6.0+ 必须动态申请。Android 12+ 需要 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`。

---

## 3. 初始化

### 3.1 眼镜端

先 `bindSecurityService`，在 `onServiceConnected` 里 `registerClient`。服务对象可能为 null，调用前检查 `GlassSdk.isReady()`。

```kotlin
fun initSdk() {
    if (GlassSdk.isReady()) return
    GlassSdk.bindSecurityService(Utils.getApp(), object : IServiceConnectionCallback {
        override fun onServiceConnected() {
            // 必须与手机端 clientId 一致
            GlassSdk.registerClient("GlassSample", mClientMessageCallback)
        }
        override fun onServiceDisconnected() {}
        override fun onBindingDied() {}
    })
}

Log.d("SDK_CHECK", "glass sdk ready = ${GlassSdk.isReady()}")
```

退出时调用 `GlassSdk.unbindSecurityService()` / `GlassSdk.release()`。

### 3.2 手机端

```kotlin
val clientIds = arrayListOf("SecurityPhone", "GlassSample")
val userAuthInfo = UserAuthInfo(appId = "", secret = "") // 向商务申请

val param = EngineParam(
    clientIds = clientIds,
    userAuthInfo = userAuthInfo,
    envType = EnvType.PUBLIC,
    banServiceList = arrayListOf(NetServiceType.ALL)
)

PSecuritySDK.initSDK(param) { result ->
    if (result.isSuccess) {
        // 初始化成功后再 getXxxService()
    }
}

Log.d("SDK_CHECK", "phone sdk initialized = ${PSecuritySDK.getMobileEngineService().isInit()}")
```

除 `initSDK()`、`getMobileEngineService()`、`getOtaEngineService()` 外，多数服务在未初始化时返回空值。销毁用 `PSecuritySDK.destroySDK()`。

---

## 4. 连接：蓝牙然后 P2P

### 4.1 通道怎么选

| 数据 | 通道 |
| --- | --- |
| 小消息、控制指令、小文件 | 经典蓝牙 |
| 大文件、图片、视频、实时流、APK | Wi-Fi P2P |

P2P **不能替代蓝牙的第一步**。必须先蓝牙连上，再通过蓝牙协商 P2P。建连时手机 Wi-Fi 打开、热点关闭。

```text
权限检查 → 蓝牙扫描/连接 → 发送 P2P 建连请求 → 协商 Wi-Fi P2P → 传文件/流
```

### 4.2 手机端：扫描并连接眼镜

```kotlin
val bt = PSecuritySDK.getClassicBlueToothClientService()
bt?.addClientListener(object : IClassicBTClientListener {
    override fun onDeviceFound(device: BluetoothDevice) { /* 过滤 Glass3_ 前缀 */ }
    override fun onScanFinished() {}
    override fun onConnect(success: Boolean) {}
    override fun onConnectionRejected(reason: String, code: Int) {}
})
bt?.startScan(timeoutMillis)
bt?.connectToServer(device, action)
```

Demo 入口：`ClassicBtActivity` → 统一配对页 `BtWifiConnectActivity`。

P2P：

```kotlin
val p2p = PSecuritySDK.getWifiP2PClientService()
p2p?.initialize(onResult)
p2p?.sendConnectP2pRequest(action)   // 依赖已连接的蓝牙
p2p?.connectDevice(device, onResult)
p2p?.addWifiP2PClientListener(listener)
```

设备名在系统蓝牙列表中类似 `Glass3_XXXX`。扫描逻辑不要把该前缀滤掉。

### 4.3 排查要点

- 扫描不到：权限、蓝牙开关、是否被其他手机占用、企业/消费版是否匹配
- 连上但消息不通：`clientId`、眼镜是否 `registerClient`、回调是否注册、眼镜 App 是否被杀
- P2P 失败：蓝牙是否已连、Wi-Fi/热点、是否过早建连、版本是否匹配

详见 [蓝牙排查](./official/references/docs/faq/蓝牙问题排查.md) 和 [P2P 排查](./official/references/docs/faq/P2P问题排查.md)。

---

## 5. 能力入口

调用前确认 SDK 已就绪，并对返回值做空判断。

### 5.1 眼镜端 `GlassSdk.getGlassXxxService()`

| 方法 | 服务 | 用途 |
| --- | --- | --- |
| `getClassicBluetoothService()` | `IBTService` | 经典蓝牙 |
| `getP2PGoService()` | `IWifiP2PGoService` | Wi-Fi P2P |
| `getGlassMessageService()` | `IMessageServer` | 消息 / 文件 |
| `getGlassCommonService()` | `ICommonInfoServer` | 用户信息、伴生 App、配置 |
| `getGlassMediaService()` | `IMediaServer` | 预览、拍照、录像、录音、变焦 |
| `getGlassAiChatService()` | `IAiChatService` | AI 问答 |
| `getGlassAsrService()` | `IAsrService` | 在线 ASR |
| `getGlassTtsService()` | `ITtsService` | 在线 TTS |
| `getGlassOfflineTtsService()` | `IOfflineTtsService` | 离线 TTS（需 2.2.0+） |
| `getGlassOfflineCmdService()` | `IOfflineCmdService` | 离线语音指令 |
| `getGlassOnlineRecService()` | `IOnlineRecService` | 在线人/车识别 |
| `getGlassOfflineRecService()` | `IOfflineRecServer` | 离线识别 |
| `getGlassOfflineFeatureRecService()` | `IOfflineFeatureRecService` | 离线特征识别 |
| `getGlassCollectService()` | `ICollectService` | 图像采集 |
| `getGlassTrackService()` | `ITrackService` | 人/车跟踪 |
| `getGlassIdentificationService()` | `IIdentificationService` | 身份识别 |
| `getGlassTranslateService()` | `ITranslateService` | 翻译 |
| `getGlassFileSystemService()` | `IFileSystemService` | 文件上传 |
| `getGlassDeviceService()` | `IDeviceService` | 电量、亮度、音量、系统 |
| `getGlassBluetoothRingService()` | `IBluetoothRingService` | 指环 |
| `getGlassNotificationService()` | `INotificationService` | 通知 |

### 5.2 手机端 `PSecuritySDK.getXxxService()`

| 方法 | 服务 | 用途 |
| --- | --- | --- |
| `getMobileEngineService()` | `IMobileEngine` | 初始化状态、用户信息 |
| `getClassicBlueToothClientService()` | `IClassicBluetoothClient` | 经典蓝牙客户端 |
| `getWifiP2PClientService()` | `IWifiP2PClientOperate` | P2P、H264/NV21 流 |
| `getMessageService()` | `IMessage` | 消息 / 文件 / APK |
| `getAbsDeviceInfoService()` | `IDevice` | 设备信息、眼镜音视频流 |
| `getAbsNotificationService()` | `INotification` | 通知同步 |
| `getFileSystemService()` | `IFileSystem` | 文件上传 |
| `getAbsAIChatService()` | `IAIChat` | AI Chat |
| `getAbsTranslateService()` | `ITranslate` | 翻译 |
| `getOtaEngineService()` | `IOta` | OTA |
| `getBluetoothRingService()` | `IBluetoothRing` | 指环，默认名过滤 `D01` |
| `getTrackService()` | `ITrack` | 检测跟踪 |
| `getOnlineRecService()` / `getOfflineRecService()` | 识别 | 在线 / 离线识别 |
| `getCollectService()` | `ICollect` | 采集 |
| `getAbsSdkLogService()` | `ILog` | 日志上传 |

完整方法、参数、回调见：

- [眼镜端 API](./official/references/docs/terminal-sdk/api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md)
- [手机端 API](./official/references/docs/terminal-sdk/api-reference/Glass3%20%20SDK(手机端)%20API文档.md)

---

## 6. 消息与文件

不要发明 `sdk.sendFile(...)` 这类通用方法。按文档使用 operator：

- P2P 文件：`getFileOperater().sendFile(...)`
- 蓝牙文件：`getBtFileOperater().sendFile(...)`
- APK：`getApkFileOperator()?.sendFile(...)`

### 6.1 手机 → 眼镜

```kotlin
val msg = PSecuritySDK.getMessageService()

msg?.sendTextMessageByClassicBT("hello", "GlassSample")
msg?.sendTextMessageByP2P("hello", "GlassSample")

msg?.getFileOperater()?.sendFile(dir, file, listener, onResult)
msg?.getBtFileOperater()?.sendFile(dir, file, listener, onResult)
msg?.getApkFileOperator()?.sendFile(file, listener)
```

接收用 `addMessageListener`，文件接收优先 `addFileReceiveV2Listener`。

一个手机 App 可以连多个眼镜端 App，靠目标 `clientId` 区分。

### 6.2 眼镜 → 手机

```kotlin
val msg = GlassSdk.getGlassMessageService()
msg?.sendTextMessageByP2P("hello")
msg?.sendTextMessageByClassicBT("hello")
// 指定对端 clientId 时用 WithClient 变体
```

眼镜端发文件建议放公共存储目录。默认接收路径：

| 端 | 路径 |
| --- | --- |
| 眼镜收到文件 | `/storage/emulated/0/Download/receiver/` |
| 手机收到文件 | `/sdcard/Android/data/<包名>/files/receiver/` |

系统拍照/录像在 P2P 上传后，眼镜端图片可能被系统清理，业务以手机端接收结果为准。

---

## 7. 媒体

眼镜显示分辨率固定 **480×640 竖屏**，不能改。摄像头是定焦，拍照不支持数字变焦，**视频支持**数字变焦。默认视频流约 1080×1920、15–19 fps、H.264 或 NV21，手机播放延迟约 300 ms。

```kotlin
val media = GlassSdk.getGlassMediaService()

media?.addPhotoCallback(photoFileCallback)
media?.takePhoto(photoResolution, path)

media?.startRecord(videoCallback, recordConfig)
media?.stopRecord()

media?.startAudioRecord(audioCallback)
media?.stopAudioRecord(audioCallback)

media?.zoomCamera(level) // 1 = 不缩放，最大见 getMaxZoomLevel()
```

录像输出在公共图片目录。录音示例为 AAC。720P 拍照偏横屏，1080P 部分模式偏竖屏，4K 拍照为横屏。

手机拉流：

```kotlin
val p2p = PSecuritySDK.getWifiP2PClientService()
p2p?.setAutoDecodeH264ToNv21(true)
// IWifiP2PMessageListener / IMessageListener:
// onVideoH264Stream / onNv21Data / onAudioStream
```

Demo：眼镜 `SdkMediaActivity`，手机相册预览见代码示例 `30-media`。

---

## 8. 语音与 AI

| 能力 | 网络 | 典型用法 |
| --- | --- | --- |
| 离线语音指令 | 无 | 高频固定命令 |
| 离线 TTS | 无，需 SDK ≥ 2.2.0 | 本地播报 |
| 在线 ASR | 需要 | 转写、问答；**暂不支持离线 ASR** |
| 在线 TTS / AI Chat | 需要 API Key | 对话、合成 |

```kotlin
// 离线指令
GlassSdk.getGlassOfflineCmdService()?.add(
    VoiceAction("打开编号12的警灯", "da kai bian hao shi er de jing deng",
        object : IVoiceCallback.Stub() {
            override fun onVoiceTriggered() {}
        })
)

// 离线 TTS
GlassSdk.getGlassOfflineTtsService()?.playTtsMsg("秋天不回来，我要去爬山啦")

// 在线 ASR
GlassSdk.getGlassAsrService()?.startSpeech(object : SpeechCallback.Stub() {
    override fun onStart() {}
    override fun onIntermediateVad(content: String) {}      // 中间结果 / 字幕
    override fun onAsrComplete(content: String?) {}         // 最终文本
    override fun onAsrCompleteWithIntent(content: String?, intent: Int, intentJson: String) {}
    override fun onError(code: Int) {}
})

// AI Chat
val ai = GlassSdk.getGlassAiChatService()
ai?.startAiChat(false)
ai?.toAiChat("杭州明天下雨吗", listener)
ai?.endAiChat()
```

录像和在线 ASR 用 SDK 方法时底层会做音频分发，一般不抢麦克风。TTS 时尽量不要同时投屏，声音可能被路由到投屏设备。

私有化 ASR/TTS 见 [SDK_INTEGRATION](./official/references/docs/private-speech/SDK_INTEGRATION.md)。

---

## 9. 视觉识别

人脸检测 Demo（`GlassFaceTrackActivity`）：

1. `GlassSdk.getGlassOnlineRecService()`
2. 注册 `IGlassDetectionListener`
3. `startDetection(MODE_FACE)`
4. 在 `onProcessedFaceModels()` 里按框面积、`iqaScore`、`faceScore`、`trackId` 筛选（示例过滤 `iqaScore < 40`）
5. `getFaceSamllBitmap(trackId)` 取抓拍图
6. 页面退出时停止检测并移除监听

注意：该示例偏「检测 + 抓拍质量筛选」，不是完整身份比对。最多同时约 5 张脸，有效尺寸建议 ≥ 50×50；正对效果更好，大角度易漏检。推荐识别距离约 3 米，最远约 5 米。

车牌：眼镜端接入车牌检测，把结果交给业务服务器处理后再回显。人脸库可在灵眸平台上传，眼镜联网后同步离线库。

---

## 10. 眼镜 UI

| 项 | 约定 |
| --- | --- |
| 坐标系 | 固定 480×640 竖屏，不要 `screenOrientation="landscape"` |
| 布局 | 中间 480×400 放主内容；顶 160px / 底 80px 放次要信息 |
| 倒影 | 顶部约 40px 可能有光学倒影，关键元素整体下移 40px |
| 颜色 | 建议绿色主色；Activity 背景用黑色减闪屏 |
| 常亮 | `FLAG_KEEP_SCREEN_ON` |
| 字体 | HarmonyOS Sans，见 [设计规范](./official/references/docs/terminal-sdk/capabilities/设计规范.md) |

全黑背景可以让镜片上看不到画面。没有震动模块，也没有地磁传感器。双麦克风。

---

## 11. 代码示例索引

官方 Demo 场景对应文档：[`downloads/samples.md`](./official/references/docs/downloads/samples.md)。

| 分类 | 场景 | Demo 位置 |
| --- | --- | --- |
| 连接 | 经典蓝牙扫描 | `ClassicBtActivity` |
| 连接 | 蓝牙 + P2P 一体化 | `BtWifiConnectActivity` |
| 连接 | 指环 | 手机端指环扫描，设备名默认 `D01` |
| 消息 | 手机发消息/文件/APK | `SendMessageActivity`（手机） |
| 消息 | 眼镜发消息/文件 | `SendMessageActivity`（眼镜） |
| 媒体 | 拍照/录像/录音/AI | `SdkMediaActivity` |
| 语音 | 离线指令 / TTS / ASR | `HomeActivity`、`SendMessageActivity` |
| 视觉 | 人脸检测 | `GlassFaceTrackActivity` |
| 系统 | OTA、亮度音量同步 | `50-system` |

建议验证顺序：SDK 初始化 → 蓝牙连接 → 文本消息 → 小文件 → P2P → 拍照/预览。

---

## 12. 日志与支持资料

| 位置 | 路径 |
| --- | --- |
| 手机 Demo 日志 | `/sdcard/Android/data/<包名>/files/Documents/mobileLog` |
| 眼镜日志 | `/sdcard/Download/glass3Log` |

企业版 App 也可在连接眼镜后：设置 → 日志上传，并提供眼镜 SN。

相关原文：

- [接入指南](./official/references/docs/terminal-sdk/getting-started/接入指南.md)
- [快速开始](./official/references/docs/terminal-sdk/getting-started/快速开始.md)
- [Demo 运行指南](./official/references/docs/downloads/demo-guide.md)
- [FAQ](./official/references/docs/faq/常见问题.md)
- [产品手册摘录](./official/references/product-manual.md)
