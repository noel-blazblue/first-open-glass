# AGENTS

本仓库是 **乐奇 AI 眼镜消费版** 开发项目，当前场景是到店餐饮 MVP。Agent 按 Cursor 项目 Skill 工作，不要凭记忆编造 SDK 接口。

真机是消费固件（系统版本号不含 `e`）。**主路径是 CXR-L + Rokid AI App**，不是企业版 `GlassSdk` / `PSecuritySDK`。

## 必读 Skill

真机 ADB、RokidMirror、镜片看不见、CXR 连接、CustomView HUD 等现场问题，先读：

`.cursor/skills/glass-dev/SKILL.md`

并先查 `.cursor/skills/glass-dev/lessons.md`。新坑解决后按该 Skill 的模板追加 lesson。

消费版 SDK 选型、CXR-L / CXR-S 以官方开放文档为准：

https://open.rokid.com/sdk

企业版 Glass3 Skill（`.cursor/skills/rokid-glass3-sdk/`）只用于对照「企业系统才能用的接口」，**不要**再按它给消费版写接入代码。

## 仓库约定

- 对用户用中文回复。
- 主应用是 `apps/phone`：`CXRLink`，会话类型 `CUSTOMVIEW`，乐奇包名 `com.rokid.sprite.aiapp`。
- 镜片 HUD 是 CustomView 绿字短卡片（`HudCard`），不是 480×640 Compose Activity，也不是 AIUI / 灵珠网页。
- `apps/glass` 是归档实验，不参与消费版验收。
- 接口名、回调、常量只引用公开文档或本仓库已验证的 CXR 调用；查不到就明确说文档没有。
- 人读需求在 `demand.md`；`docs/` 里的企业版整理仍可用作对照。

## 典型流程

1. 先确认眼镜系统版本不含 `e`，手机已装乐奇并连上眼镜。
2. 连接失败先查 `lessons.md`（双回调、token、乐奇版本），再改代码。
3. 改动apps/phone 代码后，必须安装到手机上。
