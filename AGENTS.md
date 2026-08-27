# AGENTS

本仓库是 **乐奇 AI 眼镜 + 手机到餐 Agent** 项目，当前场景是到店餐饮 MVP。Agent 按 Cursor 项目 Skill 工作，不要凭记忆编造未经验证的接口。

## 开发立场

眼镜端主应用是 **CustomApp**（`apps/glass-nav`），按 **原生 Android** 开发：Camera2、WifiP2p、WebRTC、AudioRecord、Wi‑Fi、定位等都是系统能力。这副眼镜能跑的 Android API，我们理论上都能做，**不要**用「消费版没有 / 企业版才有」自我设限，更不要把企业 `GlassSdk` / `PSecuritySDK` 当成唯一实现路径。

乐奇（`com.rokid.sprite.aiapp`）+ CXR-L 只负责：连眼镜、CustomView 会话、把我们的 CustomApp 拉到前台。HUD 通道和信令走已验证的 CXR CustomCmd；媒体、组网、相机以 Android 原生为准。

公开文档 https://open.rokid.com/sdk 用来查 CXR 会话/授权；查不到的系统能力，以本仓库真机验证和 `lessons.md` 为准，可以说「CXR 文档没写」，然后继续用原生 API 做。

企业版整理（`.cursor/skills/rokid-glass3-sdk/`、`docs/`）只作对照，不是能力上限。

## 必读 Skill

真机 ADB、RokidMirror、镜片看不见、CXR 连接、CustomView HUD 等现场问题，先读：

`.cursor/skills/glass-dev/SKILL.md`

并先查 `.cursor/skills/glass-dev/lessons.md`。新坑解决后按该 Skill 的模板追加 lesson。

## 仓库约定

- 对用户用中文回复。
- 主应用是 `apps/phone`：到餐 Agent，`CXRLink`，会话类型 `CUSTOMAPP`，乐奇包名 `com.rokid.sprite.aiapp`。`apps/phone-nav` 不是产品入口。
- `apps/glass` 是归档实验，不参与本期验收。
- 人读需求在 `demand.md`。
- 真机系统版本号不含 `e` 时，不要把企业专有 SDK 硬接到主路径；缺的能力优先用 CustomApp 原生实现。

## 典型流程

1. 手机已装乐奇并连上这副眼镜。
2. 连接失败先查 `lessons.md`（双回调、token、乐奇版本），再改代码。
3. 改动 `apps/phone` 代码后，必须安装到手机上。
4. 这是生产级别的应用，禁止 mock 数据。
