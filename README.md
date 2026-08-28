# Glass

乐奇 **AI 眼镜** 应用仓库。当前落地场景是 **到店餐饮**。需求见 [`demand.md`](./demand.md)。

眼镜端是 **CustomApp + 原生 Android**（Camera2、WifiP2p、WebRTC 等），能跑的系统能力都可以做，不要用消费版/企业版自我设限。手机经 **CXR-L** + **乐奇** 把 CustomApp 拉到前台并走 CustomView HUD。企业 `GlassSdk` / `PSecuritySDK` 只作对照，不是实现路径。

门店不打进 APK。用 **门店录入** App 在店门口记录店名和当前经纬度，写入手机上的 `Download/glass-stores.json`。到餐 App 热加载这份目录，改数据不用重装。

现场调试（ADB、镜片看不见、CXR 双回调）读 [`.cursor/skills/glass-dev/`](.cursor/skills/glass-dev/SKILL.md) 和 [`lessons.md`](.cursor/skills/glass-dev/lessons.md)。CXR 会话/授权见 [open.rokid.com/sdk](https://open.rokid.com/sdk)。企业版整理仍在 `docs/`，只作对照。

## 当前形态

| 端 | 作用 |
| --- | --- |
| 手机 `apps/phone` | 主应用。乐奇授权、`CXRLink`、到餐 Agent、TTS、真 GPS 导航 |
| 门店 `apps/store` | 线下录入店名和经纬度，随时改 JSON |
| 共享 `shared` | Agent 协议、`place/` 地点句柄与资料、目录 JSON、匹配 |
| 眼镜 `apps/glass-nav` | CustomApp HUD |
| `apps/glass` | 归档的企业/裸机实验 |

CXR 依赖：`com.rokid.cxr:client-l:1.0.3`。真机请先用乐奇连上这副眼镜，再打开本 App。

```bash
./gradlew :apps:phone:assembleDebug :apps:store:assembleDebug :apps:glass-nav:assembleDebug
```

功能键单击识别、双击下一家是企业/裸机路径，本期不验收。
