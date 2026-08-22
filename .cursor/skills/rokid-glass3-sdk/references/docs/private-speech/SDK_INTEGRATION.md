# ASR/TTS 语音服务 SDK 接入指南

本文档适用于独立部署在眼镜端的 ASR/TTS 语音服务，面向三方开发者说明 `online-speech` SDK 的接入依赖、初始化与调用方式。该 SDK 支持对接 Rokid 公有云语音服务，也支持通过可配置的 `domain`、`asrPath`、`ttsPath` 接入私有化部署环境。

## 1. 环境依赖

### 1.1 构建环境

- JDK 17
- Android Gradle Plugin 8.2.2（Android 模块）
- Kotlin 2.2.0

### 1.2 运行环境（Android）

- `minSdk = 26`
- 需要权限：
  - `android.permission.INTERNET`
  - `android.permission.RECORD_AUDIO`（ASR 麦克风模式）

## 2. 依赖接入

先在工程仓库配置里加入 Rokid Maven：

- `https://maven.rokid.com/repository/maven-public/`

`settings.gradle(.kts)` 示例：

```kotlin
dependencyResolutionManagement {
    repositories {
        maven(url = "https://maven.rokid.com/repository/maven-public/")
        google()
        mavenCentral()
    }
}
```

或老版本 Gradle 在根 `build.gradle(.kts)` 的 `allprojects.repositories` 中添加同样地址。

需要依赖以下组件，其中 Glass3 SDK 请集成最新版本。本文示例使用当前版本 `2.2.0-E`，如果项目使用更高版本，请以最新版本为准：

- `com.rokid.security.sdk:online-speech:0.1.1`
- Glass3 SDK：`com.rokid.security:glass3.open.sdk:2.2.0-E`

应用工程示例：

```kotlin
dependencies {
    implementation("com.rokid.security.sdk:online-speech:0.1.1")
    implementation("com.rokid.security:glass3.open.sdk:2.2.0-E")
}
```

## 3. SDK 初始化与调用

### 3.1 初始化

私有化部署时，请将 `domain`、`asrPath`、`ttsPath` 替换为私有化环境提供的域名与路径；API Key、UID、设备 ID 按实际部署环境分配。

```kotlin
val cfg = OnlineSpeechSdkConfig(
    domain = "api.rokid.com",
    ak = "<AK>",
    sk = "<SK>",
    uid = "<UID>", // 可根据业务需求自定义
    deviceId = "<DEVICE_ID>", // 可根据业务需求自定义
    asrPath = "/ar/audio/api/ws/asr/streaming",
    ttsPath = "/ar/audio/api/ws/tts",
    trustAllCerts = true, // 调试可开，生产建议关闭
    staticHttpHeaders = mapOf(
        "appCredential" to "userInfo",
        "messageId" to "msg-${System.currentTimeMillis()}",
    ),
    staticMessageHeaders = mapOf(
        "appCredential" to "userInfo",
        "messageId" to "msg-${System.currentTimeMillis()}",
    ),
)
val sdk = OnlineSpeechSdk(cfg)
```

### 3.2 ASR

```kotlin
val asr = sdk.createAsrClient()
    .attachAudioSource(OpenSdkAudioSource())

asr.connect()
asr.startAsrWithMic()
// ...
asr.stopAsrWithMic()
```


### 3.3 TTS

```kotlin
val tts = sdk.createTtsClient()
    .attachStreamPlayer(AndroidPcmTtsStreamPlayer())

tts.connect()
tts.speak("你好，欢迎使用在线语音 SDK。")
tts.stop()
```

可监听播放状态：

- `IDLE`
- `BUFFERING`
- `PLAYING`
- `COMPLETED`
- `STOPPED`
- `FAILED`

## 4. 生命周期建议

- 页面 `onStart/onResume`：按需 `connect()`
- 页面 `onStop/onDestroy`：调用 `close()` 释放 WebSocket
- 应用退出：`sdk.close()`

## 5. 常见问题

- WebSocket 超时：优先核对 `domain/asrPath/ttsPath` 与证书策略
- ASR 无结果：确认先 `asr.connect()`，再 `asr.startAsrWithMic()`
- TTS 无声音：确认先 `tts.connect()`，检查设备音量和音频路由
