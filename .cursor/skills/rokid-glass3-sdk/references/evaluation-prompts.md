# Skill Evaluation Prompts

Use these prompts to check whether an AI assistant can use this skill effectively.

## Basic Q&A

1. 如何初始化眼镜端 SDK？请给出 Gradle 依赖、初始化入口和成功校验方式。
2. 手机端 SDK 如何配置依赖并初始化？
3. 如何确认 Glass3 数据调试线和普通充电线的区别？scrcpy 在调试中有什么作用？
4. faceScore、iqaScore 和 LPRModel.score 分别是什么意思？
5. 指环连接应该参考哪些 API 和代码示例？
6. 如何下载 Rokid AI 企业版 App、完成登录和设备配对？

## Integration Tasks

1. 生成一个手机端向眼镜端发送文本消息和文件的接入步骤，要求指出对应 Demo 示例和 API 参考位置。
2. 生成一个眼镜端拍照并把结果回传到手机端的接入步骤，要求说明涉及的服务、回调和注意事项。
3. 生成一个蓝牙与 P2P 一体化配对的问题排查清单。

## Expected Behavior

- Answers should distinguish phone-side SDK and glasses-side SDK.
- Answers should cite bundled reference paths or section names when useful.
- Answers should avoid local filesystem paths, unpublished notes, and files outside this package.
- Answers should prefer current SDK coordinates from `references/version.json`.
