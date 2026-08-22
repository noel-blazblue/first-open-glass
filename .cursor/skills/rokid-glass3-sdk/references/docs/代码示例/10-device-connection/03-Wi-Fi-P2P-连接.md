# Wi-Fi P2P 连接

## 示例说明

演示如何在手机端单独发现 Glass 设备并建立 Wi-Fi P2P 连接。

## 使用位置

示例页面：

- `com.rokid.phone.ui.WifiP2PSettingActivity`

## 适用端

- 手机端

## 关键文件

- `glass3sdkphonedemo/app/src/main/java/com/rokid/phone/ui/WifiP2PSettingActivity.kt`
- `glass3sdkphonedemo/app/src/main/java/com/rokid/phone/DeviceLinkerManager.kt`

## 流程说明

<div class="sample-flow">
  <div class="sample-flow-step" data-step="1">
    <strong>初始化 P2P</strong>
    <span>注册监听器并调用 <code>initialize()</code>。</span>
  </div>
  <div class="sample-flow-step" data-step="2">
    <strong>扫描设备</strong>
    <span>调用 <code>startDiscoverPeers()</code> 发现周边 P2P 设备。</span>
  </div>
  <div class="sample-flow-step" data-step="3">
    <strong>筛选目标</strong>
    <span>在 <code>onPeersAvailable()</code> 中筛选 Glass3 设备。</span>
  </div>
  <div class="sample-flow-step" data-step="4">
    <strong>建立连接</strong>
    <span>找到目标设备后调用 <code>connectDevice()</code>。</span>
  </div>
</div>

1. 页面初始化后注册 `IWifiP2PClientListener`。
2. 调用 `initialize()` 初始化 P2P 通道。
3. 调用 `startDiscoverPeers()` 扫描设备。
4. 在 `onPeersAvailable()` 中筛选名字包含 `Glass3_` 的设备。
5. 找到目标设备后调用 `connectDevice()`。
6. 成功后保存 P2P 设备信息。

## 相关 API

- [手机端 API：Wi-Fi P2P 与 AR Mix](/terminal-sdk/api-reference/Glass3%20%20SDK(%E6%89%8B%E6%9C%BA%E7%AB%AF)%20API%E6%96%87%E6%A1%A3.md#phone-p2p)

## 使用说明

这个示例只验证 P2P 能力，不包含经典蓝牙配对流程。

如果业务已经建立蓝牙链路，只需要补充大文件传输或视频流能力，可以复用这里的 P2P 初始化、扫描和连接逻辑。

## 注意事项

- 当前匹配逻辑依赖 `DeviceLinkerManager.getDeviceName()`。
- 如果 Wi-Fi 未开启，页面只会提示，不会继续扫描。
- 成功后记得保存设备信息，后续自动重连会依赖它。
