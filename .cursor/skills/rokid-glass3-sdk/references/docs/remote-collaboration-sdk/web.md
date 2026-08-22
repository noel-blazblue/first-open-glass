# 远程协作 SDK（Web 端）接入

远程协作 Web SDK 用于在 Web 页面中集成远程协作会议能力。开发者可以在业务后台、调度台或专家工作台中实现会议邀请、音视频通话、设备控制、屏幕共享、电子白板、视频点选、视频控制、文件上传与下载等功能。

## 1. 适用场景

- 在 Web 管理后台中接入远程协作会议入口。
- 构建专家坐席、调度台或远程协助工作台。
- 在浏览器中接收眼镜或移动端发起的会议邀请。
- 管理会议成员、联系人状态、摄像头、麦克风和音视频流。
- 在会议中使用冻屏标注、电子白板、视频点选、视频控制、屏幕共享和文件上传。

## 2. 接入前准备与引入 SDK

### 2.1 接入前准备清单

正式接入前，建议先确认下面这些事项。Web SDK 初始化、WebSocket 信令连接和浏览器媒体能力都依赖这些前置条件。

| 准备项 | 用途 | 获取或确认方式 |
| --- | --- | --- |
| 远程协作能力开通 | 确认企业账号具备远程协作能力。 | 联系 Rokid 项目经理、销售或交付同事确认。 |
| npm 仓库访问 | 用于安装 `rokid-xpert-sdk`。 | 确认开发环境可以访问 Rokid npm 源。 |
| 登录态 `token` | SDK 初始化参数，用于用户身份校验。 | 由接入方业务登录态或后端服务提供。 |
| `saasUrl` | SDK 初始化参数，用于远程协作服务请求。 | 由项目环境配置提供。 |
| `saasWssUrl` | 可选参数，用于 WebSocket 信令连接。 | 如项目环境有单独配置，按实际环境填写。 |
| `rtcConfig` | 可选参数，用于 RTC ICE Server 和 WebSocket 配置。 | 如项目环境有单独配置，按实际环境填写。 |
| 浏览器媒体权限 | 摄像头、麦克风、屏幕共享等能力需要。 | 用户首次使用时由浏览器授权，业务侧需要处理拒绝授权的提示。 |
| HTTPS 或本地调试环境 | 浏览器媒体能力通常要求安全上下文。 | 正式环境使用 HTTPS，本地调试可使用 `localhost`。 |

如果页面只需要接收信令消息、不需要摄像头和麦克风，可以使用 `onlyHttp`；开启后不能使用媒体设备能力。

### 2.2 账号与鉴权说明

远程协作 Web SDK 接入手册没有要求在浏览器中配置平台 OpenAPI 的 `API_KEY`。Web 端需要准备当前登录用户的业务 `token`、远程协作服务地址，以及项目可用的 WebSocket / RTC 配置；这些通常来自接入方业务登录态、后端服务或项目环境配置。

如果业务后端还需要调用平台 OpenAPI 查询会议记录、参会人、会议文件、IM 消息或录制列表，才需要按平台 OpenAPI 文档配置服务端鉴权。相关密钥只应保存在服务端，不要写入前端代码，也不要暴露到浏览器。

### 2.3 引入 SDK

Web SDK 包名为 `rokid-xpert-sdk`。

如果工程需要配置 npm 源，请使用项目提供的 Rokid npm 源地址：

```bash
npm config set registry https://maven.rokid.com/repository/npm-group/
npm install rokid-xpert-sdk
```

安装后在业务代码中引入：

```ts
import xpertSdk from 'rokid-xpert-sdk'
```

## 3. 初始化与生命周期

初始化会完成 Token 有效性校验、用户信息获取、WebSocket 信令服务连接注册和设备信息上传等动作。

```ts
await xpertSdk.initConfig({
  token,
  saasUrl
})
```

### 3.1 初始化参数

| 参数 | 是否必填 | 说明 |
| --- | --- | --- |
| `token` | 是 | 登录态用户 Token。 |
| `saasUrl` | 是 | 远程协作服务地址。 |
| `saasWssUrl` | 否 | WebSocket 信令服务地址。 |
| `rtcConfig.iceServers` | 否 | RTC ICE Server 配置。 |
| `rtcConfig.wssUrl` | 否 | RTC WebSocket 地址。 |
| `forceRefreshToken` | 否 | RTC 内部 Token 过期时是否强制刷新，默认 `true`。 |
| `consoleLog` | 否 | 是否开启控制台日志。 |
| `showVersion` | 否 | 是否输出 SDK 版本信息。 |
| `supportGuest` | 否 | 值为 `1` 时表示支持游客访问。 |
| `onlyHttp` | 否 | 为 `true` 时只使用 HTTP 能力，不能使用媒体设备功能，但可以接收 WebSocket 消息。 |

对应类型：

```ts
type XPertCoreArgv = {
  token: string
  saasUrl: string
  saasWssUrl?: string
  rtcConfig?: {
    iceServers: IceServer[]
    wssUrl: string
  }
  forceRefreshToken?: boolean
  consoleLog?: boolean
  showVersion?: boolean
  supportGuest?: number
  onlyHttp?: boolean
}

type IceServer = {
  userName: string | null
  password: string | null
  urls: string[]
}
```

### 3.2 全局接口

| 接口 | 说明 |
| --- | --- |
| `initConfig(params)` | 初始化 SDK。 |
| `refreshToken(token)` | 刷新 SDK 内部 Token。 |
| `setLanguage(lang)` | 切换语言，`lang` 可传 `zh` 或 `en`。 |
| `destroy()` | 销毁 SDK，释放 WebSocket 和媒体资源。 |

```ts
xpertSdk.refreshToken(token)
xpertSdk.setLanguage('zh')
xpertSdk.destroy()
```

### 3.3 全局事件

```ts
xpertSdk.on('logout', () => {
  // 被抢登、Token 失效等场景会触发。
})
```

### 3.4 最小验证路径

首次接入时，建议先按最小路径验证：

1. npm 可以成功安装 `rokid-xpert-sdk`。
2. 页面可以在 HTTPS 或 `localhost` 环境中运行。
3. `initConfig` 初始化成功，未触发 `logout`。
4. 可以收到 `contacts` 联系人列表事件。
5. `cameraCheck`、`microphoneCheck` 返回正常或能给出明确权限提示。
6. 可以收到会议邀请或进入一个测试会议。

## 4. 核心模块

`xpertSdk` 是 SDK 的主实例，主要包含四类模块。

| 模块 | 说明 |
| --- | --- |
| `userManager` | 用户和联系人管理。 |
| `deviceManager` | 摄像头、麦克风、扬声器、分辨率、大小流和设备检查。 |
| `meetingManager` | 会议邀请、会议生命周期、成员状态和会议内消息通知。 |
| `extendManager` | 冻屏标注、电子白板、视频点选、视频控制、AR 标注、屏幕共享和文件管理。 |

## 5. 用户模块：`UserManager`

用户模块用于维护当前用户信息、联系人列表和联系人状态。

### 5.1 用户信息字段

当前登录用户常见字段：

| 字段 | 说明 |
| --- | --- |
| `userId` | 用户 ID。 |
| `realName` | 用户真实姓名或展示名称。 |
| `userName` | 用户名。 |
| `companyId` | 企业 ID。 |
| `companyName` | 企业名称。 |
| `avatar` | 头像地址。 |
| `phone` / `phoneNum` | 手机号。 |
| `unitName` | 部门或组织名称。 |

联系人常见字段：

| 字段 | 说明 |
| --- | --- |
| `userId` | 联系人用户 ID。 |
| `userName` / `username` | 用户名。 |
| `realName` | 联系人真实姓名或展示名称。 |
| `status` | 联系人在线状态。 |
| `deviceType` | 设备类型。 |
| `headPortrait` | 头像地址。 |
| `phoneNum` / `phoneNumber` | 手机号。 |
| `postName` | 岗位名称。 |
| `unitName` | 部门或组织名称。 |
| `tagName` / `tagStatus` | 专家标签和标签状态。 |
| `guestFlag` | 是否游客，`0` 表示普通租户用户，`1` 表示游客。 |
| `personType` | 人员类型，常见值：`1` 正常用户，`2` IPC 用户。 |

### 5.2 用户模块接口

| 接口 | 说明 |
| --- | --- |
| `updateToken(token)` | 更新用户模块内部 Token。 |
| `getUserInfo(userId)` | 根据用户 ID 获取联系人信息。 |
| `destroy()` | 销毁用户模块，释放用户相关 WebSocket 链接。 |

```ts
xpertSdk.userManager.updateToken(token)

const user = xpertSdk.userManager.getUserInfo(userId)

xpertSdk.userManager.destroy()
```

### 5.3 联系人事件

```ts
xpertSdk.userManager.on('contacts', (data) => {
  // data 为最新联系人列表，包含联系人上下线状态。
})
```

## 6. 设备模块：`DeviceManager`

设备模块用于管理摄像头、麦克风、扬声器、画质和音视频流。除 `cameraCheck`、`microphoneCheck` 外，其他设备接口通常需要在会议中调用。

### 6.1 设备检查

```ts
const cameraStatus = await xpertSdk.deviceManager.cameraCheck()
const microphoneStatus = await xpertSdk.deviceManager.microphoneCheck()
```

`DeviceCheckResult` 常见取值：

| 取值 | 说明 |
| --- | --- |
| `NORMAL` | 设备正常。 |
| `DEVICE_PERMISSION_DENIED` | 浏览器未授予设备权限。 |
| `DEVICE_NOT_FOUND` | 未找到对应设备。 |
| `DEVICE_UNKNOWN_ERROR` | 未知设备错误。 |

### 6.2 音视频设备控制

| 接口 | 说明 |
| --- | --- |
| `startCamera(restart?, deviceId?)` | 开启摄像头，可指定设备 ID。 |
| `stopCamera(restart?, deviceId?)` | 关闭摄像头，可指定设备 ID。 |
| `startMicrophone(restart?, deviceId?)` | 开启麦克风，可指定设备 ID。 |
| `stopMicrophone(restart?)` | 关闭麦克风。 |
| `switchAudioOutput(enable)` | 开关扬声器。 |
| `toggleFrontCamera(isFront?)` | 前置、后置摄像头切换。 |

```ts
await xpertSdk.deviceManager.startCamera()
await xpertSdk.deviceManager.stopCamera()

await xpertSdk.deviceManager.startMicrophone()
await xpertSdk.deviceManager.stopMicrophone()

xpertSdk.deviceManager.switchAudioOutput(true)
await xpertSdk.deviceManager.toggleFrontCamera(true)
```

### 6.3 画质与流控制

| 接口 | 说明 |
| --- | --- |
| `selectVideoConstraints(value)` | 切换分辨率，例如 `360P`、`720P`、`1080P`。 |
| `switchStream(userId, isHighStream)` | 指定用户大小流切换。 |
| `getStreamInfo(userId)` | 获取指定用户的音视频流信息，会议外调用无效。 |
| `setPictureMode(userId, mode)` | 设置画质模式，`0` 表示流畅，`1` 表示高清。 |

```ts
await xpertSdk.deviceManager.selectVideoConstraints('720P')
await xpertSdk.deviceManager.switchStream(userId, true)
await xpertSdk.deviceManager.setPictureMode(userId, 1)

const streamInfo = await xpertSdk.deviceManager.getStreamInfo(userId)
```

### 6.4 设备事件

```ts
xpertSdk.deviceManager.on('microphone-change', (deviceList) => {
  // 麦克风设备列表变化。
})

xpertSdk.deviceManager.on('camera-change', (deviceList) => {
  // 摄像头设备列表变化。
})

xpertSdk.deviceManager.on('video-mode', ({ userId, mode }) => {
  // 成员画质模式变化。
})
```

## 7. 会议模块：`MeetingManager`

会议模块用于接收会议邀请、会议生命周期、成员状态、媒体状态、IM 消息和 SDK 重连事件。

### 7.1 收到会议邀请

```ts
xpertSdk.meetingManager.on('invite', (data) => {
  // data: { userId, meetingId, meetingName, maxResolution }
})
```

| 字段 | 说明 |
| --- | --- |
| `userId` | 发送邀请者 ID。 |
| `meetingId` | 会议 ID。 |
| `meetingName` | 会议名称。 |
| `maxResolution` | 会议最大分辨率。 |

### 7.2 会议开始

```ts
xpertSdk.meetingManager.on('meeting-start', (meetingInfo) => {
  // meetingInfo 为会议内状态信息。
})
```

`MeetingLife` 常见字段：

| 字段 | 说明 |
| --- | --- |
| `meetingId` | 会议 ID。 |
| `members` | 会议成员列表。 |
| `moderator` | 主持人用户 ID。 |
| `speaker` | 扬声器开关状态。 |
| `meetingMuted` | 是否全体禁言。 |
| `isRecord` | 是否正在录制。 |
| `shareInfo` | 会议内分享状态。 |

`shareInfo` 常见字段：

| 字段 | 说明 |
| --- | --- |
| `shareType` | 分享类型。 |
| `promoterUserId` | 分享发起人用户 ID。 |
| `executorUserId` | 分享执行者用户 ID。 |
| `shareImageUrl` | 分享图片地址，可能为空。 |

### 7.3 成员状态事件

```ts
xpertSdk.meetingManager.on('remote-join', (data) => {
  // 远端成员加入会议。
})

xpertSdk.meetingManager.on('remote-leave', (data) => {
  // 远端成员离开会议。
})

xpertSdk.meetingManager.on('remote-refuse', (data) => {
  // 远端成员拒绝会议邀请。
})
```

| 事件 | 说明 |
| --- | --- |
| `remote-join` | 远端成员加入会议，返回 `userId`、最新 `members` 和 `joinType`。 |
| `remote-leave` | 远端成员离开会议，返回 `userId` 和最新 `members`。 |
| `remote-refuse` | 远端成员拒绝邀请，返回拒绝人员 `userId`。 |

### 7.4 会议状态事件

```ts
xpertSdk.meetingManager.on('busy-invite', (data) => {
  // 会议中收到其他会议邀请。
})

xpertSdk.meetingManager.on('meeting-end', ({ type }) => {
  // type: close | leave
})

xpertSdk.meetingManager.on('meeting-muted', ({ userId }) => {
  // 全员静音或禁言事件。
})
```

### 7.5 媒体状态、IM 与重连

```ts
xpertSdk.meetingManager.on('media-status', ({ userId, mediaStatus }) => {
  // mediaStatus 只包含发生变化的媒体状态。
})

xpertSdk.meetingManager.on('im-message', (data) => {
  // 会议内 IM 消息。
})

xpertSdk.meetingManager.on('sdk-reconnect', ({ status, meetingId }) => {
  // status: start | success
})
```

`mediaStatus` 常见字段：

| 字段 | 说明 |
| --- | --- |
| `audio` | 麦克风开关状态。 |
| `video` | 摄像头开关状态。 |
| `netQuality` | 网络质量，常见值：`0` 未知、`1` 优秀、`2` 好、`3` 差。 |

## 8. 扩展模块：`ExtendManager`

扩展模块用于管理会议内分享和协作能力。

| 属性 | 说明 |
| --- | --- |
| `shareDoodleManage` | 冻屏标注和电子白板。 |
| `shareVideoDrawManage` | 视频点选。 |
| `shareVideoControlManage` | 视频控制。 |
| `shareARManage` | AR 标注。 |
| `shareScreenManage` | 屏幕共享。 |
| `fileManage` | 文件上传与管理。 |

## 9. 冻屏标注 / 电子白板：`ShareDoodleManage`

### 9.1 初始化与参数设置

```ts
xpertSdk.extendManager.shareDoodleManage.addDoodle(canvasName, {
  canvasHeight,
  canvasWidth,
  panColor
})

xpertSdk.extendManager.shareDoodleManage.setDoodleParams({
  panColor: '#1677ff',
  panSize: 4
})
```

| 接口 | 说明 |
| --- | --- |
| `addDoodle(canvasName, options)` | 初始化冻屏标注能力，传入 canvas 名称和画布配置。 |
| `setDoodleParams(params)` | 设置画笔颜色和画笔宽度。 |

### 9.2 开始、加入和结束标注

```ts
await xpertSdk.extendManager.shareDoodleManage.startShareDoodle(
  meetingId,
  doodleImageUrl
)

await xpertSdk.extendManager.shareDoodleManage.joinShareDoodle(meetingId)

await xpertSdk.extendManager.shareDoodleManage.stopShareDoodle(meetingId)
```

| 接口 | 说明 |
| --- | --- |
| `startShareDoodle(meetingId, doodleImageUrl, domain?, replaceDomain?)` | 开启白板或截图标注；`doodleImageUrl` 为空时开启白板标注，有值时开启截图标注。 |
| `joinShareDoodle(meetingId)` | 根据会议 ID 加入标注。 |
| `stopShareDoodle(meetingId)` | 结束标注。 |

### 9.3 标注操作

```ts
xpertSdk.extendManager.shareDoodleManage.revoke(userId)
xpertSdk.extendManager.shareDoodleManage.clearAll()

const base64 = await xpertSdk.extendManager.shareDoodleManage.save()
const bg = await xpertSdk.extendManager.shareDoodleManage.generateDoodleBg(videoId)
```

| 接口 | 说明 |
| --- | --- |
| `revoke(userId?)` | 撤销一笔标注。 |
| `clearAll()` | 清空全部标注。 |
| `save()` | 以图片形式保存冻屏画面，返回 base64。 |
| `generateDoodleBg(videoId)` | 根据视频标签 ID 生成当前画面图片。 |

### 9.4 标注事件

```ts
xpertSdk.extendManager.shareDoodleManage.on('doodle', ({ msg, action }) => {
  // action: start | end
})
```

`msg.message.msgBody` 中的 `actionType` 常见值：`0` 新增、`1` 撤销、`2` 清除。

## 10. 视频点选：`ShareVideoDrawManage`

```ts
xpertSdk.extendManager.shareVideoDrawManage.addVideoDraw(canvasName, {
  deviceRatio,
  canvasHeight,
  canvasWidth,
  brushWidth,
  brushColor,
  sdkRender
})

xpertSdk.extendManager.shareVideoDrawManage.setVideoDrawParams({
  brushWidth: 4,
  color: 0xff0000
})

await xpertSdk.extendManager.shareVideoDrawManage.beginVideoPoint(
  executorUserId,
  promoterUserId
)

await xpertSdk.extendManager.shareVideoDrawManage.endVideoPoint(
  executorUserId,
  promoterUserId
)
```

| 接口 | 说明 |
| --- | --- |
| `addVideoDraw(canvasName, options)` | 初始化视频点选能力。 |
| `setVideoDrawParams(params)` | 设置点选画笔参数。 |
| `beginVideoPoint(executorUserId, promoterUserId)` | 开始视频点选。 |
| `endVideoPoint(executorUserId, promoterUserId)` | 结束视频点选。 |

事件：

```ts
xpertSdk.extendManager.shareVideoDrawManage.on('videopoint', ({ msg, action }) => {
  // action: start | end
})

xpertSdk.extendManager.shareVideoDrawManage.on('backVideoPoints', (nowClickList) => {
  // 当 addVideoDraw 未开启 sdkRender 时，可由业务层自行渲染点选列表。
})
```

`msg.message.msgBody` 中的 `actionType` 常见值：`0` 新的点、`1` 建立连接请求、`2` 建立连接响应。

## 11. 视频控制：`ShareVideoControlManage`

```ts
xpertSdk.extendManager.shareVideoControlManage.addVideoControl(canvasName, {
  deviceRatio,
  canvasHeight,
  canvasWidth
})

xpertSdk.extendManager.shareVideoControlManage.beginVideoControl(currUserId)
xpertSdk.extendManager.shareVideoControlManage.endVideoControl()

xpertSdk.extendManager.shareVideoControlManage.sendVideoControl(
  ctrType,
  currUserId,
  scale,
  pointF
)

const image = await xpertSdk.extendManager.shareVideoControlManage.save(videoId)
```

| 接口 | 说明 |
| --- | --- |
| `addVideoControl(canvasName, options)` | 初始化视频控制能力。 |
| `beginVideoControl(currUserId)` | 开始视频控制。 |
| `endVideoControl()` | 结束视频控制。 |
| `sendVideoControl(ctrType, currUserId?, scale?, pointF?)` | 发送视频控制通知。 |
| `save(videoId)` | 保存视频控制画面，返回 `base64` 和 `blob`。 |

事件：

```ts
xpertSdk.extendManager.shareVideoControlManage.on('videocontroll', ({ msg, action }) => {
  // 视频控制相关动作。
})
```

`msg.message.msgBody.ctrType` 常见含义：`2` 打开闪光灯、`4` 关闭闪光灯、`6` 放大缩小。

## 12. 屏幕共享：`ShareScreenManage`

```ts
await xpertSdk.extendManager.shareScreenManage.startShare('720P')
await xpertSdk.extendManager.shareScreenManage.stopShare()
```

| 接口 | 说明 |
| --- | --- |
| `startShare(constraints?)` | 开启屏幕共享，`constraints` 可传 `360P`、`480P`、`720P`、`1080P`。 |
| `stopShare()` | 结束屏幕共享。 |

事件：

```ts
xpertSdk.extendManager.shareScreenManage.on('start-share', ({ userId, shareInfo }) => {
  // userId 为发起屏幕共享的人。
})

xpertSdk.extendManager.shareScreenManage.on('stop-share', ({ userId, shareInfo }) => {
  // userId 为结束屏幕共享的人。
})
```

## 13. 文件上传与管理：`FileManage`

文件管理能力通过 `xpertSdk.extendManager.fileManage` 使用。默认支持的文件格式包括：`image/*`、`video/mp4`、`video/mpeg`、`.mov`、`.mkv`、`application/pdf`。

### 13.1 文件接口

| 接口 | 说明 |
| --- | --- |
| `formats` | 当前支持上传的文件格式列表。 |
| `meetingLife` | 当前会议状态信息。 |
| `setFormats(formats)` | 设置支持上传的文件格式。 |
| `selectFile()` | 打开文件选择并上传。 |
| `getMeetingFiles(meetingId)` | 获取指定会议下的上传文件。 |
| `batchDownload({ files, packageName })` | 批量下载文件。 |

```ts
xpertSdk.extendManager.fileManage.setFormats([
  'image/*',
  'video/mp4',
  'application/pdf'
])

await xpertSdk.extendManager.fileManage.selectFile()

const files = await xpertSdk.extendManager.fileManage.getMeetingFiles(meetingId)

await xpertSdk.extendManager.fileManage.batchDownload({
  files,
  packageName: 'meeting-files'
})
```

批量下载文件项结构：

| 字段 | 说明 |
| --- | --- |
| `key` | 文件名称字段，例如 `fileName`。 |
| `type` | 文件类型字段，例如 `fileContentType`。 |
| `value` | 文件地址字段，例如 `fileUrl`。 |

### 13.2 文件上传事件

```ts
xpertSdk.extendManager.fileManage.on('file-upload', ({ message, fromUserId }) => {
  // message 为文件信息，fromUserId 为上传者 ID。
})
```

## 14. 接入注意事项

- 初始化前需要准备有效用户 Token、远程协作服务地址和必要的 WebSocket / RTC 配置。
- 如果页面只需要接收信令消息，可以使用 `onlyHttp`；但开启后不能使用摄像头、麦克风等媒体设备能力。
- 设备控制、流切换、视频点选、视频控制等能力通常需要在会议中调用。
- 页面卸载、用户退出或切换账号时，需要调用 `destroy()` 释放 WebSocket 和媒体资源。
- 所有事件监听建议在页面销毁时统一移除，避免重复订阅导致 UI 多次响应。
- 会议记录、参会人、会议文件、IM 消息和录制列表查询请参考 [平台 OpenAPI：远程协作](/openapi/远程协作.md)。
