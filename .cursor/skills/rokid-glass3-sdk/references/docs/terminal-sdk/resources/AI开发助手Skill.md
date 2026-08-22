# AI 开发助手 Skill

AI 开发助手 Skill 是面向 AI 编程工具的 Rokid Glass3 SDK 知识包。它把 SDK 文档、产品手册、FAQ 和 Demo 索引整理成结构化资料，帮助 AI 更准确地回答接入、API、示例和排查问题，并尽量给出可追溯的资料来源。

## 下载

- 下载最新 Skill 包：请在文档站资源下载页获取
- 查看 Skill 版本信息：当前包内见 `references/version.json`

最新 Skill 包会在文档构建时自动生成。每次发布新文档版本时，稳定下载链接会同步指向当前版本的 Skill 包。

当前 Skill 包覆盖：

- 产品手册
- SDK 文档
- FAQ / 问题排查
- Demo 索引
- 文档版本和 SDK 版本信息

Skill 包不包含：

- 内部资料
- 未公开 API
- 本地工程源码或本地开发路径

## 包含内容

| 内容 | 说明 |
| --- | --- |
| `SKILL.md` | AI 使用入口，说明如何按问题类型查找对应资料。 |
| `references/docs/` | 当前公开文档中的 Markdown 内容。 |
| `references/product-manual.md` | 企业版产品手册抽取内容，用于硬件规格、产品使用、设备配对、OTA、投屏和常见操作问题。 |
| `references/demo-code-index.md` | Demo 下载包和能力示例索引。 |
| `references/version.json` | 当前文档版本、眼镜端 SDK 版本、手机端 SDK 版本。 |
| `references/evaluation-prompts.md` | 用于测试 Skill 效果的示例问题和验收标准。 |
| `scripts/extract-demo-package.sh` | 可选脚本，用于解压 Demo 下载包。 |

## 问题路由建议

使用 Skill 时，建议先判断问题类型，再查对应资料：

| 问题类型 | 优先资料 |
| --- | --- |
| 硬件规格、产品使用、设备配对、OTA、投屏 | `references/product-manual.md` |
| SDK 接入、Gradle 依赖、初始化、权限配置 | `references/docs/terminal-sdk/getting-started/快速开始.md` |
| 眼镜端 API | `references/docs/terminal-sdk/api-reference/Glass3  SDK(眼镜端) API文档.md` |
| 手机端 API | `references/docs/terminal-sdk/api-reference/Glass3  SDK(手机端) API文档.md` |
| Demo 示例、功能场景实现 | `references/demo-code-index.md`、`references/docs/downloads/samples.md` |
| 蓝牙、P2P、启动和按键问题排查 | `references/docs/faq/` |
| 文档版本、SDK 版本 | `references/version.json` |

## 推荐使用方式

不同 AI 工具对资料包的导入方式不完全一样。你可以直接把 Skill 压缩包提供给 AI 工具；如果工具不支持直接读取 zip，再解压后把整个文件夹加入项目知识库、上下文文件或 Skill 目录。

使用时只需要明确告诉 AI：这是 Rokid Glass3 SDK 的开发知识包，请先阅读压缩包中的 `SKILL.md`，再根据问题读取 `references/` 中的对应资料。如果你的 AI 工具没有 Skill 机制，也可以直接把 `SKILL.md` 和相关 `references/` 文件作为上下文上传。

## 如何测试效果

下载后可以用 `references/evaluation-prompts.md` 中的问题测试。建议至少覆盖三类问题：

- 接入类：例如“如何初始化眼镜端 SDK？”
- 场景类：例如“手机端如何向眼镜端发送文件？”
- 排查类：例如“蓝牙能发现设备但 P2P 连不上怎么排查？”

一个可用的 Skill 应该能区分手机端、眼镜端和云端接口，能指出对应文档或 Demo 示例。
