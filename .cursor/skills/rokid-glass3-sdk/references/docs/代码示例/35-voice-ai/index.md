# 语音与 AI

这一组文档覆盖手机端在线 ASR/TTS 初始化、眼镜端 TTS/ASR，以及眼镜端 AI Chat 相关示例。

## 包含哪些示例

- [手机端 SDK 初始化（ASR/TTS）](./01-%E6%89%8B%E6%9C%BA%E7%AB%AF-SDK-%E5%88%9D%E5%A7%8B%E5%8C%96%EF%BC%88ASR-TTS%EF%BC%89.md)
- [眼镜端 TTS 与 ASR](./02-%E7%9C%BC%E9%95%9C%E7%AB%AF-TTS-%E4%B8%8E-ASR.md)
- [眼镜端 AI Chat](./03-%E7%9C%BC%E9%95%9C%E7%AB%AF-AI-Chat.md)

## 推荐阅读顺序

1. 先看手机端 SDK 初始化，确认在线语音能力从哪里开启。
2. 再看眼镜端 TTS 与 ASR，理解离线命令、离线 TTS 和在线 ASR 的边界。
3. 最后看眼镜端 AI Chat，把 ASR、AI 和 TTS 串成完整链路。

## 能力关系

| 能力 | 主要作用 | 当前示例位置 |
| --- | --- | --- |
| 离线语音指令 | 本地命令触发 | `HomeActivity` |
| 离线 TTS | 本地播报文本 | `SendMessageActivity` |
| 在线 ASR | 语音转文本 | `SendMessageActivity` |
| 在线 TTS | 文本转语音 | 手机端初始化中预留，眼镜端调用位已预留 |
| AI Chat | 理解问题并流式回答 | `SdkMediaActivity` |

## 一条完整链路长什么样

<div class="sample-flow">
  <div class="sample-flow-step" data-step="1">
    <strong>语音输入</strong>
    <span>用户对眼镜说话，眼镜端触发 ASR。</span>
  </div>
  <div class="sample-flow-step" data-step="2">
    <strong>转成文本</strong>
    <span>ASR 返回中间结果和最终文本。</span>
  </div>
  <div class="sample-flow-step" data-step="3">
    <strong>发送问题</strong>
    <span>眼镜端把文本问题发送给 AI Chat。</span>
  </div>
  <div class="sample-flow-step" data-step="4">
    <strong>流式回答</strong>
    <span>AI Chat 按片段返回回答内容。</span>
  </div>
  <div class="sample-flow-step" data-step="5">
    <strong>语音播报</strong>
    <span>TTS 将回答内容播报给用户。</span>
  </div>
</div>
