# AGENTS

本仓库是 Rokid Glass3 企业版 AI 眼镜开发项目。Agent 按 Cursor 项目 Skill 工作，不要凭记忆编造 SDK 接口。

## 必读 Skill

开发、查 API、写示例、排查蓝牙/P2P 时，先读取并遵循：

`.cursor/skills/rokid-glass3-sdk/SKILL.md`

该目录即官方 Glass3 SDK Skill 包（含 `references/` 与 `scripts/`）。路径相对 Skill 目录，例如 `references/docs/terminal-sdk/getting-started/快速开始.md`。

## 仓库约定

- 对用户用中文回复。
- 眼镜端入口是 `GlassSdk`，手机端入口是 `PSecuritySDK`，两端 `clientId` 必须一致。
- 当前 SDK：`glass3.open.sdk:2.2.0-E`、`phone.sdk:2.2.0-E`。版本以 `references/version.json` 为准。
- 接口名、回调、常量、硬件规格只引用 Skill 内公开文档；查不到就明确说文档没有。
- 人读总览在 `docs/SDK使用方法.md`；`docs/official` 指向上述 Skill 目录。

## 典型流程

1. 读 Skill 的 Question Routing，打开对应 reference。
2. 先接入与初始化，再按能力找代码示例，最后对 API 文档核对方法名。
3. 连接失败先查 `references/docs/faq/`，再改代码。
