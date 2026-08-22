# 眼镜端 SDK 拍照录像录音与 AI

## 示例说明

演示眼镜端通过官方 SDK 完成拍照、录像、录音、缩放和 AI 对话能力调用。

## 使用位置

眼镜端首页：

- `HomeActivity` -> `SdkMediaActivity`

示例页面：

- `com.rokid.glass.SdkMediaActivity`

## 适用端

- 眼镜端

## 关键文件

- `glassdemo/app/src/main/java/com/rokid/glass/SdkMediaActivity.kt`

## 支持的能力

- 多分辨率拍照
- 多分辨率录像
- 停止录像
- 视频流发送
- 停止视频流发送
- 变焦
- 录音
- 停止录音
- AI Chat 启动与结束

## 关键 SDK 调用

- `takePhoto(...)`
- `startRecord(...)`
- `stopRecord()`
- `sendVideoStreamDataV2(...)`
- `stopVideoStreamData()`
- `zoomCamera(...)`
- `startAudioRecord(...)`
- `stopAudioRecord(...)`
- `startAiChat(...)`
- `toAiChat(...)`
- `endAiChat()`

## 实现说明

### 适用场景

适合作为媒体能力总入口示例，也适合作为 API 探针页。

### 分辨率选择有什么区别

示例里已经把一些差异直接写在注释中，例如：

- 720P 拍照偏横屏
- 1080P 部分模式偏竖屏
- 4K 拍照为横屏

## 注意事项

- 录像输出目录在公共图片目录下。
- 视频分片时长当前示例写死为 1 分钟。
- 录音结果输出为 AAC 文件。
