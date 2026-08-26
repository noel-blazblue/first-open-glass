# Glass3 SDK 文档（企业版对照）

**本期产品走消费版 CXR-L**，见仓库根目录 [`demand.md`](../demand.md) 与 [`README.md`](../README.md)。视觉分流 POC 见 [`vision-routing-poc.md`](./vision-routing-poc.md)。下面这些整理对应 **Glass3 企业系统**（版本号含 `e`），消费固件上不能当接入手册。

本目录收录 Rokid Glass3 企业版终端 SDK 的使用方法，整理自[官方文档](https://x-docs.rokid.com/docs/terminal-sdk/getting-started/快速开始.html)。

## 建议阅读顺序

1. [SDK 使用方法](./SDK使用方法.md) — 本仓库整理的接入与调用总览
2. [官方快速开始](./official/references/docs/terminal-sdk/getting-started/快速开始.md)
3. [官方接入指南](./official/references/docs/terminal-sdk/getting-started/接入指南.md)
4. 按能力查 [代码示例总览](./official/references/docs/downloads/samples.md)
5. 查接口：
   - [眼镜端 API](./official/references/docs/terminal-sdk/api-reference/Glass3%20%20SDK(眼镜端)%20API文档.md)
   - [手机端 API](./official/references/docs/terminal-sdk/api-reference/Glass3%20%20SDK(手机端)%20API文档.md)

## 目录说明

| 路径 | 内容 |
| --- | --- |
| [SDK使用方法.md](./SDK使用方法.md) | 环境、依赖、初始化、双端能力入口、常见调用 |
| [SOURCE.md](./SOURCE.md) | 官方来源、版本、Maven、Demo 仓库 |
| [official/](./official/) | 指向 `.cursor/skills/rokid-glass3-sdk/` 的符号链接 |

## 当前 SDK 坐标

- 眼镜端：`com.rokid.security:glass3.open.sdk:2.2.0-E`
- 手机端：`com.rokid.security:phone.sdk:2.2.0-E`
- Maven：`https://maven.rokid.com/repository/maven-public/`
