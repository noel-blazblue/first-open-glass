# 手机端 SDK

手机端 SDK 面向 Android 手机应用。它更接近“连接与控制层”，负责连接眼镜、建立蓝牙和 P2P 通道、收发消息与文件、接收媒体流、执行 OTA 和配置同步等能力。

## 你应该什么时候看这里

| 场景 | 建议入口 |
| --- | --- |
| 需要初始化手机端 SDK 或引擎 | [初始化与引擎](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#_1-sdk初始化) |
| 需要发现、连接或管理眼镜设备 | [设备管理](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#_8-设备信息与拉取眼镜端音视频流) |
| 需要处理蓝牙、P2P 或大文件通道 | [蓝牙与 P2P](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#_2-wifip2p客户端管理模块) |
| 需要向眼镜发送消息或文件 | [消息与文件](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#_4-消息管理模块) |
| 需要接收眼镜端音视频流或相册内容 | [相机与媒体接收](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#_8-设备信息与拉取眼镜端音视频流) |

## 接入顺序

1. 先读 [SDK 总览](../getting-started/接入指南.md)，确认手机端 SDK 与眼镜端 SDK 的协作关系。
2. 按 [快速开始](../getting-started/快速开始.md) 完成依赖引入；如需跑通官方 Demo 和基础连接验证，进入 [Demo 运行指南](/downloads/demo-guide.md)。
3. 从 [代码示例](/downloads/samples.md) 找到相近场景，再回到本页 API 参考确认接口细节。

## API 参考

| 模块 | 适合查什么 |
| --- | --- |
| [接入与初始化](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#phone-sdk-init) | `PSecuritySDK` 初始化、引擎状态、销毁 SDK。 |
| [服务入口](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#psecuritysdk-service-entry) | 通过 `PSecuritySDK` 获取手机端各能力服务。 |
| [Wi-Fi P2P 与 AR Mix](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#phone-p2p) | P2P 连接、设备发现、P2P 消息回调、AR 叠加录制。 |
| [蓝牙与指环](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#phone-bluetooth) | 经典蓝牙、BLE、蓝牙指环连接与消息。 |
| [消息与文件传输](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#phone-message-file) | 发送消息、收发文件、上传下载文件、发送 APK。 |
| [设备与媒体流](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#phone-device-media) | 设备信息、眼镜音视频流、媒体接收器。 |
| [语音、AI 与翻译](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#phone-voice-ai) | AI Chat、翻译语言设置、语音相关服务。 |
| [识别、采集与跟踪](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#phone-recognition) | 手机端 provider、识别结果、采集回调、跟踪监听。 |
| [OTA、日志与辅助能力](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#phone-ota-provision) | OTA 下载、日志、通知和业务配置。 |
| [参数取值与数据结构](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md#phone-parameter-models) | 初始化参数、下载/上传状态、翻译语言、眼镜 App 配置。 |
| [完整 API 文档](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md) | 按接口名搜索完整手机端 API。 |

## 示例代码

- [查看全部代码示例](/downloads/samples.md)
- [经典蓝牙扫描与连接](../../代码示例/10-device-connection/01-经典蓝牙扫描与连接.md)
- [蓝牙与 P2P 一体化配对](../../代码示例/10-device-connection/02-蓝牙与-P2P-一体化配对.md)
- [Wi-Fi P2P 连接](../../代码示例/10-device-connection/03-Wi-Fi-P2P-连接.md)
- [手机端发送消息与文件](../../代码示例/20-message-transfer/01-手机端发送消息与文件.md)
- [手机端接收消息与文件](../../代码示例/20-message-transfer/03-手机端接收消息与文件.md)
- [OTA 升级](../../代码示例/50-system/01-OTA-升级.md)

## 相关资料

如果你还不确定应该使用哪一端接口，可以先查看 [SDK 总览](../) 和 [代码示例](/downloads/samples.md)；如果已经知道接口名称，可以直接进入 [完整 API 文档](../api-reference/Glass3%20%20SDK(手机端)%20API文档.md) 搜索。
