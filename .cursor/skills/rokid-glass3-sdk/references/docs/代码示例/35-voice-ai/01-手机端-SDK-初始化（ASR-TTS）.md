# 手机端 SDK 初始化（ASR/TTS）

## 示例说明

演示手机端如何在 SDK 初始化阶段启用在线语音转文本和文本转语音能力，并说明接入时需要补齐的鉴权参数。

## 使用位置

手机端首页：

- `MainPhoneActivity`

核心初始化方法：

- `initSDK()`

## 适用端

- 手机端

## 关键文件

- `glass3sdkphonedemo/app/src/main/java/com/rokid/phone/ui/MainPhoneActivity.kt`

## 源码定位

仓库内重点查看下面这些位置：

- `glass3sdkphonedemo/app/src/main/java/com/rokid/phone/ui/MainPhoneActivity.kt`
  关键方法：`initSDK()`
- `glass3sdkphonedemo/app/src/main/java/com/rokid/phone/ui/MainPhoneActivity.kt`
  关键对象：`UserAuthInfo`、`EngineParam`、`NetServiceType`

如果你后续要继续扩展手机端语音调试页，建议从 `glass3sdkphonedemo/app/src/main/java/com/rokid/phone/ui/MainPhoneActivity.kt` 往下追踪 SDK 初始化成功后的业务分发逻辑。

## 相关 API 文档

- [Glass3 SDK(手机端) API文档](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md)
- [快速开始](/terminal-sdk/getting-started/快速开始.md)

## 流程说明

1. 创建客户端 ID 列表：`SecurityPhone` 和 `GlassSample`。
2. 构造 `UserAuthInfo`，这里需要填入在线 ASR/TTS 对应的 API Key 鉴权信息。
3. 构造 `EngineParam`。
4. 通过 `PSecuritySDK.getMobileEngineService().initSDK(param)` 初始化手机端引擎服务。
5. 初始化成功后，再继续设备连接、消息监听和状态同步。

## 关键代码片段

```kotlin
private fun initSDK() {
    lifecycleScope.launch {
        val clientIds = arrayListOf("SecurityPhone", "GlassSample")

        // 这里需要替换成真实的 API Key 鉴权信息
        val userAuthInfo = UserAuthInfo("", "")

        // 当前示例不初始化翻译服务，保留 ASR/TTS
        val banServiceList: List<NetServiceType> =
            arrayListOf(NetServiceType.TranslateService)

        val param = EngineParam(
            clientIds = clientIds,
            userAuthInfo = userAuthInfo,
            banServiceList = banServiceList,
            envType = EnvType.Companion.PUBLIC
        )

        PSecuritySDK.getMobileEngineService().initSDK(param) {
            if (it.isSuccess) {
                GlobalData.setSdkInitState(it.isSuccess)
            }
        }
    }
}
```

## 初始化时序

<div class="sample-flow">
  <div class="sample-flow-step" data-step="1">
    <strong>准备参数</strong>
    <span>手机端组装 <code>EngineParam</code>、<code>clientId</code> 和 API Key 鉴权信息。</span>
  </div>
  <div class="sample-flow-step" data-step="2">
    <strong>初始化 SDK</strong>
    <span>调用 <code>initSDK(EngineParam)</code>。</span>
  </div>
  <div class="sample-flow-step" data-step="3">
    <strong>建立在线能力</strong>
    <span>SDK 使用鉴权信息初始化在线语音服务。</span>
  </div>
  <div class="sample-flow-step" data-step="4">
    <strong>接收结果</strong>
    <span>初始化成功后继续连接设备或注册监听。</span>
  </div>
</div>

## 关键代码要点

### clientId 配置

手机端和眼镜端需要共享业务侧约定的 clientId，示例里是：

- `SecurityPhone`
- `GlassSample`

### 在线语音鉴权

当前示例代码里仍然是占位值：

```kotlin
val userAuthInfo = UserAuthInfo("", "")
```

真正接入在线 ASR/TTS 时，需要替换成商务申请下来的 API Key 鉴权信息。

### 服务初始化范围

示例里没有禁用 ASR/TTS，而是禁用了翻译服务：

```kotlin
val banServiceList: List<NetServiceType> = arrayListOf(NetServiceType.TranslateService)
```

这意味着当前初始化目标是：

- 初始化在线语音转文本
- 初始化在线文本转语音
- 不初始化翻译服务

## 实现说明

### 为什么这里只看到初始化，没有单独的手机端 ASR/TTS 页面

当前仓库里，手机端主要把在线语音能力准备工作放在了 SDK 初始化阶段，真正可直接点击验证的 ASR/TTS 页面示例更多在眼镜端。

所以这篇文档的价值主要在于说明：

- 在线语音能力从哪里打开
- API Key 应该在哪里配置
- clientId 怎样和眼镜端保持一致

### 如果 API Key 没填会怎样

SDK 初始化本身可能仍然成功，但涉及在线 ASR/TTS 的实际调用时会失败，或者拿不到可用结果。

### 为什么要在初始化时就决定 banServiceList

因为这里相当于声明“本次要拉起哪些在线能力”。当前示例明确保留了 ASR/TTS，把翻译服务排除掉，方便调试语音链路本身。

## 接入清单

- 向商务申请在线语音能力 API Key
- 保证手机端和眼镜端约定同一组 clientId
- 在初始化成功后再触发依赖在线语音的页面或业务
- 给错误态补充更明确的 UI 提示，避免开发期误判为 SDK 初始化失败

## 注意事项

- `UserAuthInfo` 不能继续保持空值用于正式环境。
- 手机端和眼镜端必须约定一致的 clientId。
- 如果后续需要单独的手机端语音调试页，建议在这个初始化逻辑之上再补一个独立 Demo 页面。
