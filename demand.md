# First Open Glass — 到店餐饮 MVP

基于 **乐奇 AI 眼镜消费版** + 手机 **CXR-L**。当前阶段只落地 **到店餐饮**；景区 / 游乐园 / 外卖取餐只保留场景位，不实现。

数据、识别、POI、团购全部 **mock**。没有眼镜时，用手机里的绿字 HUD 预览走通主路径。

本仓库的眼镜是消费固件（系统版本号不含 `e`）。**不要**再用企业版 `GlassSdk` / `PSecuritySDK` 当主路径。

---

## 1. 目标

用户看向餐厅（或在手机上点「模拟看店识别」），镜片立刻给出店名、评分、人均、排队、一条团购；可以用口令或按钮换店、问店。不要求拿出手机完成主路径。

**本阶段验收（无眼镜）：** 手机预览 30 秒内走完「看店 → 看团购 → 问排队 → 换下一家」。

**本阶段验收（有眼镜）：** 手机已装 **Rokid AI App（乐奇）** 并连上这副眼镜 → CXR-L `onCXRLConnected` 且 `onGlassBtConnected` → CustomView 打开 → 镜片出现绿字门店卡片 → 手机扬声器播 TTS 摘要。

---

## 2. 本阶段做 / 不做

### P0（必须可演示）

- 门店绿字卡片（镜片 CustomView，不是彩色网页，也不是企业版 480×640 Activity）：
  - 店名
  - 评分 · 人均 · 营业中/已打烊
  - 排队（约 X 分钟 / 不用排队）
  - 1 条团购（现价 + 短标题）
- 短 TTS 摘要，例如：「海底捞三里屯店，四点七分，人均一百二，现在大约排二十五分钟」
- 触发：手机「模拟看店识别」（会拍照）；对着手机说「看店识别」；眼镜功能键以后再接
- 候选切换：附近 2～3 家；按钮 / 口令「下一家」
- 店内问答（脚本 mock，不接真大模型也能演）：人均、排队、招牌菜、团购、适不适合约会/带娃、有没有包间
- 手机端完整详情：地址、电话、营业时间、团购列表、招牌菜、Debug 强制命中某店
- 手机端绿字 HUD 预览（与 CustomView 同行文案），无眼镜可点玩

### P1（骨架预留）

- 团购详情、附近同类对比、「带我去」TTS 导航句、收藏 / 最近看过
- 经 CXR-L 从乐奇取眼镜画面，mock VLM 返回店名
- 问答改接灵珠 / 自有 LLM（`PhoneAi` 已留口）

### 明确不做

- 真 VLM / 真美团 / 真地图 POI
- 企业版 `glass3.open.sdk` / `phone.sdk`、P2P JSON、`clientId=GlassDining`
- 把 `apps/glass` 当主应用（那是企业/裸机旁路，消费固件上 `GlassSdk` 绑不上）
- 用 AIUI Studio / 灵珠网页当本期主工程（智能体上架以后再说）
- 全彩图像 HUD、AR Studio / UXR

---

## 3. 职责划分

| 端 | 职责 |
| --- | --- |
| 共享模块 `shared/` | 门店模型、商圈 mock、`DiningMatcher`、问答脚本、绿字 `HudCard` |
| 手机 `apps/phone` | **主应用**。乐奇授权拿 CXR token；`CXRLink` + `CUSTOMVIEW`；mock 认店/问答；CustomView 推卡片；手机 TTS；无眼镜时本地预览 |
| 乐奇 AI App | 连眼镜、桥接 IO。本 App 不自己扫 Glass3 蓝牙 |
| 眼镜 | 显示 CustomView 绿字。不装本仓库的企业 APK |
| `apps/glass` | **归档**，仅作历史裸机实验，不参与本期验收 |

数据路径与消费版文档一致：`眼镜 ⇄ 乐奇 AI App ⇄ 本机 CXR-L ⇄ mock`。

---

## 4. 空间匹配（本阶段简化）

目标架构（未来）：Camera（经 CXR）→ OCR/Logo → 手机 GPS → POI → VLM → 门店。

本阶段匹配：`场景（或 mock GPS）半径内的餐饮 POI + 默认第一家`。强制命中某店走 Debug。

消费版公开文档同样没有给眼镜 IMU 朝向读数，不做朝向锥。

---

## 5. HUD 约束（乐奇 CustomView）

- 光波导 **单绿色** HUD，黑底当透明
- 一次只推短卡片，四行以内
- 网页 / Compose 全彩预览不等于镜片观感；手机预览必须用同一套绿字文案
- `customViewOpen` / `customViewUpdate`；连接成功判定是 `onCXRLConnected(true)` **且** `onGlassBtConnected(true)`，不是 `connect()` 返回值

布局：

1. 店名
2. 评分 · 人均 · 营业状态
3. 排队
4. 团购或问答短句

---

## 6. 触发

- 手机按钮：模拟看店 / 下一家 / 快捷问句
- 手机语音识别：对着手机说话（本期）
- 眼镜侧按键 / 乐奇语音看店：P1，走 CXR 能力后再接

---

## 7. 工程

```text
apps/phone     主应用：CXR-L + 乐奇 + 绿字预览
shared         模型 / mock / 匹配 / 问答 / HudCard
apps/glass     归档（企业/裸机），不作为消费版主路径
```

- Maven：`https://maven.rokid.com/repository/maven-public/`
- 手机：`com.rokid.cxr:client-l:1.0.3`，会话类型 `CUSTOMVIEW`
- 乐奇包名：`com.rokid.sprite.aiapp`（需 ≥ 1.7.14）
- 消费版 SDK 文档：[open.rokid.com/sdk](https://open.rokid.com/sdk)

---

## 8. 后续场景位（不实现）

- 景区导览：识别景点 + 语音讲解
- 游乐园：排队时间与导航
- 外卖取餐：识别商家与订单状态
