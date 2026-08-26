# 视觉分流 POC

到餐 Agent 不复制豆包实时视频通话。参考其已公开的一点：按价值选择视觉信息，而不是把每一帧都送给多模态大模型。

本期只做 **单次拍照 / 稀疏关键帧 → 端侧确定性提取 → 置信度路由 → 必要时云端 VLM**。支付和消费者侧美团券全部 **mock**。

## 公开依据与推测边界

| 来源 | 能确认 | 不能当成本仓库实现 |
| --- | --- | --- |
| [SeedRealtime](https://seed.bytedance.com/zh/seedrealtime) | 豆包视频通话走原生音视频全双工模型，连续建模声音、画面和时序 | 帧率、端侧语义模型、关键帧算法 |
| [火山引擎 MMT](https://developer.volcengine.com/articles/7663051406903312422) | 服务端网关可动态抽帧、要高清图、做局部增强 | 我们自建同等传输网关 |
| [ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) | bundled 中文模型可在端侧抽文字 | 复杂手写菜单一定够用 |
| [ML Kit Barcode](https://developers.google.com/ml-kit/vision/barcode-scanning/android) | bundled 扫码完全端侧 | 扫码等于支付或核销 |
| [美团自助核销](https://developer.meituan.com/docs/biz/biz_tuangouself_87bdad7c-df27-4fe0-9483-433410f44ebc) | 正式链路是授权 → 查券 → 验券准备/执行/撤销 | 本期 mock，不调美团写接口 |

本仓库消费固件没有公开的眼镜实时视频流 API。拍照仍走已验证的 `camera.snap` + `CMD_FRAME`。步行导航仍是手机 GPS + 高德，**常关相机**。

## 数据流

```text
用户语音 / 当前技能
    → 眼镜单次拍照 JPEG
    → 手机画质门控（亮度、清晰度、重复帧）
    → 端侧 OCR + 二维码
    → VisionRouter
         TEXT_ONLY   → 结构化文字给 Agent
         RECAPTURE   → 提示重新对准，不调模型
         UPLOAD_ROI  → 裁剪后送给云端 VLM
         UPLOAD_FULL → 压缩整图送给云端 VLM
    → Agent 调工具
    → 镜片 HUD + TTS
```

云端 VLM 只输出观察和置信度，不改会话状态，不执行支付或核销。

## 路由阈值

画质（灰度 160×120）：

| 项 | 失败条件 | 决策 |
| --- | --- | --- |
| JPEG | 解不出图 | `RECAPTURE` |
| 亮度 | 均值 &lt; 18 或 &gt; 240 | `RECAPTURE` |
| 清晰度 | Laplacian 方差 &lt; 18 | `RECAPTURE` |
| 重复帧 | 2 秒内 16×16 指纹相同 | `RECAPTURE`（画面没变） |

业务：

| 条件 | 决策 |
| --- | --- |
| 二维码已解码（支付/桌码） | `TEXT_ONLY`，**禁止上传原图** |
| OCR 唯一匹配当前商圈门店（最高分 ≥ 6 且明显高于第二名） | `TEXT_ONLY` |
| 菜单 OCR 有菜名且能抽出价格 | `TEXT_ONLY` |
| 路牌/入口 OCR ≥ 4 字 | `TEXT_ONLY` |
| 两家以上门店分数接近、店招无字、菜单排版碎、用户问方位 | `UPLOAD_ROI`，ROI 太小则 `UPLOAD_FULL` |
| 画面可用但端侧什么都没抽到 | `UPLOAD_FULL` |

## 云上传字段

允许：任务名、裁剪/压缩 JPEG、OCR 文本、候选店短名、路由原因。

禁止：完整二维码 payload、券码、支付账号、人脸特写日志。logcat 只记 `decision` / `scene` / `reason` / `ocrChars` / `hasQr` / `storeId`。

## 四个场景

- **导航**：GPS + 高德是事实源。只有用户问入口/路牌才拍一张。VLM 不得判断能不能过马路，也不得改路线。
- **门店详情**：OCR 店名 + `StoreVision` 消歧。评分、排队、营业来自 mock 店数据，不从照片猜。
- **扫码支付 mock**：本地解码后只把类型和 mock 商户 ID 给 Agent，进入确认页。用户明确说确认后才记一笔 mock 成功。HUD 不显示完整码。
- **消费者侧美团券 mock**：按 `currentStore` 列 mock 券。用户选定并确认后返回 mock 回执。扫到桌码只当门店/桌号候选，**不等于核销成功**。

## 真实接入时的边界（本期不做）

支付必须走支付机构确认页和客户授权；条码支付规范要求交易经客户确认后发起。美团核销必须走授权与官方验券接口，并以接口回执为准。模型只能提出候选。

## 验证

单元测试覆盖路由决策（二维码快路、唯一店招、歧义升级、模糊重拍、菜单价格）。真机验收四条语音：看入口/路牌、看门店、扫码买单 mock、使用美团券 mock。导航过程不得发 `camera.on`。
