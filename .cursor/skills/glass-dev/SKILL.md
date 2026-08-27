---
name: glass-dev
description: >-
  Field playbook for developing and debugging Rokid Glass3 (and similar ADB
  wearables/HUDs). Use when controlling glasses over ADB or RokidMirror,
  verifying the display is actually on, showing HUD text, installing debug
  APKs, or after a device-side issue is solved and the lesson should be
  recorded. Complements rokid-glass3-sdk (official APIs); this skill stores
  hands-on experience that docs do not.
---

# Glass Dev Playbook

主路径是手机 **CXR-L** + 乐奇把 **CustomApp** 拉到前台；眼镜能力按原生 Android 做，不要用消费版/企业版自我设限。现场坑记在 [lessons.md](lessons.md)。  
企业 `GlassSdk` / `PSecuritySDK` 文档在 [rokid-glass3-sdk](../rokid-glass3-sdk/SKILL.md)，只作对照，不是能力上限。  
本 Skill 管**现场开发**：真机、ADB、显示通路、CXR 连接、验证方法，以及把踩坑写成可复用经验。

## 何时用

- 用户说已经用 RokidMirror / 无线 ADB 连上眼镜，要控制或确认能调试
- 指令“成功了”但用户看不见
- 要在镜片上显示文字、通知、HUD，而不是打开系统设置
- 刚解决一个设备问题，需要记下来避免下次重探

## 工作方式

1. 先读 [lessons.md](lessons.md) 里相关条目，再动手。
2. 用下面的循环，**一次只验证一个假设**。
3. 问题闭环后，按文末模板追加一条 lesson。不要把过程散落在聊天里就结束。

## 调试循环

```text
连上了吗？ → 设备醒着、屏是 ON 吗？ → 用户能看见吗？ → 再用业务操作
```

不要跳步。ADB `device` 只表示调试桥通了，**不等于镜片在显示**。

### 1. 连接

```bash
adb devices -l
```

无线形态一般是 `IP:5555`，`model:RG_glasses`。没有 device 就先修连接，不要发 `am start`。

USB 有线调试和 Wi-Fi 视频**可以同时开**（家里基线）。不要让人拔数据线来开视频。

| 现象 | 实际关系 |
| --- | --- |
| USB 调试刚连上，`wifi_on=0` | 固件常把眼镜 Wi-Fi 关掉。数据线留着，`svc wifi enable` |
| 开 Wi-Fi / Direct 之后 | 有线 USB ADB 不应被当成「被踢掉」。Direct 最多影响 STA / 无线 ADB |
| `adb devices` 空、USB 仍是 Rokid `0x4ee7` | 电脑侧 adbd 握手断了。重启 adb，不要先让人反复拔插 |
| 视频没画面但已收到 answer | 信令走 CXR/蓝牙；RTP 走 Wi-Fi Direct。查 ICE / `192.168.49`，不是查 USB |

### 2. 唤醒与显示通路

每一轮调试开始都确认：

```bash
adb shell dumpsys power | rg mWakefulness
adb shell dumpsys display | rg "state ON|state OFF" | head
adb shell dumpsys window | rg "mCurrentFocus|mFocusedApp"
```

| 现象 | 先做什么 |
| --- | --- |
| `mWakefulness=Asleep` 或 display `OFF` | `input keyevent KEYCODE_WAKEUP`，再查一次 |
| 焦点在 `MockWindow` | 系统合成器和普通 Activity 不是同一层，`am start` 可能“成功但看不见” |
| `screencap` 全黑 | 不要当失败。眼镜常有 `FLAG_SECURE` + 光机黑底，截图几乎没用 |
| 用户说没看到 | 问投屏画面和镜片分别看到什么；用 `mFocusedApp` 证明进程，不要用截图 |

需要用户看见时：**先醒屏，再操作，用 dumpsys 证实焦点，让用户看镜片。**

### 3. 最小可见操作

按目标选最小手段，不要一上来开整页系统设置。

| 目标 | 优先做法 |
| --- | --- |
| 确认能控 | 醒屏 + 打开已知 Activity，查 `mFocusedApp` |
| 一行 HUD 字（类乐奇） | 用轻量黑底绿字 Activity（包名 `com.noel.glass.hud`，若已装） |
| 像乐奇一样写进系统字幕层 | 官方 SDK **没有**公开接口；不要再扫 assistserver 私有 Service |

已装 HUD 调试页时：

```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start -n com.noel.glass.hud/.HudActivity -f 0x10000000 --es text "你的句子"
```

`--es text` **不要带空格**（`adb shell` 会截断）。空格改成 `_` 或短句。

### 4. 环境卫生

调试时改过的系统设置，结束时改回去。

| 项 | 本机已知默认 |
| --- | --- |
| `settings put system screen_brightness` | `51`（不要用 255，光机会刺眼） |
| `settings put system screen_off_timeout` | `5000` |
| `svc power stayon` | `false` |
| `pointer_location` / `show_touches` | `0` |

### 5. 快的做法

- 无线 ADB 单次就有延迟。少 `sleep`，用 dumpsys 替代盲等。
- 并行只跑互不依赖的读命令；写操作（`am start`、安装）串行。
- 同一假设失败一次就换假设，不要重复同一组按键。

## 和官方 Skill 的分工

| 问题 | 去哪 |
| --- | --- |
| 消费版 CXR-L、乐奇授权、CustomView | 本 Skill + `lessons.md` + [open.rokid.com/sdk](https://open.rokid.com/sdk) |
| 企业版 `GlassSdk` / `PSecuritySDK`（仅对照） | `rokid-glass3-sdk` |
| 真机看不见、ADB、HUD、投屏、亮度 | 本 Skill + `lessons.md` |
| 文档没有的系统私有能力 | 写明「文档没有」，记入 lessons，不要当正式 API |

## 如何积累经验

解决新问题后，立刻在 [lessons.md](lessons.md) **顶部**追加一节，格式固定：

```markdown
## YYYY-MM-DD — 短标题

- 场景：
- 误判：
- 根因：
- 以后先做：
- 不要做：
```

只写可复用结论，不要粘完整 log。条目多了再把稳定结论提升到本 SKILL.md 的表格里。
