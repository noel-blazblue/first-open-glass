# 眼镜端 TTS 与 ASR

## 示例说明

演示眼镜端如何调用离线 TTS、在线 ASR，以及如何注册离线语音指令。

## 使用位置

离线语音指令注册：

- `HomeActivity`

TTS / ASR 点击示例：

- `SendMessageActivity`

## 适用端

- 眼镜端

## 关键文件

- `glassdemo/app/src/main/java/com/rokid/glass/HomeActivity.kt`
- `glassdemo/app/src/main/java/com/rokid/glass/SendMessageActivity.kt`

## 源码定位

仓库内重点查看下面这些位置：

- `glassdemo/app/src/main/java/com/rokid/glass/HomeActivity.kt`
  关键位置：`initSDK()` 中的 `VoiceAction` 注册与 `GlassSdk.getGlassOfflineCmdService()?.add(...)`
- `glassdemo/app/src/main/java/com/rokid/glass/SendMessageActivity.kt`
  关键位置：`toClick()` 中的 `R.id.btTts` 和 `R.id.btAsr`
- `glassdemo/app/src/main/java/com/rokid/glass/SendMessageActivity.kt`
  关键回调：`SpeechCallback.Stub()`

如果你要快速看明白这条链路，推荐按下面顺序读源码：

1. `glassdemo/app/src/main/java/com/rokid/glass/HomeActivity.kt`
2. `glassdemo/app/src/main/java/com/rokid/glass/SendMessageActivity.kt`
3. `SpeechCallback` 的几个回调实现

## 相关 API 文档

- [Glass3 SDK(眼镜端) API文档](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md)
- [快速开始](/terminal-sdk/getting-started/快速开始.md)

## 覆盖的能力

- 离线语音指令注册
- 离线文本转语音播放
- 在线语音转文本
- ASR 结果回调与意图回调

## 关键代码片段

### 离线语音指令注册

```kotlin
jdVoiceAction = VoiceAction(
    "打开编号12的警灯",
    "da kai bian hao shi er de jing deng",
    object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            Log.e(TAG, "打开编号12的警灯")
        }
    }
)
GlassSdk.getGlassOfflineCmdService()?.add(jdVoiceAction)
```

### 离线 TTS

```kotlin
val str = "秋天不回来，我要去爬山啦"
GlassSdk.getGlassOfflineTtsService()?.playTtsMsg(str)
```

### 在线 ASR

```kotlin
GlassSdk.getGlassAsrService()?.startSpeech(object : SpeechCallback.Stub() {
    override fun onStart() {}

    override fun onIntermediateVad(content: String) {}

    override fun onAsrComplete(content: String?) {}

    override fun onAsrCompleteWithIntent(
        content: String?,
        intent: Int,
        intentJson: String
    ) {}

    override fun onError(code: Int) {}
})
```

## 流程说明

### 1. 注册离线语音指令

在 `HomeActivity` 中，SDK 初始化成功后会创建 `VoiceAction`，然后调用：

- `GlassSdk.getGlassOfflineCmdService()?.add(...)`

示例里已经注册了多条离线语音命令，例如：

- “打开编号12的警灯”
- “下雪了”
- “智涌”

### 2. 调用离线 TTS

在 `SendMessageActivity` 中，点击 TTS 示例会调用：

- `GlassSdk.getGlassOfflineTtsService()?.playTtsMsg(str)`

代码里还保留了在线 TTS 调用位置，但当前示例实际执行的是离线 TTS。

### 3. 调用在线 ASR

在 `SendMessageActivity` 中，点击 ASR 示例会调用：

- `GlassSdk.getGlassAsrService()?.startSpeech(...)`

并通过 `SpeechCallback` 监听以下事件：

- `onStart()`
- `onIntermediateVad(content)`
- `onAsrComplete(content)`
- `onAsrCompleteWithIntent(content, intent, intentJson)`
- `onError(code)`
- `onServiceConnectState(connect)`

## 调用时序

<div class="sample-flow">
  <div class="sample-flow-step" data-step="1">
    <strong>触发识别</strong>
    <span>眼镜端页面调用 <code>startSpeech(callback)</code>。</span>
  </div>
  <div class="sample-flow-step" data-step="2">
    <strong>开始监听</strong>
    <span>识别服务通过 <code>onStart()</code> 返回启动状态。</span>
  </div>
  <div class="sample-flow-step" data-step="3">
    <strong>返回中间结果</strong>
    <span>用户说话过程中回调 <code>onIntermediateVad(content)</code>。</span>
  </div>
  <div class="sample-flow-step" data-step="4">
    <strong>返回最终结果</strong>
    <span>识别结束后回调最终文本和意图结果。</span>
  </div>
</div>

## 离线与在线能力对比

| 项目 | 离线语音指令 | 在线 ASR | 离线 TTS |
| --- | --- | --- | --- |
| 主要用途 | 命令触发 | 自由语音转文本 | 文本播报 |
| 网络依赖 | 无 | 有 | 无 |
| 结果形式 | 触发回调 | 中间结果 + 最终文本 + 意图 | 语音输出 |
| 当前示例位置 | `HomeActivity` | `SendMessageActivity` | `SendMessageActivity` |
| 更适合的场景 | 高优先级控制命令 | 字幕、问答、复杂语义 | 本地反馈、提示音播报 |

## 实现说明

### 离线 TTS 和在线 TTS 在示例里是什么关系

当前示例优先展示的是离线 TTS，因为它更容易本地验证。

在线 TTS 的调用位置也已经预留，只是代码里暂时注释掉了。完成 API Key 配置后，可以把在线 TTS 补成正式示例。

### `onIntermediateVad` 和 `onAsrComplete` 有什么区别

- `onIntermediateVad`：识别过程中的中间结果
- `onAsrComplete`：识别完成后的最终文本

如果你的业务是实时字幕，更适合用前者；如果是命令识别或提交结果，更适合以后者为准。

### `onAsrCompleteWithIntent` 适合做什么

这个回调适合做“语音 + 意图”的联动场景，例如：

- 识别到文本
- 同时拿到语义意图编号
- 直接触发业务动作

### 离线语音指令和在线 ASR 应该怎么分工

一个很实用的做法是：

- 高频固定命令走离线语音指令
- 开放式表达走在线 ASR

这样既能保证关键命令低延迟，也能保留自然语言交互能力。

## 推荐落地方式

- 设备控制类命令：优先离线语音指令
- 搜索、问答、转写：优先在线 ASR
- 状态播报、提示反馈：优先离线 TTS

## 注意事项

- 示例中的在线 ASR 依赖在线语音鉴权配置。
- 示例代码里明确提示了一个真实使用注意点：语音播报时尽量不要同时开投屏，否则声音路由可能跑到投屏设备上。
- 离线语音指令适合做高频、低延迟、弱网络依赖的本地控制。
