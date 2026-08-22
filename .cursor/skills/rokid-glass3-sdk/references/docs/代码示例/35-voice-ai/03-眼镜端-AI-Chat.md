# 眼镜端 AI Chat

## 示例说明

演示眼镜端如何启动 AI Chat、发送问题，并接收流式回答结果。

## 使用位置

眼镜端首页：

- `HomeActivity` -> `SdkMediaActivity`

示例页面：

- `com.rokid.glass.SdkMediaActivity`

## 适用端

- 眼镜端

## 关键文件

- `glassdemo/app/src/main/java/com/rokid/glass/SdkMediaActivity.kt`

## 源码定位

仓库内重点查看下面这些位置：

- `glassdemo/app/src/main/java/com/rokid/glass/SdkMediaActivity.kt`
  关键位置：`binding.btnAiChat.setOnClickListener`
- `glassdemo/app/src/main/java/com/rokid/glass/SdkMediaActivity.kt`
  关键位置：`binding.btnStopAiChat.setOnClickListener`
- `glassdemo/app/src/main/java/com/rokid/glass/SdkMediaActivity.kt`
  关键回调：`mAIChaLister`

如果你要做完整终端助手，可以把这一页和下面两处源码配合着看：

- `glassdemo/app/src/main/java/com/rokid/glass/SendMessageActivity.kt`
  用来接 ASR / TTS 示例
- `glassdemo/app/src/main/java/com/rokid/glass/HomeActivity.kt`
  用来接离线语音指令示例

## 相关 API 文档

- [Glass3 SDK(眼镜端) API文档](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md)
- [快速开始](/terminal-sdk/getting-started/快速开始.md)

## 流程说明

1. 点击页面中的 AI 按钮。
2. 调用 `GlassSdk.getGlassAiChatService()?.startAiChat(false)` 启动会话。
3. 调用 `toAiChat("杭州明天下雨吗", mAIChaLister)` 发送问题。
4. 通过 `AiChatListener` 接收流式回调。
5. 结束时调用 `endAiChat()`。

## 关键代码片段

```kotlin
binding.btnAiChat.setOnClickListener {
    GlassSdk.getGlassAiChatService()?.startAiChat(false)
    GlassSdk.getGlassAiChatService()?.toAiChat(
        "杭州明天下雨吗",
        mAIChaLister
    )
}

binding.btnStopAiChat.setOnClickListener {
    GlassSdk.getGlassAiChatService()?.endAiChat()
}
```

```kotlin
private val mAIChaLister = object : AiChatListener.Stub() {
    override fun onAiChatAnswer(
        answer: String?,
        isFinish: Boolean,
        contentType: String?,
        sessionId: String?
    ) {
        L.i(TAG, "onAiChatAnswer: answer = $answer")
    }

    override fun onError(code: Int, message: String) {}
}
```

## 回调内容

示例中已经处理了这些 AI Chat 回调：

- `onContinuousModeUpdate(...)`
- `onAiChatAnswer(answer, isFinish, contentType, sessionId)`
- `onError(code, message)`
- `onAiTakePhoto(filePath)`

## 调用时序

<div class="sample-flow">
  <div class="sample-flow-step" data-step="1">
    <strong>启动会话</strong>
    <span>调用 <code>startAiChat(false)</code>。</span>
  </div>
  <div class="sample-flow-step" data-step="2">
    <strong>发送问题</strong>
    <span>通过 <code>toAiChat(question, listener)</code> 提交文本问题。</span>
  </div>
  <div class="sample-flow-step" data-step="3">
    <strong>接收片段</strong>
    <span>多次收到 <code>onAiChatAnswer(..., isFinish=false)</code>。</span>
  </div>
  <div class="sample-flow-step" data-step="4">
    <strong>结束回答</strong>
    <span>收到 <code>isFinish=true</code> 后完成本轮回答。</span>
  </div>
  <div class="sample-flow-step" data-step="5">
    <strong>关闭会话</strong>
    <span>不再使用时调用 <code>endAiChat()</code>。</span>
  </div>
</div>

## 实现说明

### 为什么 `onAiChatAnswer` 里有 `isFinish`

因为 AI 回答是流式返回的。

- `answer` 可能是一段增量文本
- `isFinish` 表示这次回答是否已经结束

如果你要做逐字展示、逐句播报或者聊天气泡增量刷新，这个回调非常关键。

### `onAiTakePhoto` 是做什么的

说明 AI Chat 不只是文本问答，还可能触发带设备能力的动作，例如拍照。这也是为什么它更像“AI 助手能力”，而不只是一个普通聊天接口。

### `sessionId` 可以怎么用

如果后续需要多轮对话、会话续接、日志追踪，`sessionId` 会很有用。当前示例里只是打印日志，但正式业务通常应该把它保留下来。

## 与 ASR/TTS 的关系

在真实产品中，AI Chat 往往会和语音能力组合使用：

- ASR 负责把用户语音转成文本问题
- AI Chat 负责理解和回答
- TTS 负责把回答再播报出来

当前仓库里，这三块示例代码分散在不同页面，但已经能串成一条完整链路。

## 推荐组合方式

| 阶段 | 推荐能力 | 当前示例位置 |
| --- | --- | --- |
| 用户输入 | 在线 ASR | `SendMessageActivity` |
| 语义理解与回答 | AI Chat | `SdkMediaActivity` |
| 播报结果 | TTS | `SendMessageActivity` |

## 注意事项

- 这个示例当前以代码调用为主，没有做完整聊天 UI。
- 如果要做终端助手，建议把 ASR、AI Chat、TTS 串成一个统一页面或统一状态机。
