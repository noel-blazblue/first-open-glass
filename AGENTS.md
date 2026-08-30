# AGENTS

本仓库是 **乐奇 AI 眼镜 + 手机到餐 Agent** 项目（当前场景：到店餐饮）。

---

## 一、 系统架构与分工

```
[眼镜端 apps/glass] (采集/HUD) <--- link-api / CXR CustomCmd ---> [手机端 apps/phone] (决策/网络/模型)
```

1. **手机端（`apps/phone`）**
   - 主脑角色：负责大模型编排、高德地图、外网请求、业务状态机。
   - 通过 `CXRLink`（会话类型 `CUSTOMAPP`，乐奇包名 `com.rokid.sprite.aiapp`）建立连接并拉起眼镜端 CustomApp。
2. **眼镜端（`apps/glass`）**
   - 交互与感知角色：负责传感器采集（Camera2、AudioRecord、定位）与 480×640 HUD 渲染。
   - **原生 Android 开发**：以 Android 原生 API 为主（系统版本号不含 `e` 时切勿强依赖企业专有 SDK），不自我设限。
3. **共享与协议层（`shared` / `link-api`）**
   - 纯逻辑（匹配、规划、HUD 数据结构、运行时协议）统一归入 `shared`。
   - 协议字段两端保持一致，禁止在手机端或眼镜端私自新增平行字段。

---

## 二、 交互与沟通规范

1. **对话风格**
   - **全中文交互**，默认对方了解上下文，不主动罗列代码文件名、字段表或调用栈。
   - **先答所问**：一句话说清结论与判断，再补必要依据。
   - **一条因果**：讲链路仅说明关键流转（例如：聊天模型触发 → 视觉模型解析 → 结果回传 → 语音播报）。
   - **不问不展开**：不倒无意义的 JSON 或旁路细节；禁止反问让用户自行总结。
2. **效果图与设计呈现**
   - 需展示 HUD / UI 效果时，统一调用 Cursor 内置 **`GenerateImage`**（`namespace=cursor`，`toolName=GenerateImage`）。
   - 出图当轮回复以简短说明 + 工具调用为主；**禁止**用 Python/Pillow 脚本生成本地图片或用 Markdown 伪引用。
   - 眼镜显示画面规格：**480×640 竖屏**。

---

## 三、 设备与调试硬规则

1. **眼镜 APK 安装**
   - `apps/glass` **只能通过电脑 ADB 直装**（`./gradlew :apps:glass:installGlassAdb` 或 `adb -s <serial> install -r ...`），禁止通过手机中转或 `appUploadAndInstall`。
   - 执行前先 `adb devices -l` 校验设备。多设备时必须显式指定 `ANDROID_SERIAL`（手机）或 `GLASS_SERIAL`（眼镜 `model:RG_glasses`）。
   - `apps/phone:installDebug` 仅构建安装手机包并同步 `.env`，不分发眼镜 APK。
2. **现场排查与经验沉淀**
   - 连接异常时按 `docs/glass-connection-troubleshooting.md` 判定具体物理链路（电脑 ADB / CXR 蓝牙 / Wi-Fi / Wi-Fi Direct / WebRTC），勿笼统归因为「眼镜连不上」。
   - 真机 ADB、RokidMirror、黑屏、CXR 回调等现场问题先查阅 `.cursor/skills/glass-dev/SKILL.md` 与 `lessons.md`；新坑闭环后及时在 `lessons.md` 沉淀记录。

---

## 四、 代码编写准则

1. **体量控制**
   - **单文件目标 ≤ 400 行**（超过 500 行必须拆分，禁止在大文件上继续堆砌职责）。
   - **单函数目标 ≤ 80 行**；ViewModel / Activity / `*Host` 仅负责编排与生命周期，不塞领域算法与渲染排版。
2. **模块与抽象**
   - 严格按领域分包（`place/`、`nav/`、`vision/`、`hud/`、`agent/`、`catalog/`），避免按技术层杂糅。
   - `shared/agent` 仅承载运行时上下文协议；地点与资料分别收敛至 `shared/place`。
   - 禁止设立 `Utils.kt` 等职责模糊的垃圾桶类；抽取逻辑以「职责单一、输入输出明确、可单独测试」为原则。
3. **通用 Agent 与真实数据**
   - 遵循开放世界语义，依赖模型提案与纯逻辑 reducer 处理意图，**严禁写死特定店名、口令或谐音映射字典**。
   - 生产级代码严禁 mock 虚假业务数据；新增纯逻辑能力须在对应模块 `src/test` 补齐单元测试。
