# 独立 ASR/TTS 示例使用说明

眼镜端独立部署 ASR/TTS 功能已集成在 Demo 仓库的眼镜端 `glassdemo` 工程中。本文档将介绍如何获取该示例并部署到自己的眼镜端。

## 1. 获取并打开 Demo

请先前往 [Demo 运行指南](/downloads/demo-guide)，按照“获取 Demo”中的说明克隆 GitHub 仓库。

使用 Android Studio 打开克隆目录中的 `glass3_sdk_demo/glassdemo` 工程。

## 2. 编译与安装

```bash
cd glass3_sdk_demo/glassdemo
./gradlew :app:assembleDebug
```

APK 输出：

- `app/build/outputs/apk/debug/app-debug.apk`

## 3. 配置项

示例默认从 `glass3_sdk_demo/glassdemo/gradle.properties` 读取：

- `online.demo.domain`：语音服务域名；私有化部署时替换为对应环境提供的域名。
- `online.demo.ak`：访问密钥（Access Key），用于服务鉴权。
- `online.demo.sk`：签名密钥（Secret Key），用于生成鉴权签名。
- `online.demo.uid`：用户唯一标识，可根据业务需求自定义。
- `online.demo.deviceId`：设备唯一标识，可根据业务需求自定义。
- `online.demo.asrPath`：ASR 实时语音识别 WebSocket 接口路径；私有化部署时按实际服务路径修改。
- `online.demo.ttsPath`：TTS 语音合成 WebSocket 接口路径；私有化部署时按实际服务路径修改。
- `online.demo.trustAllCerts`：是否忽略证书校验；仅建议在调试环境启用。

## 4. 示例页面

在眼镜端 Demo 首页向后滑动并选择 **独立ASR/TTS**。

<img src="/private-speech/independent-asr-tts-entry.png" alt="眼镜端 Demo 首页中的独立 ASR/TTS 入口" width="238" />

示例包含以下页面：

### 4.1 初始化页

- `Init SDK (create clients)`：创建 SDK、ASR client、TTS client。
- `Close SDK + Unbind`：释放 SDK 和连接、解绑 open-sdk 服务。

### 4.2 ASR 页

- `connect()`：建立 ASR WebSocket。
- `startAsrWithMic()`：通过 open-sdk 录音并自动推流。
- `stopAsrWithMic()`：停止录音并结束 ASR。
- `close()`：关闭 ASR 连接。

### 4.3 TTS 页

- `connect()`：建立 TTS WebSocket。
- `speak(default text)`：播放默认测试文案。
- `stop()`：停止当前 TTS 播放。
- `close()`：关闭 TTS 连接。

## 5. 常见问题

- ASR 无结果：确认已完成配置，并先调用 `connect()` 再调用 `startAsrWithMic()`。
- TTS 无声音：确认已调用 `connect()`，并检查设备音量与音频路由。
