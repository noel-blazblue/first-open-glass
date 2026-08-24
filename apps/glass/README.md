# apps/glass（归档）

消费版乐奇眼镜的主路径是 `apps/phone` + CXR-L，**不要把本模块当正式应用装到眼镜上。**

本模块是早期按 Glass3 企业版 / 裸机写的实验：`GlassSdk`、Vosk、Camera2。当前真机系统版本不含 `e`，`GlassSdk.bindSecurityService` 会失败。

需要企业固件对照时再编译：

```bash
./gradlew :apps:glass:assembleDebug
```
