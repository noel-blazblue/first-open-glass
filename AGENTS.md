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
- **做通用型智能 Agent，禁止定制化、写死业务。** 见下方「通用 Agent」。

## 眼镜 APK 安装（硬规则）

`apps/glass-nav` **只能**通过电脑 ADB 直装，禁止任何手机/CXR 中转。

- 命令：`adb -s <RG_glasses serial> install -r ...`，或 `./gradlew :apps:glass-nav:installGlassAdb`。
- 安装前必须 `adb devices -l`。眼镜未出现、或 `model:RG_glasses` 匹配不到 / 匹配到多台时 **立即停止并报告**，禁止改走手机、`CXRLink.appUploadAndInstall`、把 APK 推进手机目录再上传。
- 手机和眼镜按 `model` / serial 分别校验后再装：手机 `installDebug` 必须设 `ANDROID_SERIAL`（AGP 否则会装到所有设备，包括眼镜），眼镜用 `GLASS_SERIAL` 或唯一 `model:RG_glasses`。禁止依赖默认 adb 设备。
- `apps/phone:installDebug` 只装手机包并同步手机 `.env`，不再构建或推送眼镜 APK。

## 连接问题

不要说「眼镜连不上」。先按 [`docs/glass-connection-troubleshooting.md`](docs/glass-connection-troubleshooting.md) 判定是哪一条链路（电脑 ADB / CXR 蓝牙 / 眼镜 Wi-Fi / Wi-Fi Direct / WebRTC），再改代码。闭环后同步更新该文档和 `.cursor/skills/glass-dev/lessons.md`。

## 代码编写准则

写新代码、改旧代码都按这个尺度；宁可先拆模块，也不要继续往大文件里堆。

### 体量

- **单文件目标 ≤ 400 行**（含空行与 import）。到 **500 行必须拆**，禁止在已超限的文件上继续加职责。
- 单个函数目标 ≤ 80 行；超过就抽私有函数或独立类型。
- ViewModel / Activity / `*Host` 只做编排与生命周期，不塞领域算法、协议解析、HUD 排版。
- 现有超限文件（例如 `DiningViewModel`）改功能时顺手拆出一块，不要再加厚。

### 模块抽象

- **一个文件一个主职责**。按领域分包，不按技术层乱堆：`nav/`、`vision/`、`hud/`、`engine/`、`catalog/`。
- 纯逻辑（匹配、规划、协议、HUD 数据结构）放 `shared`；Android 系统能力（GPS、相机、CXR、TTS）放对应 `apps/*`。
- 新能力优先新类型 + 新文件，再由现有编排类调用。不要把导航、视觉、LLM、语音塞进同一个 class。
- 抽取边界是「能单独理解、单独测」：输入输出清晰。不要为了行数把紧密耦合的 20 行拆成一堆单行文件，也不要建 `Utils.kt` 垃圾桶。
- 协议字段、数据模型改动落在 `shared` / `nav-api`，两端一起改，禁止在手机或眼镜端私自加平行字段。

### 通用 Agent

产品是通用到餐 Agent，不是某一家店、某一句话、某一个识别错误的专用程序。

- 意图靠模型 + 工具 + 会话上下文（目录、候选、当前店、画面证据），**不靠固定口令、谐音表、品牌白名单、if 某店名**。
- 禁止为单个案例写死映射（例如把「开滴滴」替换成「海底捞」）。世界上店名和说法无限，表补不完，还会误伤真正说的那句话。

### 实现习惯

- 先读调用链再改，复用已有抽象；禁止复制一份「差不多」的实现。
- 禁止 mock 业务数据。测试用固定输入断言纯函数，不要在主路径里塞假店、假 GPS。
- 新增可测逻辑时补单元测试，放在对应模块的 `src/test`。

## 典型流程

1. 手机已装乐奇并连上这副眼镜。
2. 连接失败先查 `lessons.md`（双回调、token、乐奇版本），再改代码。
3. 改动 `apps/phone` 代码后，必须安装到手机上。
4. 改动 `apps/glass-nav` 后，必须用 `installGlassAdb` 直装到眼镜，禁止用手机更新眼镜包。
5. 这是生产级别的应用，禁止 mock 数据。
