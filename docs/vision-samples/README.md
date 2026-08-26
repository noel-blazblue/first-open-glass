# 眼镜实拍样本

验收视觉分流时，用眼镜拍下列四类，每类至少 30 张，放到本目录对应子文件夹（不要提交含人脸、完整付款码、券码的原图）。

| 目录 | 拍什么 | 期望路由 |
| --- | --- | --- |
| `store-sign/` | 店招、品牌字 | 唯一店名 `TEXT_ONLY`；Logo/艺术字 `UPLOAD_*` |
| `menu/` | 纸质或屏幕菜单 | 有菜名+价格则 `TEXT_ONLY` |
| `qr-pay/` | 门店付款码（可打码后留格式） | 必须 `TEXT_ONLY`，禁止上传原图 |
| `negatives/` | 模糊、逆光、走路晃动 | `RECAPTURE` |

跑共享模块单测覆盖合成样本：

```bash
./gradlew :shared:testDebugUnitTest
```

真机四条语音：看入口/路牌、看门店、扫码买单 mock、使用美团券 mock。导航过程 logcat 不应出现 `camera.on`。
