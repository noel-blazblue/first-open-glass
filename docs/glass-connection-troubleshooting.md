# 眼镜连接故障总文档

连接问题先读本文，再改代码。不要把五条链路混称为「眼镜连不上」。闭环后同步更新本文和 `.cursor/skills/glass-dev/lessons.md`。

眼镜 APK **只能**电脑 ADB 直装，见 `AGENTS.md` 硬规则与 `:apps:glass-nav:installGlassAdb`。

## 五条链路（外加 PCM）

| 链路 | 成功才算什么 | 失败时不要查什么 |
| --- | --- | --- |
| 电脑 ADB | `adb devices -l` 出现 `model:RG_glasses`，`adb -s <serial> shell echo ok` | CXR、Direct、画面 |
| CXR / 蓝牙 | `onCXRLConnected=true` 且 `onGlassBtConnected=true`，CustomCmd 能收 `ready` | Wi-Fi、ICE |
| 眼镜 Wi-Fi | `wifi keep state=` 为 ENABLED，`setWifiEnabled=true` 或随后 `WIFI_STATE_ENABLED` | 拔 USB、电脑手动 `svc wifi enable` |
| Wi-Fi Direct | 手机 `group ready` 且眼镜 `groupFormed && !isGroupOwner` **且** 真实 `192.168.49.x` | 猜 `.2`、走 `wlan0` STA |
| WebRTC | SDP/ICE 使用 `192.168.49.x`，手机收到首帧 | USB、乐奇版本 |
| PCM 语音 | log 周期性 `pcm n=`，说话后有 utterance | 把 pose 当心跳 |

手机 UI「连接诊断」按层显示：CXR、眼镜 App、麦克风、眼镜 Wi-Fi、Direct、RTC。Agent Context 是环境记忆，不是连接状态。

## 成功判据与状态迁移

`VisionLinkCoordinator` 单 attempt、串行 phase：

`Idle → CxrReady → GlassStarting → GlassReady → WifiEnabling → GroupCreating → PeerJoining → P2pReady → RtcStarting → Streaming`

| Phase | 必须看到 | 超时后 |
| --- | --- | --- |
| CxrReady | 双回调都 true | 不能开流 |
| GlassStarting | `appStart` 一次，随后 `cmd=ready` | 停在失败原因，禁止再 appStart 刷 |
| GlassReady | `CMD_READY`；**此前禁止** `p2p.offer` | — |
| GroupCreating | `PhoneP2p group ready`，同 attempt 只建一次组 | `removeGroup` 后才允许用户重试 |
| PeerJoining | 眼镜 `joined as p2p client ip=192.168.49.*` | 完整清理，不换另一套入网 |
| RtcStarting | `rtc.ready` 再本地 `startOffer` | 保留本 phase 失败原因 |
| Streaming | 首帧；PCM 仍应继续 | — |
| Stopping / Failed | RTC stop、P2P removeGroup、无孤儿组 | 失败原因不被「视频流已关」覆盖 |

## 固定诊断顺序

1. `adb devices -l`：有没有眼镜、有没有手机。命令一律 `adb -s <serial>`。
2. 手机连接诊断面板：哪一层不是「已就绪」。
3. 手机 log：`adb -s <phone> logcat -s GlassDiningPhone:I PhoneP2p:I PhoneRtc:I`
4. 眼镜 log：`adb -s <glass> logcat -s GlassP2p:I GlassRtc:I NavActivity:I`
5. 只修当前层。不要同时重装 APK、重连乐奇、重建 Direct。

最小命令：

```bash
adb devices -l
adb -s <glass> shell dumpsys package com.glass.nav.glass | rg "versionName|versionCode|targetSdk"
adb -s <glass> shell settings get global wifi_on
adb -s <glass> shell dumpsys wifi | rg "Wi-Fi is |mWifiState"
adb -s <phone> shell dumpsys wifi | rg "mP2pGroup|networkName|interface"
```

**禁止当作修复的操作**

- 通过手机或 CXR `appUploadAndInstall` 装眼镜 APK
- 助手用 ADB 替产品打开眼镜 Wi-Fi（`svc wifi enable`）
- 为开 Wi-Fi 或视频让人拔 USB
- 半连接时从 P2P 切到 STA 或反过来

## 已确认原因

### 1. 自动 APK 上传与开流竞态

- 现象：开对话/开画面后 Direct 卡住，log 里先 `upload glass apk`，马上 `p2p.offer`，再 `appStart`，随后 `onInstallAppResult=false` 又一次 `appStart`。
- 根因：手机 `installDebug` 把眼镜 APK 推进手机目录，`GlassApkSync` 在 CXR 就绪后无版本比较地 `appUploadAndInstall`。上传未完成就开始建组和发 offer。
- 关键日志：`upload glass apk bytes=`、`onInstallAppResult=false`、连续两次 `appStart`。
- 最终设计：删除上传链路。眼镜只走 `installGlassAdb`。CXR 只查询、启动、停止 CustomApp。
- 禁止再做：任何「连上手机就自动更新眼镜包」的捷径。

### 2. offer 早于 App ready

- 现象：眼镜还没 `CMD_READY`，手机已经 `p2p.offer`。
- 根因：`kickOpenVisionStream` 只看 CXR 双回调，对话一开始就 `beginRtc`。
- 最终设计：`VisionLinkMachine` 必须 `GlassReady` 后才能 `CreateGroup` / `SendOffer`。
- 禁止再做：用 `onOpenAppResult` 或 `hudOpened` 代替 `CMD_READY` 去发 offer。

### 3. 重复 appStart

- 现象：一次连接里 `appStart` 两次，镜片闪、Wi-Fi hold 被打断。
- 根因：安装失败回退 `queryAndStart`，以及 resume / 开流再拉一次。
- 最终设计：`appStart` 2s 去重；安装回调直接忽略。
- 禁止再做：安装失败再自动 start。

### 4. STA 与 P2P 并发

- 现象：`p2p0 Connect ... SUCCESS` 的同时 `wlan0` 反复连 `DIRECT-…`。
- 根因：`GlassStaJoin` 每 2.5s `disconnect+reconnect`，8s 后 `discoverPeers` 兜底，两套状态机抢射频。
- 最终设计：产品路径只有原生 `WifiP2pManager.discoverPeers/connect`。删除 `GlassStaJoin`。
- 禁止再做：SSID+口令当普通热点与 p2p0 同时跑。

### 5. WPA3 association reject

- 现象：眼镜 STA 把手机 GO 识别成 WPA3，`ASSOCIATION_REJECT`。
- 根因：legacy `WifiConfiguration` 无法正确加入 WPA3 Direct GO。
- 最终设计：不再走 STA。
- 禁止再做：用 `WifiConfiguration` 加 Direct 组。

### 6. USB 固件关闭眼镜 Wi-Fi

- 现象：`wifi_on=0`，Direct 永远加不进。
- 根因：USB 调试枚举后固件关 Wi-Fi。
- 最终设计：CustomApp 进程启动 `GlassWifi.hold()`，App 自己打开。USB 留下。
- 禁止再做：让助手 ADB 开 Wi-Fi；为开 Wi-Fi 拔线。

### 7. targetSdk 限制 setWifiEnabled

- 现象：App 调用开 Wi-Fi 无效果。
- 根因：`targetSdk 34` 时 `WifiManager.setWifiEnabled` 被拒绝。
- 最终设计：眼镜 CustomApp `targetSdk 28`；ENABLING 不重入。
- 禁止再做：ENABLING 时反复 enable。

### 8. ICE 未使用 192.168.49.x

- 现象：信令通、0 帧。
- 根因：候选落在别的网卡。
- 最终设计：两端优先 Direct 网段；眼镜 ready 必须真实 `192.168.49.x`，禁止猜 `.2`。
- 禁止再做：`groupFormed` 就报 ready。

### 9. 高频 pose 挤占 PCM

- 现象：对着眼镜说话 AI 不答，log 全是 `cmd=pose`，没有 `pcm n=`。
- 根因：100ms 姿态占满 CXR。
- 最终设计：麦开着不发 pose；导航且没说话、转头 ≥12° 才最多 1s 一次。
- 禁止再做：把 pose 当常驻心跳。

### 10. ADB 握手断开

- 现象：USB 仍是 Rokid `0x4ee7`，`adb devices` 空。
- 根因：电脑侧 adbd 握手断，不是眼镜没电。
- 最终设计：重启 adb；不要先让人反复拔插。安装前 devices 里必须有眼镜。
- 禁止再做：devices 空时改走手机装眼镜包。

## 重试边界

- 同一 attempt：不重复 `createGroup` / `removeGroup`；同 SSID 不重连。
- PeerJoining 超时：一次完整清理（RTC stop + removeGroup），回到 Idle，保留失败原因。
- 用户再点「开眼镜画面」才允许新 attemptId。
- 失败原因不得被「已关视频流」覆盖。
