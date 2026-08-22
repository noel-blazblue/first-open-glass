# Rokid Glass3 QRCode Scanner SDK 接入文档

`scanner` 是 Rokid Glass3 二维码/条形码扫描 SDK，支持以下使用方式：

- 打开 SDK 内置扫码页面，通过相机实时扫描。
- 将 `GlassQrScannerView` 嵌入业务页面，自定义扫码页面布局和交互。
- 传入 `Bitmap`，识别图片中的二维码或一维码。

## 1. 添加依赖

在 App 模块的 `build.gradle` 中添加依赖：

```groovy
dependencies {
    implementation 'com.rokid.security.glass3.qrcode:scanner:1.0.6'
}
```

如果工程使用 Kotlin DSL：

```kotlin
dependencies {
    implementation("com.rokid.security.glass3.qrcode:scanner:1.0.6")
}
```

## 2. 打开 SDK 内置扫码页面

适用于需要快速接入完整相机扫码页面的场景。

```kotlin
import com.google.mlkit.vision.barcode.common.Barcode
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.api.GlassScanner

GlassScanner.launch(
    this,
    config = GlassScanConfig(enableAutoClose = true, scanType = ScanType.QR_CODE_ONLY, cameraZoomLevel = 5),
    scanCallback = object : GlassScanCallback {
        override fun onScanSuccess(content: String?, barcode: Barcode) {
            log("扫描成功：${content}")
        }

        override fun onScanFailure(error: String) {
            log("扫描失败：${error}")
        }
    })
```

默认识别到结果后，SDK 会自动关闭扫码页面，并通过 `onScanSuccess` 返回识别结果。

## 3. 自定义内置扫码页面

`GlassScanner.launch(...)` 支持传入 `GlassScanConfig`，可以配置扫描类型、标题、取景框和相机缩放等级。

```kotlin
import com.rokid.security.glass3.qrcode.model.GlassScanConfig
import com.rokid.security.glass3.qrcode.model.ScanType

val config = GlassScanConfig(
    scanType = ScanType.QR_CODE_AND_BARCODE,
    enableAutoClose = true,
    customTitle = "请扫描二维码或条形码",
    viewfinderFrameRatio = 0.7f,
    viewfinderVisible = true,
    cameraZoomLevel = 2
)

GlassScanner.launch(
    activity = this,
    config = config,
    scanCallback = object : GlassScanCallback {
        override fun onScanSuccess(content: String?, barcode: Barcode) {
            log("扫描成功：$content")
        }

        override fun onScanFailure(error: String) {
            log("扫描失败：$error")
        }
    }
)
```

配置项说明：

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `scanMode` | `ScanMode` | `ScanMode.CAMERA` | 扫描模式。通过 `launch` 打开页面时使用相机模式。 |
| `scanType` | `ScanType` | `ScanType.QR_CODE_ONLY` | 扫描类型，取值见下表。 |
| `enableAutoClose` | `Boolean` | `true` | 识别成功后是否自动关闭扫码页面。 |
| `customTitle` | `String?` | `null` | 扫码页面标题。为空时使用 SDK 默认标题。 |
| `viewfinderFrameRatio` | `Float` | `0.62f` | 取景框占控件短边的比例，有效范围为 `0.1f..1.0f`。 |
| `viewfinderVisible` | `Boolean` | `true` | 是否显示取景框遮罩、扫描线和提示文案。隐藏后不影响预览和识别。 |
| `cameraZoomLevel` | `Int` | `1` | 相机缩放等级，有效范围为 `1..8`，`1` 表示不缩放。 |

也可以通过辅助方法生成裁剪到有效范围内的新配置：

```kotlin
val config = GlassScanConfig()
    .withViewfinderFrameRatio(0.7f)
    .withViewfinderVisible(true)
    .withCameraZoomLevel(2)
```

`ScanType` 取值说明：

| 枚举 | 说明 |
| --- | --- |
| `QR_CODE_ONLY` | 仅识别二维码。 |
| `BARCODE_ONLY` | 仅识别常见一维码，包括 Code 128、Code 39、Code 93、Codabar、EAN-13、EAN-8、ITF、UPC-A 和 UPC-E。 |
| `QR_CODE_AND_BARCODE` | 同时识别二维码和上述常见一维码。 |
| `ALL_FORMATS` | 识别 ML Kit 支持的所有条形码格式。 |

## 4. 嵌入业务页面扫码

适用于需要自行设计页面布局、控制扫码启停或连续接收结果的场景。

先在布局中添加 `GlassQrScannerView`：

```xml
<com.rokid.security.glass3.qrcode.view.GlassQrScannerView
    android:id="@+id/scannerView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

然后在 Activity 或 Fragment 中启动扫描：

```kotlin
import com.google.mlkit.vision.barcode.common.Barcode
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.model.ScanType

binding.scannerView.startScan(
    callback = object : GlassScanCallback {
        override fun onScanSuccess(content: String?, barcode: Barcode) {
            log("扫描成功：$content")
        }

        override fun onScanFailure(error: String) {
            log("扫描失败：$error")
        }
    },
    scanType = ScanType.QR_CODE_AND_BARCODE,
    scanIntervalMs = 400,
    stopOnSuccess = true,
    cameraZoomLevel = 2
)
```

`startScan` 参数说明：

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `callback` | `GlassScanCallback` | 必填 | 扫描结果回调。 |
| `scanType` | `ScanType` | `QR_CODE_ONLY` | 扫描类型。 |
| `scanIntervalMs` | `Int` | `400` | 两次 ML Kit 识别之间的最小间隔；小于 `100` 时按 `100` 处理。 |
| `stopOnSuccess` | `Boolean` | `false` | 成功后是否停止识别。设为 `false` 时会继续扫描并可能重复回调。 |
| `cameraZoomLevel` | `Int` | `2` | 相机缩放等级，有效范围为 `1..8`。 |

运行期间可以控制扫码和取景框：

```kotlin
binding.scannerView.stopScan()
binding.scannerView.resumeScan()

binding.scannerView.setViewfinderFrameRatio(0.7f)
binding.scannerView.hideViewfinder()
binding.scannerView.showViewfinder()

val visible = binding.scannerView.isViewfinderVisible()
```

`GlassQrScannerView` 会自动监听宿主 `LifecycleOwner`：

- 宿主进入后台时暂停相机预览和识别。
- 宿主恢复时恢复相机预览和识别。
- View 从窗口移除或宿主销毁时释放相机、识别器和 GL 预览资源。
- 如果宿主没有 `LifecycleOwner`，请在页面销毁时主动调用 `release()`。
- 调用 `release()` 后如需再次扫码，重新调用 `startScan(...)`。

## 5. 扫描 Bitmap 图片

适用于识别相册图片、截图、网络图片或业务侧已有 `Bitmap` 的场景。该方式不会打开扫码页面。

```kotlin
import com.google.mlkit.vision.barcode.common.Barcode
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.api.GlassScanner
import com.rokid.security.glass3.qrcode.model.ScanType

val bitmap = FileUtils.loadBitmapFromAssets(this, "qrcode.jpg") ?: run {
    log("加载图片失败")
    return
}

GlassScanner.scanFromBitmap(
    context = this,
    bitmap = bitmap,
    scanType = ScanType.QR_CODE_AND_BARCODE,
    callback = object : GlassScanCallback {
        override fun onScanSuccess(content: String?, barcode: Barcode) {
            log("扫描成功，内容为：$content，barcode=${barcode.rawValue}")
        }

        override fun onScanFailure(error: String) {
            log("扫描失败：$error")
        }
    }
)
```

## 6. 回调说明

实现 `GlassScanCallback` 获取扫描结果：

```kotlin
interface GlassScanCallback {
    fun onScanSuccess(content: String?, barcode: Barcode)

    fun onScanFailure(error: String)

    fun onScanCancelled() {}
}
```

| 回调 | 触发时机 |
| --- | --- |
| `onScanSuccess(content, barcode)` | 扫描成功。`content` 为识别内容，`barcode` 为 ML Kit 原始 `Barcode` 对象。 |
| `onScanFailure(error)` | 扫描失败、初始化失败或图片中未识别到目标条码。 |
| `onScanCancelled()` | 用户按返回键退出 SDK 内置扫码页面。该回调可按需实现。 |

## 7. 接入建议

- 只需要标准扫码页面时，优先使用 `GlassScanner.launch(...)`。
- 需要将预览嵌入业务 UI、连续扫码或自行控制启停时，使用 `GlassQrScannerView`。
- 连续扫码时建议根据业务提高 `scanIntervalMs`，并在不需要识别时调用 `stopScan()`，减少眼镜端 CPU、相机和内存开销。
- 不需要重复结果时，将 `stopOnSuccess` 设为 `true`。
