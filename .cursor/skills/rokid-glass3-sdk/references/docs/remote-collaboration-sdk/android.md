# 远程协作 SDK（Android 端）接入

远程协作 Android SDK 用于在 Android 应用中集成音视频会议、呼叫邀请、屏幕共享、电子白板、视频点选、AR 标注、云端录制和会议文件等能力。它适合需要在自有 App 内构建远程协作体验的项目。

> SDK 提供 RTC 和会议能力，不包含完整业务页面。账号体系、联系人展示、会议入口、来电页面和会议中 UI 需要接入方结合自身 App 实现。

## 1. 适用场景

- 在 Android 手机端或 Glass3 眼镜端 App 内发起、加入远程协作会议。
- 实现专家呼叫、成员邀请、接听、拒绝、忙线等协作流程。
- 在会议中控制摄像头、麦克风、扬声器、本地预览和音视频流。
- 使用屏幕共享、电子白板、视频点选、视频控制和 AR 标注等会议内协作能力。
- 对接云端录制、会议文件和日志上传，辅助业务留痕与问题排查。

## 2. 接入边界

Android SDK 负责会议与 RTC 相关能力，业务侧仍需自行准备：

- 当前登录用户 ID、Token 或业务身份。
- 联系人列表、用户昵称、头像和组织信息。
- 会议入口、来电页面、会议中 UI 和异常提示。
- AppId、RTC 服务地址、入会 Token 等环境配置。

### 2.1 接入前准备清单

正式接入前，建议先确认下面这些事项。缺少其中任何一项，都可能导致 SDK 可以集成但无法初始化、登录或入会。

| 准备项 | 用途 | 获取或确认方式 |
| --- | --- | --- |
| 远程协作能力开通 | 确认企业账号具备远程协作能力。 | 联系 Rokid 项目经理、销售或交付同事确认。 |
| SDK 版本 | 用于配置 Gradle 依赖。 | 以项目对接时确认的版本为准。 |
| Maven 仓库访问 | 用于拉取远程协作 SDK。 | 确认开发环境可以访问 Rokid Maven 仓库。 |
| `appId` | SDK 初始化参数，用于标识 RTC 应用。 | 由 Rokid 分配。 |
| `rtcUrl` | SDK 初始化参数，用于登录、入会和配置查询。 | 由项目环境配置提供。 |
| `rtcWebsocketUrl` / `iceServers` | 可选配置，用于长链和 RTC 网络连接。 | 如项目环境有单独配置，按实际环境填写。 |
| 当前用户 `userId` | SDK 登录和会议成员识别使用。 | 由接入方账号体系或业务服务提供。 |
| 入会 `token` | 创建或加入会议时使用。 | 通常由业务后端或 Rokid OpenAPI 下发。 |
| 运行时权限 | 摄像头、麦克风、通知、屏幕共享等能力需要。 | App 侧在调用对应能力前动态申请。 |

### 2.2 账号与鉴权说明

远程协作 Android SDK 接入手册没有要求在客户端配置平台 OpenAPI 的 `API_KEY`。客户端需要准备 `appId`、`rtcUrl`、当前用户 `userId`、入会 `token` 等 SDK 初始化和入会参数；这些通常由 Rokid 项目环境、接入方账号体系或业务后端提供。

如果业务后端还需要调用平台 OpenAPI 查询会议记录、参会人、会议文件、IM 消息或录制列表，才需要按平台 OpenAPI 文档配置服务端鉴权。相关密钥只应保存在服务端，不要写入 Android App，也不要下发到客户端。

如果只需要查询远程协作会议记录、参会人、文件、IM 消息或录制列表，请使用 [平台 OpenAPI：远程协作](/openapi/远程协作.md)。

## 3. 推荐调用流程

1. 在工程中添加 Rokid Maven 仓库和远程协作 SDK 依赖。
2. 在 `AndroidManifest.xml` 中声明网络、相机、麦克风、蓝牙、前台服务等权限。
3. 在应用启动阶段初始化 RTC 引擎。
4. 当前业务用户登录成功后，调用 SDK 登录远程协作能力。
5. 通过频道管理能力创建或加入会议。
6. 通过呼叫管理能力邀请、接听、拒绝或取消邀请。
7. 进入会议后，通过频道对象控制音视频、共享、白板、点选、标注和录制。
8. 页面销毁或业务结束时移除监听，并在合适时机登出或销毁 SDK。

### 3.1 最小验证路径

首次接入时，建议先按最小路径验证：

1. Gradle 可以成功拉取 SDK 依赖。
2. App 已声明并动态申请相机、麦克风等必要权限。
3. `init` 初始化成功。
4. `login(userId)` 登录成功。
5. 可以创建或加入一个测试会议。
6. 会议中可以打开麦克风、摄像头，并收到成员状态回调。

## 4. 工程配置

### 4.1 SDK 版本

当前远程协作 Android SDK 版本示例：

```properties
RTC_SDK_VERSION=6.0.1-20260610.105440-5
```

实际接入时，请以项目对接时确认的 SDK 版本为准。

### 4.2 添加 Rokid Maven 仓库

Gradle 7.0 及以上版本，推荐在项目根目录 `settings.gradle` 中配置：

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://maven.rokid.com/repository/maven-public/' }
    }
}
```

如果项目使用较旧版本 Gradle，也可以在根目录 `build.gradle` 的 `buildscript.repositories` 和 `allprojects.repositories` 中添加同一仓库地址。

### 4.3 添加 SDK 依赖

在业务 App 模块的 `build.gradle` 中添加：

```groovy
dependencies {
    implementation "com.rokid.rtc:rtc:6.0.1-20260610.105440-5"
}
```

### 4.4 Android 编译配置

建议接入工程使用以下配置：

| 配置项 | 建议值 |
| --- | --- |
| `compileSdkVersion` | `34` |
| `targetSdkVersion` | `34` |
| `minSdkVersion` | `26` |
| Java / Kotlin JVM Target | `17` |
| ABI | `armeabi-v7a`、`arm64-v8a` |

示例：

```groovy
android {
    compileSdkVersion 34

    defaultConfig {
        minSdkVersion 26
        targetSdkVersion 34

        ndk {
            abiFilters 'armeabi-v7a', 'arm64-v8a'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}
```

### 4.5 Manifest 权限

根据实际使用能力，在 `app/src/main/AndroidManifest.xml` 中声明权限。常用权限如下：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

如果使用屏幕共享、前台服务、通知、悬浮窗、文件上传等能力，需要根据目标 Android 版本补充对应权限：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

### 4.6 运行时权限说明

- Android 6.0 及以上版本，相机、麦克风、存储等敏感权限需要动态申请，不能只依赖 `AndroidManifest.xml` 静态声明。
- Android 12 及以上版本，需要关注蓝牙权限和前台服务类型配置。
- Android 13 及以上版本，如果使用通知，需要申请 `POST_NOTIFICATIONS`。
- 如果业务使用屏幕共享，需要按系统要求处理 MediaProjection 授权流程。

## 5. 核心入口

| 入口 | 说明 |
| --- | --- |
| `RKCooperation.getRtcEngine()` | 初始化、登录、登出、本地设备、全局视频配置和日志上传。 |
| `RKCooperation.getChannelManager()` | 创建会议、加入会议、查询会议、录制和频道缓存。 |
| `RKCooperation.getCallManager()` | 邀请、取消邀请、接听、拒绝、忙线和来电监听。 |
| `RKChannel` | 会议频道对象，用于离开或结束会议、成员管理、音视频流控制、频道消息和会议文件。 |
| `RKChannel.getChannelShare()` | 会议共享能力，用于屏幕共享、电子白板、视频点选、视频控制和 AR 标注。 |
| `RKCooperation.getRtcEngine().getLocalDevice()` | 本地设备能力，用于摄像头、麦克风、扬声器、音频设备和采集参数控制。 |

## 6. 初始化与登录

### 6.1 初始化 RTC 引擎

建议在应用主进程的 `Application.onCreate()` 中初始化，完成后不需要重复初始化。

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        RKCooperation.getRtcEngine().init(
            context = this,
            appId = appId,
            rtcUrl = rtcUrl
        )
    }
}
```

接口定义：

```kotlin
fun init(
    context: Context,
    appId: String,
    rtcUrl: String,
    rtcWebsocketUrl: String? = null,
    iceServers: List<RKIceServer>? = null
)
```

| 参数 | 是否必填 | 说明 |
| --- | --- | --- |
| `context` | 是 | 建议传 `Application` 或 `applicationContext`，避免持有页面上下文。 |
| `appId` | 是 | RTC 应用 ID，用于区分接入方和环境。 |
| `rtcUrl` | 是 | RTC 业务服务地址，SDK 会基于该地址完成登录、入会和配置查询。 |
| `rtcWebsocketUrl` | 否 | RTC 长链地址；不传时由 SDK 或服务端配置决定。 |
| `iceServers` | 否 | TURN/STUN 配置；不传时由 SDK 或服务端配置决定。 |

### 6.2 可选配置：多路视频流

SDK 支持开启或关闭 Simulcast。多人会议、宫格布局、弱网优化、大小流切换等场景建议开启。

```kotlin
RKCooperation.getChannelManager().enableSimulcast(enable = true)
```

### 6.3 登录、状态和登出

使用会议功能前需要先登录。登录失败时无法创建、加入或接听会议。

```kotlin
val result = RKCooperation.getRtcEngine().login(
    userId = userId,
    forceRefreshToken = false
)
```

常用状态接口：

```kotlin
val isLogin = RKCooperation.getRtcEngine().isLogin()
val currentUserId = RKCooperation.getRtcEngine().getUserId()
```

登出与销毁：

```kotlin
RKCooperation.getRtcEngine().logout()
RKCooperation.getRtcEngine().destroy()
```

| 参数 | 是否必填 | 说明 |
| --- | --- | --- |
| `userId` | 是 | 当前业务用户 ID。会议成员、呼叫、消息和共享状态都会基于该 ID 识别用户。 |
| `forceRefreshToken` | 否 | 是否强制刷新 RTC Token。普通登录可使用默认值，账号切换或 Token 异常时可传 `true`。 |

### 6.4 登录和长链状态监听

登录监听用于接收登录、登出以及已加入频道信息。

```kotlin
private val loginListener = object : LoginListener {
    override fun onLogin(errorCode: Int) {
        // 登录结果，错误码见 RKCooperationCode。
    }

    override fun onLogout(reason: Int) {
        // 登出原因，错误码见 RKCooperationCode。
    }

    override fun onJoinedChannel(channelList: Array<RKIJoinedChannel>) {
        // 登录后 SDK 查询到当前账号已加入的频道。
    }
}

RKCooperation.getRtcEngine().addLoginListener(loginListener)
RKCooperation.getRtcEngine().removeLoginListener(loginListener)
```

RTC 长链状态监听用于感知连接创建、状态变化、错误和释放。

```kotlin
private val clientStateListener = object : RtcClientStateListener {
    override fun onCreated() {}

    override fun onClientStateChanged(oldState: Int, newState: Int) {
        // 状态值见 ClientState。
    }

    override fun onError(errorCode: Int) {}

    override fun onDispose(reason: Int) {}
}

RKCooperation.getRtcEngine().addRtcClientStateListener(clientStateListener)
RKCooperation.getRtcEngine().removeRtcClientStateListener(clientStateListener)
```

## 7. 会议频道管理

会议频道由 `RKChannelManager` 管理。创建或加入会议后，会得到一个 `RKChannel` 会议对象，后续会议内操作都通过该对象完成。

### 7.1 创建会议

```kotlin
val channelParam = RKChannelParam().apply {
    maxMembers = 16
    frameRate = 24
    maxResolution = Resolution.RESOLUTION_720
    url = rtcUrl
    token = joinToken
    defaultSubscribeMediaType = RKSubscribeMediaType.Both
    defaultStreamType = VideoSize.SIZE_SMALL
}

val result = RKCooperation.getChannelManager().createChannel(
    channelId = null,
    channelTitle = meetingTitle,
    channelParam = channelParam
)
```

### 7.2 加入会议

```kotlin
val result = RKCooperation.getChannelManager().joinChannel(
    channelId = channelId,
    channelTitle = meetingTitle,
    channelParam = channelParam,
    timeoutSeconds = 20
)
```

### 7.3 查询会议和本地缓存

```kotlin
val queryResult = RKCooperation.getChannelManager().queryChannel(channelId)
val channel = RKCooperation.getChannelManager().getChannel(channelId)
```

### 7.4 `RKChannelParam` 常用参数

| 参数 | 说明 |
| --- | --- |
| `maxMembers` | 最大成员数，当前常用默认值为 `16`。 |
| `maxResolution` | 会议最大分辨率，例如 `RESOLUTION_360`、`RESOLUTION_720`、`RESOLUTION_1080`。 |
| `url` | RTC 服务地址，通常取当前环境的 `rtcUrl`。 |
| `token` | 加入会议使用的 Token，通常由业务后端或 Rokid OpenAPI 下发。 |
| `password` | 频道密码，不传时使用服务端或 SDK 默认策略。 |
| `frameRate` | 视频帧率。 |
| `recordParam` | 云端录制参数；需要创建会议时开启录制能力才配置。 |
| `bitrate` | 最大码率，按底层 RTC 实现约定使用。 |
| `inviteUserId` | 邀请目标用户 ID，可用于创建或加入会议时携带邀请上下文。 |
| `defaultSubscribeMediaType` | 默认订阅音视频类型，例如 `Both`、`Audio`。 |
| `defaultStreamType` | 默认拉流大小流，例如 `VideoSize.SIZE_SMALL`。 |

## 8. 会议监听与成员管理

### 8.1 会议事件监听

进入会议后，可以对 `RKChannel` 添加频道监听。

```kotlin
private val channelListener = object : RKChannelListener {
    override fun onUserJoinChannel(channelId: String, userId: String) {}

    override fun onUserLeaveChannel(channelId: String, userId: String) {}

    override fun onLeave(channelId: String, reason: Int) {}

    override fun onError(channelId: String, errorCode: Int) {}
}

channel.addChannelListener(channelListener)
channel.removeChannelListener(channelListener)
```

### 8.2 常用会议操作

```kotlin
channel.leave()
channel.stop()
channel.kickOutUser(userId)

val members = channel.getChannelParticipantList()
```

| 接口 | 说明 |
| --- | --- |
| `leave()` | 当前用户离开会议。 |
| `stop()` | 结束会议，其他成员会收到会议结束回调。 |
| `kickOutUser(userId)` | 将指定用户移出会议。 |
| `getChannelParticipantList()` | 获取会议成员列表。 |

## 9. 本地与远端设备控制

### 9.1 本地设备控制

本地摄像头、麦克风、扬声器和预览能力由本地设备对象提供。

```kotlin
val device = RKCooperation.getRtcEngine().getLocalDevice()

device.openCamera(CameraType.FRONT)
device.switchCamera()
device.closeCamera()

device.startAudio()
device.stopAudio()

device.enableSpeaker(true)
val devices = device.getAllAudioDevice()
```

采集参数配置：

```kotlin
device.setCameraProperty(width = 1280, height = 720, frameRate = 24)
device.configScreenShareProperty(width = 1280, height = 720, frameRate = 24)
```

关闭预览：

```kotlin
RKCooperation.getRtcEngine().stopPreview(closeCamera = true)
```

会议中控制本地音视频是否上传：

```kotlin
channel.enableUploadLocalAudioStream(true)
channel.enableUploadLocalVideoStream(true)
channel.enableAudioOutput(true)
```

查询状态：

```kotlin
val isAudioUpload = channel.isLocalAudioUpload()
val isVideoUpload = channel.isLocalVideoUpload()
val isCameraOpened = device.isCameraOpened()
val isAudioStart = device.isAudioStart()
```

### 9.2 远端设备监听

远端设备监听用于感知频道内其他成员的音频上传、视频上传、视频尺寸、音量等状态变化。

```kotlin
private val remoteDeviceListener = object : RKRemoteDeviceListener {
    override fun onUserUploadAudioChanged(userId: String?, enabled: Boolean) {}

    override fun onUserUploadVideoChanged(userId: String?, enabled: Boolean) {}

    override fun onUserVideoSizeChanged(userId: String?, videoSize: Int) {}

    override fun onUserVolumeChange(userId: String?, status: Int) {}
}

channel.addRemoteDeviceListener(remoteDeviceListener)
channel.removeRemoteDeviceListener(remoteDeviceListener)
```

| 回调 | 说明 |
| --- | --- |
| `onUserUploadAudioChanged` | 远端用户音频上传状态变化。 |
| `onUserUploadVideoChanged` | 远端用户视频上传状态变化。 |
| `onUserVideoSizeChanged` | 远端用户上传的视频流尺寸变化。 |
| `onUserVolumeChange` | 远端用户音量状态变化。 |

## 10. 呼叫、邀请和接听

呼叫相关能力由 `RKCooperation.getCallManager()` 提供。

### 10.1 邀请与取消

```kotlin
val result = RKCooperation.getCallManager().invite(
    channelId = channelId,
    userIdList = arrayOf(targetUserId)
)

RKCooperation.getCallManager().cancel(channelId)
```

### 10.2 接听、拒绝和忙线

```kotlin
RKCooperation.getCallManager().accept(
    channelId = channelId,
    defaultSubscribeMediaType = RKSubscribeMediaType.Both,
    defaultStreamType = VideoSize.SIZE_SMALL,
    onSuccess = {
        // 接听成功，进入会议页面。
    },
    onFailed = {
        // 接听失败。
    },
    timeoutSeconds = 10
)

RKCooperation.getCallManager().reject(channelId)
RKCooperation.getCallManager().busy(channelId)
```

### 10.3 来电监听

```kotlin
private val incomingCallListener = object : RKIncomingCallListener {
    override fun onReceiveCall(
        channelId: String,
        fromUserId: String,
        createTime: Long,
        channelTitle: String,
        channelParam: RKChannelParam?
    ) {
        // 收到来电，可展示接听 / 拒绝页面。
    }

    override fun onCallCanceled(channelId: String, fromUserId: String, createTime: Long) {
        // 对方取消来电。
    }
}

RKCooperation.getCallManager().addIncomingCall(incomingCallListener)
RKCooperation.getCallManager().removeIncomingCall(incomingCallListener)
```

## 11. 屏幕共享、白板与 AR 标注

会议共享能力统一通过 `RKChannel.getChannelShare()` 调用。

### 11.1 屏幕共享

```kotlin
val share = channel.getChannelShare()

val screenShareParam = ScreenShareParam(
    10 * 1024,
    24,
    screenWidth * screenHeight
).apply {
    width = screenWidth
    height = screenHeight
}

val state = share.startScreenShare(screenShareParam)
```

常用接口：

```kotlin
channel.getChannelShare().stopScreenShare()
channel.getChannelShare().getShareInfo()
channel.getChannelShare().addShareEventListener(shareListener)
channel.getChannelShare().removeShareEventListener(shareListener)
```

### 11.2 电子白板

```kotlin
channel.getChannelShare().startDoodle()
channel.getChannelShare().startDoodle(imageUrl)

val doodleView = channel.getChannelShare().getDoodleView()

channel.getChannelShare().stopDoodle()
```

### 11.3 视频点选、视频控制和 AR 标注

```kotlin
channel.getChannelShare().inviteSharePointVideo(userId)
channel.getChannelShare().stopInviteSharePointVideo()

channel.getChannelShare().inviteShareVideoControl(userId)
channel.getChannelShare().stopInviteShareVideoControl()

channel.getChannelShare().inviteShareSlam(userId)
```

发送 AR 标注：

```kotlin
val share = channel.getChannelShare()

share.sendArSlamArrow(center, size, slamColor)
share.sendArSlamCircle(center, radius, slamColor)
share.sendArSlamPath(points, slamColor)
share.sendArSlamSticker(center, stickerType, slamColor)
share.sendArSlamImage(center, imageUrl, scale = 0.2f)

share.undoArSlam()
share.clearArSlam()
```

## 12. 频道消息、录制、会议文件和日志

### 12.1 频道消息和自定义属性

```kotlin
channel.setCustomProperty(property)
val property = channel.getCustomProperty()

channel.sendChannelMessage(msg = message, toUserId = null)
channel.sendChannelMessage(msg = message, toUserId = targetUserId)
channel.sendChannelMessage(msg = message, toUserList = userIdList)

channel.addChannelMsgListener(channelMsgListener)
```

### 12.2 云端录制

```kotlin
RKCooperation.getChannelManager().setRecordStatusListener(recordStatusListener)

val startResult = RKCooperation.getChannelManager().startServerRecording(
    channelId = channelId,
    bucket = bucket,
    fileName = fileName,
    resolution = Resolution.RESOLUTION_720
)

val stopResult = RKCooperation.getChannelManager().stopServerRecording(
    channelId = channelId,
    save = true
)

val files = RKCooperation.getChannelManager().getServerRecordingFiles(channelId)
```

### 12.3 会议文件

```kotlin
val result = channel.uploadMeetingFile(
    localPath = localPath,
    mimeType = mimeType,
    fileName = fileName
)
```

### 12.4 视频质量和网络状态

```kotlin
channel.configVideoQuality(
    maxPublishBitrate = 2_000,
    maxDelay = 500
)

channel.setVideoQualityListener(qualityListener)

val quality = channel.getUserNetworkQuality(userId)
val streamState = channel.getUserStreamState(userId)

RKCooperation.getRtcEngine().setVideoPublishBitrate(
    bitrateMapping16to9,
    bitrateMapping4to3
)
```

### 12.5 日志上传

```kotlin
val result = RKCooperation.getRtcEngine().uploadLog()
```

建议在用户反馈问题、会议失败、音视频异常等场景引导用户触发日志上传，并记录用户账号、会议 ID 和问题时间，便于排查。

## 13. 接入注意事项

- 初始化只做一次，建议放在应用主进程。
- 登录成功前不要创建、加入或接听会议。
- 所有 `addListener` 都应在页面销毁或业务结束时对应 `removeListener`。
- 会议内共享能力统一通过 `RKChannel.getChannelShare()` 调用，不建议在业务层自行维护共享状态兜底。
- 业务侧不要直接依赖 SDK 内部实现类，应使用公开接口，例如 `RKChannel`、`RKChannelShare`、`RKCall` 等。
- Android 12 及以上需要明确处理 `android:exported`、蓝牙权限、前台服务类型等系统要求。
- Android 13 及以上如果需要通知，需要申请 `POST_NOTIFICATIONS`。
- 如果启用混淆或资源压缩，请在正式发布前完整验证登录、入会、音视频、呼叫、共享、白板和文件上传流程。
