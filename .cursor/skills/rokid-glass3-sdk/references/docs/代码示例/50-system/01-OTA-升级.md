# OTA 升级

## 示例说明

演示手机端如何检查眼镜系统版本、准备升级通道、下载更新包并驱动 OTA 流程。

## 使用位置

手机端首页：

- `MainPhoneActivity` -> `SystemOtaActivity`

设置页：

- `SettingActivity` -> `SystemOtaActivity`

示例页面：

- `com.rokid.phone.system.ui.SystemOtaActivity`

## 适用端

- 手机端

## 关键文件

- `glass3sdkphonedemo/app/src/main/java/com/rokid/phone/system/ui/SystemOtaActivity.kt`
- `glass3sdkphonedemo/app/src/main/java/com/rokid/phone/system/OtaManager.kt`
- `glass3sdkphonedemo/app/src/main/java/com/rokid/phone/system/viewmodel/SystemViewModel.kt`

## 流程说明

1. 页面初始化后获取当前系统信息。
2. 用户点击“检测新版本”。
3. 页面检查：
   - 眼镜是否在充电
   - 网络是否可用
   - 蓝牙是否连接
   - OTA 是否已在进行
4. 如果需要更新：
   - 准备 P2P 通道
   - 下载升级包
   - 发送升级文件或指令
5. 页面根据 OTA 状态刷新进度和文案。

## 实现说明

### 为什么 OTA 要求蓝牙和 P2P 都可用

这个项目里：

- 蓝牙链路负责状态控制和部分指令
- P2P 链路负责更大的数据传输

OTA 天然属于大文件和多阶段状态同步场景，所以两条链路都会参与。

### 为什么要求设备在充电状态

这是升级安全策略的一部分，避免升级过程中因为电量不足导致失败或中断。

## 注意事项

- 页面内有较多状态保护和重复点击保护。
- 页面退出时会根据当前 OTA 阶段决定是否允许退出。
- 这是整个仓库里业务状态最复杂的页面之一，后续单独抽文档时可以继续细分“检查版本”“下载包”“下发升级”“状态回传”四段。

