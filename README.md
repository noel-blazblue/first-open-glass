# Glass

Rokid Glass3 企业版 AI 眼镜应用开发仓库。当前落地场景是 **到店餐饮 MVP**（识别、团购、问答全部 mock）。需求见 [`demand.md`](./demand.md)。

SDK 使用方法见 [`docs/SDK使用方法.md`](./docs/SDK使用方法.md)。Cursor Agent 请读根目录 [`AGENTS.md`](./AGENTS.md)，以及项目 Skill [`.cursor/skills/rokid-glass3-sdk/`](./.cursor/skills/rokid-glass3-sdk/SKILL.md)。

## 当前 SDK

| 端 | 坐标 |
| --- | --- |
| 眼镜端 | `com.rokid.security:glass3.open.sdk:2.2.0-E` |
| 手机端 | `com.rokid.security:phone.sdk:2.2.0-E` |

两端 `clientId`：眼镜 `GlassDining`，手机列表含 `GlassDining` 与 `GlassDiningPhone`。

官方文档：https://x-docs.rokid.com/docs/terminal-sdk/getting-started/快速开始.html

## 工程

```text
apps/glass    眼镜 HUD
apps/phone    手机陪伴 + 480×640 HUD 预览
shared        门店模型 / mock / 匹配 / 问答 / HUD UI
```

无眼镜时，在 Android 手机上安装 `apps/phone`，即可 30 秒走完「看店 → 看团购 → 问排队 → 换下一家」。

```bash
./gradlew :apps:phone:assembleDebug
./gradlew :apps:glass:assembleDebug
```

眼镜真机请用 Glass3 **数据调试线**。功能键单击识别，双击下一家。
