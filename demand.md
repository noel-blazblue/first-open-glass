# First Open Glass — 到餐 Agent MVP

基于 **乐奇 AI 眼镜消费版** + 手机 **CXR-L**。当前阶段只落地 **到店餐饮**；景区 / 游乐园 / 外卖取餐只保留场景位，不实现。

产品是 **到餐 Agent**，不是导航 App。对话是唯一入口；认店、推荐、导航、以及以后的菜单 / 验券 / 结账都是 Agent 可调用的技能。有目的店且用户要出发时，由模型调用 `start_nav`，不靠固定口令。

店名、排队、人均、团购 **mock**。定位、步行路线、剩余距离 **必须是真的**。没有眼镜时，用手机里的绿字 HUD 预览走通主路径。

本仓库的眼镜是消费固件（系统版本号不含 `e`）。**不要**再用企业版 `GlassSdk` / `PSecuritySDK` 当主路径。

---

## 1. 目标

用户只跟 AI 说话。Agent 按意图调用技能：推荐附近店、认眼前店招、问答、选定门店、再开到店步行导航。不要求拿出手机完成主路径，也不要一进 App 就是导航 HUD。

**本阶段验收（无眼镜）：** 手机预览走完「说想吃什么 → 推荐 2～3 家 → 选定（或同一句里就要出发）→ 模型调 `start_nav` 出导航条」。没选店不得进入导航。对话态是乐奇式「一句口语 + 圆点头像」，禁止把回复拆成主标题和正文。

**本阶段验收（有眼镜）：** 手机已装 **Rokid AI App（乐奇）** 并连上这副眼镜 → CXR-L `onCXRLConnected` 且 `onGlassBtConnected` → 眼镜 CustomApp 打开对话面（Hi, 我在听）→ 说话推荐/认店出门店卡 → 用户表示要走才切导航条。导航用手机 GPS + 高德步行，不开低频相机。

对齐乐奇的是 **对话面构图和交互时序**，不是时速 + 小地图。室外路网用高德步行 REST，不自研、不画地图。

---

## 2. Agent 怎么长

```text
用户语音
    → 到餐 Agent（DeepSeek + 会话上下文）
        → 技能工具（可增删）
        → 镜片 HUD（对话 talk / 门店卡 / 导航条）
        → TTS
```

会话上下文（一直带着，技能共享）：

- `currentStore`：当前在聊 / 在看的店
- `candidates`：刚才推荐的 2～3 家
- `activeSkill`：`none` | `browse` | `nav` | 以后 `menu` | `coupon` | `pay`
- 导航等技能的私有状态，停技能时清掉，店上下文可保留

新功能 = 新工具 + 一种 HUD 皮 + 是否占用相机。不要新开 App、不要新首页。

---

## 3. 技能表（本期做 / 以后加）

| 技能 | 用户怎么说 | 工具 | 相机 | 本期 |
| --- | --- | --- | --- | --- |
| 闲置听 | （打开对话） | 无 | 关 | 做 |
| 推荐 | 附近火锅、不要排队 | `recommend` | 关 | 做 |
| 认店 | 这是哪家、看店 | `look_store` | 拍一张 | 做 |
| 问店 | 排队、人均、包间 | 读 currentStore | 关 | 做 |
| 选定 | 第一家、去海底捞 | `select_store` | 关 | 做 |
| 到店导航 | 走、出发、去这家（有目的店） | `start_nav` / `stop_nav` | 关 | 做 |
| 菜单识别 | 看看菜单 | `read_menu` | 拍一张 | 以后 |
| 扫码验券 | 核销这张券 | `scan_coupon` | 拍一张 | 以后 |
| 结账 | 买单 | `checkout` | 可能拍码 | 以后 |

相机是 **共享设备**：同时只允许一个技能占用。`look_store` / 以后菜单验券是单次快门。步行导航用手机 GPS，**常关相机**。停技能立刻释放。

`start_nav` 前置条件：已有 `currentStore`。没选店就表示要走 → Agent 先推荐或追问，**禁止**空目的开导航。不要求用户说「带我去」三个字。同一轮可以 `select_store` 然后 `start_nav`。

---

## 4. 开场

- 手机：现有到餐对话，没有「开始导航」主按钮，不要教口令。
- 镜片：对话面「Hi, 我在听」+ 圆点头像，上半屏空着。
- 相机关。不要一进 CustomApp 就画箭头。

---

## 5. 语音怎么串（本期）

| 用户说 | Agent | HUD | TTS |
| --- | --- | --- | --- |
| （刚打开） | 无 | 对话面 Hi, 我在听 | 无 |
| 附近火锅 | `recommend` | 第一家卡片 | 摘要 +「去哪家」 |
| 这是哪家 | `look_store` | 认店卡 | 店名评分排队 |
| 排多久 | 读店 | 仍是门店卡 | 直接答 |
| 去第一家 | `select_store` | 仍是门店卡 | 确认店名 |
| 走 / 出发 / 去这家 | `start_nav` | **这时才**导航条 | 「开始去…」 |
| 就去海底捞（没先选） | `select_store` + `start_nav` | 导航条 | 「开始去…」 |
| 要走（没选店） | 追问 / `recommend` | 不进导航 | 「去哪家」 |
| 走着问有包间吗 | 读店，技能仍是 nav | 导航条 | 答完继续走 |
| 换一家不用排 | `stop_nav` + `recommend` | 回到卡片 | 「要去吗」 |
| 取消 / 到了 | `stop_nav` | 门店卡或对话面 | 「导航停了」 |

以后「看看菜单」在已有 currentStore 时切 `menu` 技能，HUD 换菜单皮，拍一张；用户要出发再切回 nav。技能切换由 Agent 决定，用户不进功能页。

---

## 6. HUD

两种版式，不要混用：

- **talk（闲置 / 在听 / 思考 / 纯对话回复）：** 学乐奇。上三分之二空着；下三分之一等宽折 2～3 行绿字 + 圆点头像。听：`Hi, 我在听`。回复是同一块口语，禁止把一句拆成加粗主标题和正文。底栏只显示真时钟。
- **card（门店卡 / 导航条）：** 四行绿字换皮。口播只走 TTS，不要把同一句再拆进 title 和 extra。

导航条例：`去某店` / `右转 80米` / 当前路段短句 / `剩余 320米`。不做乐奇时速 + 小地图。不搬乐奇桌面三个 tab、假天气、系统手势提示。

光波导 **单绿色** HUD，黑底当透明。网页 / Compose 全彩预览不等于镜片观感；手机预览必须用同一套绿字文案和版式。

---

## 7. 本阶段做 / 不做

### P0（必须可演示）

- 到餐 Agent：DeepSeek 规划器 + 可扩展工具（`look_store` / `recommend` / `select_store` / `start_nav` / `stop_nav`）
- 默认对话面；认店 / 推荐后门店卡；仅 `start_nav` 后导航条
- 没选店禁止开导航；有店且用户要走则模型调 tool，不靠固定口令
- 短 TTS；眼镜麦 PCM + 手机 Vosk
- 候选 2～3 家；店内问答（脚本 mock，有 LLM 时走 Agent）
- 真 GPS + 高德步行：剩余米数随定位变，偏航重规划，到店判定
- 手机端绿字 HUD 预览与镜片同一套 talk / card

### P1（骨架预留）

- 菜单识别、扫码验券、结账技能
- 团购详情、收藏 / 最近看过
- 真 VLM / 真美团 POI

### 明确不做

- 把产品做成室内导航 App，或给导航单独开一个手机入口
- 用室内脚本 / 相机帧翻卡片冒充导航
- 一进 CustomApp 就开相机、画箭头
- 真 VLM / 真美团 / 自研室外路网 / 高德导航 SDK 自带地图 UI
- 企业版 `glass3.open.sdk` / `phone.sdk`、P2P JSON、`clientId=GlassDining`
- 把 `apps/glass` 当主应用；把 `apps/phone-nav` 当产品入口
- 用 AIUI Studio / 灵珠网页当本期主工程
- 全彩图像 HUD、AR Studio / UXR、乐奇时速 + 小地图克隆

---

## 8. 职责划分

| 端 | 职责 |
| --- | --- |
| 共享模块 `shared/` | 门店模型、商圈 mock、`DiningSession`、问答脚本、绿字 `HudCard` |
| `nav-api/` | 眼镜 CustomApp 通道与 HUD/导航指令 |
| 手机 `apps/phone` | **主应用 / 产品入口**。乐奇授权拿 CXR token；`CXRLink` + `CUSTOMAPP`；Agent 工具；TTS；GPS + 高德步行；无眼镜时本地预览 |
| 乐奇 AI App | 连眼镜、桥接 IO。本 App 不自己扫 Glass3 蓝牙 |
| 眼镜 CustomApp | 一块 Activity：对话 talk / 门店卡 / 导航条；默认对话面；仅 `look_store` 单次快门 |
| `apps/phone-nav` | 实验室旁路，**不是**产品入口 |
| `apps/glass` | **归档**，仅作历史裸机实验，不参与本期验收 |

数据路径与消费版文档一致：`眼镜 ⇄ 乐奇 AI App ⇄ 本机 CXR-L ⇄ 手机 GPS / 高德步行 / mock 店`。

---

## 9. 工程

```text
apps/phone     主应用：到餐 Agent + CXR-L + 乐奇 + GPS 步行 + 绿字预览
shared         模型 / mock / 匹配 / 问答 / HudCard
nav-api        CustomApp 通道
apps/glass-nav 眼镜 CustomApp（对话面 / 门店卡 / 导航条）
apps/phone-nav 实验室，不当入口
apps/glass     归档（企业/裸机）
```

- Maven：`https://maven.rokid.com/repository/maven-public/`
- 手机：`com.rokid.cxr:client-l:1.0.3`，会话类型 `CUSTOMAPP`，眼镜包名 `com.glass.nav.glass`
- 乐奇包名：`com.rokid.sprite.aiapp`（需 ≥ 1.7.14）
- 消费版 SDK 文档：[open.rokid.com/sdk](https://open.rokid.com/sdk)
- 步行路线：高德 Web 服务 `v3/direction/walking`，密钥 `AMAP_WEB_KEY`（与 DeepSeek 一起放 `ai.env`）
- 目的店坐标：按真 GPS + mock `distanceMeters` 钉在用户身边，禁止用写死的三里屯经纬度当终点

本期 mock 店数据；DeepSeek 当 Agent 规划器，有目的店且用户要出发时调用 `start_nav`。

---

## 10. 后续场景位（不实现）

- 景区导览：识别景点 + 语音讲解
- 游乐园：排队时间与导航
- 外卖取餐：识别商家与订单状态
