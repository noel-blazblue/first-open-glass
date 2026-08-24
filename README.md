# Glass

乐奇 **AI 眼镜消费版** 应用仓库。当前落地场景是 **到店餐饮 MVP**（识别、团购、问答全部 mock）。需求见 [`demand.md`](./demand.md)。

主路径是手机 App 接 **CXR-L**，经官方 **Rokid AI App（乐奇）** 把绿字 HUD 推到镜片。不要用企业版 `GlassSdk` / `PSecuritySDK`。

现场调试（ADB、镜片看不见、CXR 双回调）读 [`.cursor/skills/glass-dev/`](.cursor/skills/glass-dev/SKILL.md) 和 [`lessons.md`](.cursor/skills/glass-dev/lessons.md)。消费版 SDK 选型见 [open.rokid.com/sdk](https://open.rokid.com/sdk)。企业版文档仍在 `docs/`，只作对照，不是本期接口。

## 当前形态

| 端 | 作用 |
| --- | --- |
| 手机 `apps/phone` | 主应用。乐奇授权、`CXRLink` CustomView、mock 引擎、TTS |
| 共享 `shared` | 门店模型 / mock / 匹配 / 问答 / `HudCard` |
| 眼镜 | 只显示乐奇 CustomView。不装 `apps/glass` |
| `apps/glass` | 归档的企业/裸机实验，消费固件上绑不上 `GlassSdk` |

CXR 依赖：`com.rokid.cxr:client-l:1.0.3`。真机请先用乐奇连上这副眼镜，再打开本 App。

无眼镜时，在 Android 手机上安装 `apps/phone`，即可 30 秒走完「看店 → 看团购 → 问排队 → 换下一家」。

```bash
./gradlew :apps:phone:assembleDebug
```

功能键单击识别、双击下一家是企业/裸机路径，本期不验收。
