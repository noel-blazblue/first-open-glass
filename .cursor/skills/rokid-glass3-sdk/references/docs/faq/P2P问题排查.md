# P2P 连接排查

P2P 用于在手机和 Glass3 之间建立点对点通信通道，适合传输文件、图片、音视频流和远程控制数据。

## 1. P2P 适合解决什么问题

| 场景 | 是否推荐 P2P |
| --- | --- |
| 实时视频预览 | 推荐 |
| 大文件传输 | 推荐 |
| 图片或音频数据 | 推荐 |
| 小控制指令 | 蓝牙即可 |
| 建连协商 | 先依赖蓝牙 |

P2P 不是替代蓝牙的第一步。通常需要先完成蓝牙连接，再通过蓝牙触发 P2P 建连。

## 2. 建连前置条件

| 条件 | 要求 |
| --- | --- |
| 蓝牙连接 | 必须先连接成功 |
| Wi-Fi | 手机 Wi-Fi 需开启 |
| 手机热点 | 建连时建议关闭 |
| 权限 | 需要 Wi-Fi、定位、网络相关权限 |
| 系统版本 | SDK 与眼镜系统版本需匹配 |

常见权限包括：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

不同 Android 版本权限要求可能不同，请以目标系统为准。

## 3. P2P 建连流程

```text
手机端权限检查
  ↓
蓝牙连接成功
  ↓
通过蓝牙发送 P2P 建连请求
  ↓
手机与眼镜协商 Wi-Fi P2P
  ↓
建立 TCP/UDP 通道
  ↓
传输文件、消息或音视频数据
```

## 4. 连接不上怎么办

### 蓝牙还没连接

P2P 依赖蓝牙协商。如果蓝牙未连接，P2P 无法建立。请先处理蓝牙连接问题：

[蓝牙连接排查](./蓝牙问题排查.md)

### 蓝牙已连接但 P2P 失败

按顺序检查：

1. 手机 Wi-Fi 是否开启。
2. 手机热点是否关闭。
3. App 是否已获得定位和 Wi-Fi 相关权限。
4. 是否过早发起 P2P 建连。
5. SDK 与眼镜系统版本是否匹配。
6. 是否有系统弹窗或权限被拒绝。

如果仍失败，请导出手机端日志：

```text
/sdcard/Android/data/<手机端APP包名>/files/Documents/mobileLog/
```

## 5. 眼镜通过扫一扫连接热点后 P2P 断开

这类问题通常与 Wi-Fi 网络切换有关。眼镜连接新的 Wi-Fi 或热点时，原 P2P 通道可能被系统重建或断开。

建议：

1. 避免在 P2P 传输过程中切换 Wi-Fi。
2. 先完成联网，再重新建立蓝牙和 P2P。
3. 如需技术支持，提供手机端 `mobileLog` 目录日志。

## 6. P2P 长连接

如果业务需要保持 P2P 长时间在线，可在连接成功后设置 keep-alive。

```kotlin
GlobalData.p2pConnectState.collect(lifecycleScope) {
    connectStatus(GlobalData.btConnectState.value, it)
    binding.tvDeviceName.text = DeviceLinkerManager.getDeviceName()

    if (it && GlobalData.btConnectState.value) {
        PSecuritySDK.getWifiP2PClientService()
            ?.getIP2PConnectControl()
            ?.getKeepP2PConnectState({ keepAlive ->
                if (!keepAlive) {
                    PSecuritySDK.getWifiP2PClientService()
                        ?.getIP2PConnectControl()
                        ?.setKeepP2PConnect(true, { success ->
                            Log.d(TAG, "setKeepP2PConnect result=$success")
                        })
                }
            }, { errMsg: String, code: Int ->
                Log.e(TAG, "getKeepP2PConnectState failed: $code $errMsg")
            })
    }
}
```

## 7. 日志排查

建议关注：

| 日志 | 说明 |
| --- | --- |
| 权限日志 | 是否已获取 Wi-Fi、定位、蓝牙权限 |
| 蓝牙连接日志 | P2P 请求是否已经通过蓝牙发出 |
| `Transport-sdk` | P2P 建连、断开、重连相关日志 |
| 业务收发日志 | 文件、消息、音视频数据是否开始传输 |

提交问题时请提供：

- 手机型号和 Android 版本。
- 眼镜系统版本。
- SDK 版本。
- 是否开启手机热点。
- 是否切换过 Wi-Fi。
- `mobileLog` 日志目录。
