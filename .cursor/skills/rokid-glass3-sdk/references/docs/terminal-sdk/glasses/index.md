# 眼镜端 SDK

眼镜端 SDK 面向运行在 Rokid Glass3 设备上的应用。它更接近“设备侧能力层”，负责媒体采集、语音、识别、消息收发、设备状态、蓝牙、P2P 等能力。

## 你应该什么时候看这里

| 场景 | 建议入口 |
| --- | --- |
| 需要在眼镜端 App 中初始化 SDK | [初始化与连接](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#api-init) |
| 需要拍照、录像、录音或处理媒体能力 | [相机与媒体](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#media-capabilities) |
| 需要和手机端互发消息或文件 | [消息与文件](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#messaging-file-transfer) |
| 需要接入 ASR、TTS 或 AI 问答 | [语音与 AI](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#voice-ai) |
| 需要人脸、车牌或其他识别能力 | [识别能力](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#visual-recognition-capabilities) |

## 接入顺序

1. 先读 [SDK 总览](../getting-started/接入指南.md)，确认眼镜端 SDK 与手机端 SDK 的职责边界。
2. 按 [快速开始](../getting-started/快速开始.md) 完成环境准备和依赖配置；如需跑通官方 Demo，进入 [Demo 运行指南](/downloads/demo-guide.md)。
3. 从 [代码示例](/downloads/samples.md) 找到相近场景，再回到本页 API 参考确认接口细节。

## API 参考

| 模块 | 适合查什么 |
| --- | --- |
| [接入与初始化](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#api-init) | SDK 绑定、客户端注册、初始化状态和释放。 |
| [服务入口](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#glasssdk-service-entry-table) | 通过 `GlassSdk` 获取各类能力服务。 |
| [通用信息与离线指令](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#common-info-interfaces) | 用户信息、伴生端信息、离线语音指令配置。 |
| [语音与 AI](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#voice-ai) | AI Chat、ASR 语音转文本、TTS 与语音回调。 |
| [媒体能力](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#media-capabilities) | 音视频预览、拍照录像、媒体状态监听。 |
| [消息与文件传输](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#messaging-file-transfer) | 手机与眼镜之间的消息、文件和流数据传输。 |
| [文件系统能力](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#file-system-capabilities) | 文件上传、文件状态、上传进度和结果回调。 |
| [视觉识别能力](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#visual-recognition-capabilities) | 人脸、车牌、人车检测等识别能力。 |
| [连接与外设](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#connectivity-peripherals) | Wi-Fi P2P、经典蓝牙、蓝牙指环等连接能力。 |
| [设备与系统能力](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#device-system-capabilities) | 设备状态、应用可见性、电量、亮度和音量。 |
| [参数取值与数据结构](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md#parameter-models) | 常量、枚举、复杂参数、回调数据结构。 |
| [完整 API 文档](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md) | 按接口名搜索完整眼镜端 API。 |

## 示例代码

- [查看全部代码示例](/downloads/samples.md)
- [眼镜端 SDK 拍照、录像、录音与 AI](../../代码示例/30-media/01-眼镜端-SDK-拍照录像录音与-AI.md)
- [眼镜端 TTS 与 ASR](../../代码示例/35-voice-ai/02-眼镜端-TTS-与-ASR.md)
- [眼镜端 AI Chat](../../代码示例/35-voice-ai/03-眼镜端-AI-Chat.md)
- [眼镜端发送消息与文件](../../代码示例/20-message-transfer/02-眼镜端发送消息与文件.md)
- [眼镜端接收消息与文件](../../代码示例/20-message-transfer/04-眼镜端接收消息与文件.md)

## 相关资料

如果你还不确定应该使用哪一端接口，可以先查看 [SDK 总览](../) 和 [代码示例](/downloads/samples.md)；如果已经知道接口名称，可以直接进入 [完整 API 文档](../api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md) 搜索。
