# 代码示例

这里按 Demo 能力场景整理示例。建议先跑通 [Demo 运行指南](./demo-guide.md)，再根据目标能力进入对应示例，并结合 API 参考确认接口细节。

## 场景索引

| 场景 | 示例 | 适用端 | 覆盖能力 | 相关 API |
| --- | --- | --- | --- | --- |
| 设备发现与配对 | [经典蓝牙扫描与连接](../代码示例/10-device-connection/01-经典蓝牙扫描与连接.md) | 手机端 | 扫描 Glass3 设备、选择目标设备 | [蓝牙与指环](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-bluetooth) |
| 设备发现与配对 | [蓝牙与 P2P 一体化配对](../代码示例/10-device-connection/02-蓝牙与-P2P-一体化配对.md) | 手机端 | 经典蓝牙连接、P2P 自动连接请求、连接状态维护 | [蓝牙与指环](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-bluetooth)、[Wi-Fi P2P 与 AR Mix](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-p2p) |
| 设备发现与配对 | [Wi-Fi P2P 连接](../代码示例/10-device-connection/03-Wi-Fi-P2P-连接.md) | 手机端 | P2P 初始化、设备扫描、连接目标设备 | [Wi-Fi P2P 与 AR Mix](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-p2p) |
| 设备发现与配对 | [设备状态同步与远程控制](../代码示例/10-device-connection/04-设备状态同步与远程控制.md) | 手机端、眼镜端 | 系统信息、电量、亮度、音量和自定义配置同步 | [消息与文件传输](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-message-file)、[设备与系统能力](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#device-system-capabilities) |
| 设备发现与配对 | [指环连接](../代码示例/10-device-connection/05-指环连接.md) | 手机端 | 指环扫描、连接、连接状态回调 | [蓝牙与指环](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-bluetooth) |
| 消息与文件 | [手机端发送消息与文件](../代码示例/20-message-transfer/01-手机端发送消息与文件.md) | 手机端 | 文本消息、文件发送、APK 发送 | [消息与文件传输](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-message-file) |
| 消息与文件 | [眼镜端发送消息与文件](../代码示例/20-message-transfer/02-眼镜端发送消息与文件.md) | 眼镜端 | 眼镜向手机发送消息、音频流和文件 | [消息与文件传输](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#messaging-file-transfer) |
| 消息与文件 | [手机端接收消息与文件](../代码示例/20-message-transfer/03-手机端接收消息与文件.md) | 手机端 | 手机端监听消息、文件和流数据 | [消息与文件传输](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-message-file) |
| 消息与文件 | [眼镜端接收消息与文件](../代码示例/20-message-transfer/04-眼镜端接收消息与文件.md) | 眼镜端 | 眼镜端监听手机消息和文件 | [消息与文件传输](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#messaging-file-transfer) |
| 消息与文件 | [通知同步](../代码示例/20-message-transfer/05-通知同步.md) | 手机端、眼镜端 | 手机通知发送、眼镜通知展示 | [OTA、日志与辅助能力](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-ota-provision) |
| 媒体与预览 | [实时视频预览](../代码示例/20-message-transfer/06-实时视频预览.md) | 手机端、眼镜端 | P2P 视频流、H264/NV21 数据回调 | [Wi-Fi P2P 与 AR Mix](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-p2p) |
| 媒体与预览 | [眼镜端 SDK 拍照、录像、录音与 AI](../代码示例/30-media/01-眼镜端-SDK-拍照录像录音与-AI.md) | 眼镜端 | SDK 媒体能力、拍照、录像、录音、AI 对话入口 | [相机与媒体](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#media-capabilities)、[语音与 AI](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#voice-ai) |
| 媒体与预览 | [眼镜端应用拍照、录像](../代码示例/30-media/02-眼镜端应用拍照录像.md) | 眼镜端 | 眼镜 App 内拍摄页面与业务 UI | [相机与媒体](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#media-capabilities) |
| 媒体与预览 | [手机端相册预览](../代码示例/30-media/03-手机端相册预览.md) | 手机端 | 手机端相册、媒体接收和预览 | [设备与媒体流](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-device-media) |
| 语音与 AI | [手机端 SDK 初始化（ASR/TTS）](../代码示例/35-voice-ai/01-手机端-SDK-初始化（ASR-TTS）.md) | 手机端 | 手机端引擎初始化、ASR/TTS 能力准备 | [语音、AI 与翻译](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-voice-ai) |
| 语音与 AI | [眼镜端 TTS 与 ASR](../代码示例/35-voice-ai/02-眼镜端-TTS-与-ASR.md) | 眼镜端 | 语音合成、语音识别、语音回调 | [语音与 AI](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#voice-ai) |
| 语音与 AI | [眼镜端 AI Chat](../代码示例/35-voice-ai/03-眼镜端-AI-Chat.md) | 眼镜端 | AI 对话、流式回调、音频输入 | [语音与 AI](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#voice-ai) |
| 视觉识别 | [人脸检测](../代码示例/40-vision/01-人脸检测.md) | 眼镜端 | 人脸检测、采集、回调数据 | [识别能力](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#visual-recognition-capabilities) |
| 视觉识别 | [车牌识别](../代码示例/40-vision/02-车牌识别.md) | 眼镜端 | 车牌检测、结果回调 | [识别能力](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#visual-recognition-capabilities) |
| 系统与配置 | [OTA 升级](../代码示例/50-system/01-OTA-升级.md) | 手机端 | OTA 查询、下载、进度回调 | [OTA、日志与辅助能力](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-ota-provision) |
| 系统与配置 | [眼镜设置同步](../代码示例/50-system/02-眼镜设置同步.md) | 手机端、眼镜端 | 亮度、音量、配置同步 | [设备与系统能力](/terminal-sdk/api-reference/Glass3%20%20SDK(%E7%9C%BC%E9%95%9C%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#device-system-capabilities) |

## 设备连接

- [经典蓝牙扫描与连接](../代码示例/10-device-connection/01-经典蓝牙扫描与连接.md)
- [蓝牙与 P2P 一体化配对](../代码示例/10-device-connection/02-蓝牙与-P2P-一体化配对.md)
- [Wi-Fi P2P 连接](../代码示例/10-device-connection/03-Wi-Fi-P2P-连接.md)
- [设备状态同步与远程控制](../代码示例/10-device-connection/04-设备状态同步与远程控制.md)
- [指环连接](../代码示例/10-device-connection/05-指环连接.md)

## 消息与文件传输

- [手机端发送消息与文件](../代码示例/20-message-transfer/01-手机端发送消息与文件.md)
- [眼镜端发送消息与文件](../代码示例/20-message-transfer/02-眼镜端发送消息与文件.md)
- [手机端接收消息与文件](../代码示例/20-message-transfer/03-手机端接收消息与文件.md)
- [眼镜端接收消息与文件](../代码示例/20-message-transfer/04-眼镜端接收消息与文件.md)
- [通知同步](../代码示例/20-message-transfer/05-通知同步.md)

## 拍照、录像与实时预览

- [眼镜端 SDK 拍照、录像、录音与 AI](../代码示例/30-media/01-眼镜端-SDK-拍照录像录音与-AI.md)
- [眼镜端应用拍照、录像](../代码示例/30-media/02-眼镜端应用拍照录像.md)
- [手机端相册预览](../代码示例/30-media/03-手机端相册预览.md)
- [实时视频预览](../代码示例/20-message-transfer/06-实时视频预览.md)

## 语音与 AI

- [手机端 SDK 初始化（ASR/TTS）](../代码示例/35-voice-ai/01-手机端-SDK-初始化（ASR-TTS）.md)
- [眼镜端 TTS 与 ASR](../代码示例/35-voice-ai/02-眼镜端-TTS-与-ASR.md)
- [眼镜端 AI Chat](../代码示例/35-voice-ai/03-眼镜端-AI-Chat.md)

## 视觉识别

- [人脸检测](../代码示例/40-vision/01-人脸检测.md)
- [车牌识别](../代码示例/40-vision/02-车牌识别.md)

## 系统与配置

- [OTA 升级](../代码示例/50-system/01-OTA-升级.md)
- [眼镜设置同步](../代码示例/50-system/02-眼镜设置同步.md)

## Demo 基础信息

- [Demo 运行指南](./demo-guide.md)
