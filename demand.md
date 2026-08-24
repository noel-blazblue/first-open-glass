# First Open Glass — 到店餐饮 MVP

基于 Rokid Glass3 企业版 SDK `2.2.0-E`。当前阶段只落地 **到店餐饮**；景区 / 游乐园 / 外卖取餐只保留场景位，不实现。

数据、识别、POI、团购全部 **mock**。没有眼镜时，用手机端 480×640 HUD 预览走通主路径。

---

## 1. 目标

用户看向餐厅（或在手机上点「模拟看向」），HUD 立刻给出店名、评分、人均、排队、团购；可以用按键或口令换店、问店。不要求拿出手机完成主路径。

**本阶段验收（无眼镜）：** 手机预览 30 秒内走完「看店 → 看团购 → 问排队 → 换下一家」。

**本阶段验收（有眼镜）：** 同一套卡片出现在真 HUD 上；功能键单击识别、双击下一家；TTS 播报摘要。

---

## 2. 本阶段做 / 不做

### P0（必须可演示）

- 门店卡片：店名、品类、距离、营业中/已打烊、评分、评价数、人均、排队（约 X 桌 / Y 分钟）、2 条团购（现价 / 划线价）、识别置信度
- 短 TTS 摘要，例如：「海底捞三里屯店，四点七分，人均一百二，现在大约排二十五分钟」
- 触发：手机「模拟看向这家店」；眼镜功能键单击（`DeviceEventCode.BUTTON_ONE_CLICK` / 广播 `com.rokid.glass3.action.button.CLICK`）
- 候选切换：附近 2～3 家；按键双击 / 口令「下一家」
- 店内问答（脚本 mock，不接真大模型）：人均、排队、招牌菜、团购、适不适合约会/带娃、有没有包间
- 手机端完整详情：地址、电话、营业时间、团购列表、招牌菜、Debug 强制命中某店
- 手机端 HUD 预览（480×640 画布），无眼镜可点玩

### P1（骨架预留，本仓库可有入口但不接真能力）

- 团购详情、附近同类对比、「带我去」TTS 导航句、收藏 / 最近看过
- 拍照触发：眼镜 `takePhoto` → 手机收图 → mock VLM 返回店名（接口留 `imageBase64`，本阶段不传真图）

### 明确不做

- 真 VLM / 真美团 / 真地图 POI
- 眼镜 IMU 朝向锥（公开 SDK **没有** IMU / 朝向接口；手册仅有「陀螺仪 6 轴」规格）
- 景区导览、游乐园排队、外卖取餐
- 独立 HUD SDK（没有 `setHudXxx`；HUD 就是眼镜上的 Android Activity）

---

## 3. 职责划分

| 端 | 职责 |
| --- | --- |
| 共享模块 `shared/` | 门店模型、P2P JSON 协议、商圈 mock、`DiningMatcher`、问答脚本、480×640 HUD Compose |
| 眼镜 `apps/glass` | `GlassSdk` 初始化，`clientId = GlassDining`；HUD；功能键 / 离线口令；收消息刷新卡片；离线 TTS |
| 手机 `apps/phone` | `PSecuritySDK` 初始化；蓝牙/P2P；场景选择器；HUD 预览；详情；Debug；无眼镜时本地跑 mock |
| Mock 引擎 | 不走公网。识别 = 当前商圈 + 触发事件取出默认朝向的一家。以后换真 VLM 只改 `DiningMatcher` |

数据路径与官方建议一致：`眼镜 → 蓝牙/P2P → 手机 → 本机 mock → 回传 HUD`。眼镜无网时可用共享模块本地 mock 兜底，保证 HUD 能单独演示。

---

## 4. 空间匹配（本阶段简化）

目标架构（未来）：Camera → OCR/Logo → GPS → IMU → POI 过滤 → VLM → 门店。

公开文档缺口：

- 眼镜 **无 GPS/GNSS**；SDK 定位权限用于蓝牙扫描 / P2P，不是定位 API。GPS 只能来自手机系统定位，或场景选择器里的 mock 坐标。
- **无 IMU 读数接口**，不做朝向锥。
- **无门店 OCR/Logo/VLM API**（视觉示例只有人脸/车牌）。`LookInput.imageBase64` 预留，当前忽略。

本阶段匹配：`场景（或 mock GPS）半径内的餐饮 POI + 默认朝向第一家`。强制命中某店走 Debug。

---

## 5. HUD 约束（FAQ）

- 分辨率固定 **480×640 竖屏**，不能改
- 中间 480×400 放核心；顶 160 / 底 80 放次要信息
- 顶部约 40px 可能倒影，关键内容整体下移
- 主色绿色、背景黑色、保持常亮（`FLAG_KEEP_SCREEN_ON`）

布局：

1. 顶栏：距离 / 营业状态
2. 主区：店名、评分、人均、排队
3. 中下：两条团购
4. 底栏：口令提示（下一家 / 问问排队）

---

## 6. 消息协议（自定 JSON，不是 SDK 标准协议）

两端 `clientId` 必须为 `GlassDining`。小文本走经典蓝牙，图片/大文件才走 P2P（本阶段只有短 JSON）。

| type | 方向 | 含义 |
| --- | --- | --- |
| `LOOK` | 眼镜 → 手机 | 请求识别，可带 `sceneId` / `imageBase64` |
| `STORE_RESULT` | 手机 → 眼镜 | 命中门店卡片 + TTS 文本 |
| `NEXT` | 眼镜 → 手机 | 下一家 |
| `ASK` / `ANSWER` | 双向 | 语音问答 |
| `TTS` | 手机 → 眼镜 | 仅播报 |
| `SELECT` | 眼镜 → 手机 | 按 `storeId` 选中候选 |

---

## 7. 触发

- 手机按钮：模拟看向 / 下一家 / 快捷问句
- 眼镜功能键单击：LOOK；双击：NEXT
- 离线口令：「看看这家店」「下一家」「排队多久」
- 触控板键值文档不全，本阶段不用触控板当主触发

---

## 8. 工程

```text
apps/glass     眼镜 Android 应用
apps/phone     手机 Android 应用
shared         模型 / 协议 / mock / HUD
```

- Maven：`https://maven.rokid.com/repository/maven-public/`
- 眼镜：`com.rokid.security:glass3.open.sdk:2.2.0-E`，`GlassSdk.bindSecurityService` → `registerClient("GlassDining")`
- 手机：`com.rokid.security:phone.sdk:2.2.0-E`，`PSecuritySDK.initSDK(EngineParam)`，`clientIds` 含 `GlassDining`
- Camera 拍照识别留 P1，第一期不接 `takePhoto` 主路径

---

## 9. 后续场景位（不实现）

- 景区导览：识别景点 + 语音讲解
- 游乐园：排队时间与导航
- 外卖取餐：识别商家与订单状态
