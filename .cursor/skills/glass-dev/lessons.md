## 2026-08-27 — 屏幕里的店招被 probe 判成不是店

- 场景：对着电脑上的巴奴店招图，抽帧和打分都过了，镜片仍无反应。
- 误判：模型看不见店招；或没打到 Vision。
- 根因：真机 `auto probe isStore=false`。OCR 全是 `logitech`。键盘大字块走了「有大字」公式，han=0 把分压到阈值附近反复掉线。PROBE 规则把「网页/IDE/不确定」写成 false，屏幕里的店招被一票否决。
- 以后先做：无汉字的大字块当噪点，改走亮带公式。probe 规则改成「看得见店招就 true，旁边有键盘不要否决」。本地先拿店招原图 + 屏幕合成图打 Vision，再看真机 `auto probe`。
- 不要做：用 OCR 原文当店名；把 IDE/网页写成绝对 false。

## 2026-08-27 — 自动认店不能拿 OCR 当「是不是店」的开关

- 场景：对着电脑屏幕上的巴奴店招看很久，眼镜无反应。
- 误判：画质或抽帧坏了；或目录没有「巴奴」所以匹配不上。
- 根因：艺术字+屏幕摩尔纹时店招抄不出。OCR 只抄到 `logitech` / IDE 噪点。旧逻辑要求 OCR 连续两帧唯一命中目录才出卡，`decision=UPLOAD_FULL` 时从未打视觉模型。
- 以后先做：端侧只打「像不像店」（大字块/汉字/门头亮带），过线后裁主体图问 Vision `is_store`。不是店则 TTS/HUD 都不动。用户说「识别门店」仍走 `look_store`。
- 不要做：用 OCR 字符串是否命中目录决定要不要问模型；把键盘碎字当成店名。

## 2026-08-26 — 点「开眼镜画面」只看到「视频流已关」

- 场景：USB 还插在眼镜上，手机点开画面，立刻提示「视频流已关」。
- 误判：WebRTC / 720p 采集把流拉挂了。
- 根因：手机 Direct 组已经建好。眼镜 `wifi_on=0`（USB 调试会关 Wi-Fi），`setWifiEnabled` 失败后立刻 `P2P_FAIL`。随后 `PhoneRtc.stop()` / `PhoneP2p.stop()` 把失败文案盖成「视频流已关」。
- 以后先做：关流不要覆盖 abort 原因。眼镜 Wi-Fi 关着时重试到超时，把「请拔 USB / 开 Wi-Fi」显示在手机上。开画面前看 `wifi_on` 和 `GlassP2p wifi was off`。
- 不要做：把「视频流已关」当采集失败；USB 插着时假定眼镜 Wi-Fi 是开的。

## 2026-08-26 — 到餐显示 0 家门店，Download 里其实有 glass-stores.json

- 场景：门店 App 录过望京真店，`Download/glass-stores.json` 约 7KB，到餐界面却是「已加载 0 家」。
- 误判：文件没写上；或 ContentProvider 一 query 到空列表就当没有店。
- 根因：小米上公开 Download 属主是门店 App（`660`），到餐 `File` 读不到。Provider 若返回空/失败，旧代码 `?.let` 把空列表当真结果，不再读文件。应用私有目录里也没有副本。
- 以后先做：目录顺序 Provider → MediaStore Downloads → `filesDir` / `Android/data/.../files` → 公开 Download。空 Provider 要继续往下找。真机看 `catalog provider/mediastore/file n=`。
- 不要做：为了有店再写回 MockCatalog；看到 0 家就当没录入。

## 2026-08-26 — Wi-Fi Direct 探针：手机 createGroup，眼镜 STA 加入

- 场景：户外没路由器也要视频。眼镜已改 USB 调试，允许把原来的 STA Wi-Fi 踢掉。
- 误判：必须企业 P2P SDK；或两边 `discoverPeers` 才能组网。
- 根因：消费固件有 `p2p0`。手机 `WifiP2pManager.createGroup` 当 GO，CXR 把 `DIRECT-` SSID/口令送给眼镜，眼镜 `WifiNetworkSpecifier` 当普通热点客户端，再复用 WebRTC。
- 以后先做：开视频流先看 `PhoneP2p group ready` 和 `GlassP2p joined`。15s 没加入要暴露失败，不要回退现有 Wi-Fi。眼镜定位权限用 `adb pm grant`。
- 不要做：眼镜上走 `connect()` 等系统确认框；把 Direct 口令打进聊天。

## 2026-08-26 — 消费固件有 Wi-Fi Direct 硬件，系统 P2P 默认关着

- 场景：户外没路由器，想绕开「必须同一局域网」做视频。不走企业 `PSecuritySDK`，问 CustomApp 能不能用 Android 原生 P2P。
- 误判：消费固件没有 P2P；或 CXR 没有接口就等于眼镜 Wi-Fi Direct 不存在。
- 根因：固件 `1.24.011`（Android 12）声明了 `android.hardware.wifi.direct`，网卡有 `p2p0`（当前 DOWN）。`dumpsys wifip2p` 是 `P2pDisabledState`，要等 App 调 `WifiP2pManager.initialize()` 才会 ENABLE。属性里有 `persist.p2p.Go.channel=5745`。到餐眼镜 App 还没要 P2P 权限。
- 以后先做：用原生 `createGroup`（手机当 GO）+ CXR 把 SSID/口令送给眼镜，眼镜当普通 STA 加入 `DIRECT-` 热点，避免 `connect()` 弹窗。出网段后再复用现有 WebRTC。真机先验证会不会把当前 STA/无线 ADB 踢掉。
- 不要做：在消费版接企业 `PSecuritySDK`；用 `discoverPeers` 当主路径（镜片没法点系统确认框）。

## 2026-08-26 — 眼镜 WebRTC 探针：信令走 CXR，画面走同 Wi-Fi RTP

- 场景：手机点「开视频流（探针）」，要确认消费固件相机和手机 App 能不能拉到实时画面。
- 误判：CXR CustomCmd 能传视频；或消费固件没有公开视频流 API 就等于相机拉不起来。
- 根因：CXR Caps 只适合 SDP/ICE 信令。RTP 是眼镜↔手机局域网 WebRTC。真机同一 5GHz Wi-Fi 时 ICE 约 250ms 进 CONNECTED。眼镜 Camera2 `camera 0` 能开，无 AF，最低稳定档 15fps；请求 8fps 时采集仍是 15，手机收到约 8fps。编码后第一帧约 320x240，不是采集的 640x480。
- 以后先做：先确认 Direct 组网成功再开流。log 看 `PhoneP2p group ready`、`GlassP2p joined`、`PhoneRtc ice CONNECTED` 和 `frame n=1`。关流后 EglRenderer 仍可能报 0 帧，那是预览还在、PeerConnection 已关。认店/路牌/菜单/券/支付从视频流抽帧，**不要**为拍照停 RTC。
- 不要做：把 CXR/蓝牙当视频通道；用截图判断镜片；以为 8fps 采集参数眼镜真的会出 8fps；看店时 `stopRtc` 再 `camera.snap`。

## 2026-08-26 — 用户话没说完就被收句

- 场景：对着眼镜说话，中间停顿一下，后半句还没出口系统已经开始处理。
- 误判：Vosk 识别太快；或用户说完了。
- 根因：眼镜 PCM 约 100ms 一包，满 10 包低于阈值就收句，大约 1 秒。中文停顿常超过这个长度。轻声也会被当成静音。
- 以后先做：按真实时间 endpoint，静音约 1.8s 且 partial 停 0.5s 再收句；句中用更低的 hold 阈值。
- 不要做：用固定包数当句末；把低于说话阈值的轻声直接当静音。

## 2026-08-26 — TTS 被眼镜麦听回去，AI 会自己打断自己

- 场景：到餐 Agent 正在播回复，话说到一半突然停下，又当成用户说了一句，再进一轮。
- 误判：模型自己截断；或 Vosk 误识别。
- 根因：LLM 一结束 `ask()` 的 `finally` 就把 `GlassAsr.muted=false`，TTS 还在播。眼镜麦收到手机喇叭。旧代码还把这段当 barge-in 去 `PhoneTts.stop()`。`QUEUE_FLUSH` 的旧 `onDone` 也会提前 unmute。
- 以后先做：TTS 期间保持 mute；只认当前 utterance id 的 `onDone`；播完再延迟约 700ms unmute。TTS 播放中丢掉 PCM，不要抢话停 TTS。
- 不要做：模型返回后立刻开麦；用 RMS 阈值在 TTS 期间 barge-in。

## 2026-08-26 — CustomApp 里 startAudioStream 会静默失败

- 场景：到餐切 `CUSTOMAPP` 后点对话，镜片闲置卡在，说话没反应。状态一闪「startAudioStream 未发出」随后变成「镜片到餐页在前台」。
- 误判：乐奇没勾麦克风；或 `hasGlassPermission(MICROPHONE)` 为 false。
- 根因：`AuthorizationHelper` 已是 true。CXR-L `startAudioStream(1)` 仍返回 false（媒体通道在 CustomApp 下发不出去）。`onGlassAppResume` 又把失败文案盖掉。对话 `talking=true`，但没有 PCM，Vosk 无输入。
- 以后先做：CustomApp 用眼镜 `AudioRecord` 16 kHz mono，经 `mic.on` / `pcm` Caps 送到手机 Vosk。`adb shell pm grant com.glass.nav.glass android.permission.RECORD_AUDIO`。不要用 resume 文案覆盖收声失败。
- 不要做：CustomApp 会话里把 CXR-L `startAudioStream` 当眼镜麦；看到「到餐页在前台」就当已经在听。

## 2026-08-26 — 小米拦截新包 USB 安装

- 场景：眼镜 APK `adb install` 成功，新包 `com.glass.nav.phone` 报 `INSTALL_FAILED_USER_RESTRICTED`。
- 误判：签名不对、要 `-g`、或 `pm install --user 0` 能绕过。
- 根因：MIUI「应用安装拦截」拦住了 USB 装的「室内导航」。通知文案：已拦截通过USB安装的室内导航。
- 以后先做：让用户在通知里允许这次安装，再 `adb install -r`。已装过的旧包更新一般不会拦。
- 不要做：对着 `USER_RESTRICTED` 换 install 参数空转。

## 2026-08-25 — Vosk 模型打进 APK，adb push 到 Android/data 看不见

- 场景：重装后点对话提示没有语音模型；`adb ls` 却能看到 `asr/vosk-model-small-cn-0.22`。
- 误判：没推上，或 CXR 会转写。
- 根因：CXR-L 只给 PCM。`adb push` 到 `Android/data/.../files` 属主是 `shell`，MIUI 下 App 看不见。
- 以后先做：模型 zip 进 `assets/asr/`（构建时 `fetchVoskModel`），首次解压到 `filesDir/asr/`。`GlassAsr` 先查内部目录。
- 不要做：只 `adb push` 到 `Android/data/.../files` 就当 App 能加载。

## 2026-08-25 — 乐奇 AI 退出会关掉 CustomView，绿字不回来

- 场景：镜片绿字在，用户用乐奇本机说话；之后眼镜喇叭恢复，到餐 HUD 消失。点「对话」提示「镜片 HUD 还没打开」。
- 误判：没声是到餐 TTS 没走 A2DP；UI 没了是 CXR 断了。
- 根因：乐奇 `onAiExit` / `Sys_Msg_Tts_Stop` 会 `onCustomViewClosed`。到餐只把 `hudOpened=false`，不重开。`openHudIfReady` 见 SDK `customViewIsOpen()==true` 就直接 return，所以点对话也打不开。眼镜本机喇叭不靠手机 App；CustomView 会话会占显示（有时也影响本机播报）。
- 以后先做：`onCustomViewClosed` 后延迟重开 HUD；`hudOpened=false` 时即使 SDK 仍报 open 也要再 `customViewOpen`。用镜片看绿字，不要用截图。
- 不要做：把「眼镜没声」先当成手机 TTS 路由问题；不要用企业版 TTS 接口救消费版喇叭。

## 2026-08-24 — 实时对话：手机开关控制眼镜麦，文字走 DeepSeek

- 场景：点手机「对话」才收眼镜语音，送到 DeepSeek，回复上镜片并 TTS。
- 误判：CXR-L 会把 PCM 变成文字；或 DeepSeek 能直接吃音频。
- 根因：`startAudioStream(1)` 只给 16 kHz / mono / 16-bit PCM。对话走 DeepSeek `stream=true` SSE，镜片用四行各 16 字折行显示完整口语，不再用 JSON `hud` 截成一行。`customViewUpdate` 随 delta 刷新。
- 以后先做：乐奇授权勾选麦克风；HUD 打开后再收声；密钥写工作区 `.env`。对话回复用 `HudCard.fromTalkText` 折四行。认店由 DeepSeek `look_store` tool 决定，不要再用口令 `isLookIntent` 拦截。
- 不要做：没点「对话」就开眼镜麦；把 DeepSeek 密钥写进仓库或打进聊天；TTS 成功就当镜片已更新；把每句 ASR 都当看店。

## 2026-08-24 — CXR-L 拍照是 takePhoto(宽, 高, 质量) + onImageReceived

- 场景：看店要先出一张图，再 mock 认店，把绿字卡片推到镜片。
- 误判：CustomView 会话不能拍照；或要自己在眼镜上开 Camera2。
- 根因：`hasGlassPermission(CAMERA)` 看的是**本次进程**里 `requestAuthorization` 成功后的内存标记，不是磁盘 token。跳过授权页直接 `connect(savedToken)` 时该标记为 false，`takePhoto` 立刻 `No GlassPermission CAMERA`。
- 以后先做：启动时向乐奇申请 MICROPHONE/CAMERA/MEDIA；`parseAuthorizationResult` 成功后再 connect。拍照前再查一次 `hasGlassPermission(CAMERA)`。
- 以后先做：connect 时就 `setCXRImageCbk`；`takePhoto` 设 12s 超时；照片展示在手机 App，认店仍走 `DiningSession` mock。
- 不要做：眼镜没连上或 takePhoto 失败时自动打开手机相机。失败就提示，让用户看手机状态。

## 2026-08-24 — 手机端能接任意 LLM，CXR-L 不管大模型

- 场景：要做眼镜 AI 问答。CXR-L 已通 Custom View。
- 误判：等乐奇或企业 `toAiChat` 当手机端 AI；或 CXR-L 会把 PCM 变成文字。
- 根因：`client-l:1.0.3` 只给连接、HUD、眼镜麦 PCM、拍照。大模型是手机 App 自己的 HTTP。本机企业 AI Chat 仍绑不上。密钥放 `Android/data/com.glass.dining.phone/files/ai.env`（与 `.cursor/glass-ai.env` 同格式）。当前代理返回 401 时链路仍通：HUD `customViewUpdate` + 手机 TTS 会走本地回退。
- 以后先做：先证明 `loaded ai.env` 和 `LLM http=`；401 先换密钥，不要改连接代码。提问走手机输入或系统语音识别；眼镜麦下一步再接 `startAudioStream`。
- 不要做：把密钥写进仓库或打进聊天。

## 2026-08-24 — CXR-L connect 成功判定是双回调，不是 connect 返回值

- 场景：token 已落盘，`CXRLink` 配 `CUSTOMVIEW` 后 `connect(token)`。
- 误判：`connect` 返回 true 就算连上；或要等用户再点乐奇。
- 根因：`connect` 只表示请求发出。就绪是 `onCXRLConnected(true)` 且 `onGlassBtConnected(true)`。随后 `customViewOpen` 成功会收到 `onCustomViewOpened`。乐奇需已连上这副眼镜。真机已确认镜片能看见 `已连接`。
- 以后先做：Application 里单例 `CXRLink`；先 `configCXRSession` 再 `connect`；双回调后再开 HUD。logcat 看 `GlassDiningPhone` / `CXRLink`。用镜片确认，不要用截图。
- 不要做：把 SDK 日志里的完整 token 贴进聊天（`CXRLink: connect by token:` 会打印明文）。

## 2026-08-24 — 乐奇授权页能弹出，CXR-L token 能拿到

- 场景：USB 连上小米 15 Pro（`e1109082` / `2410DPN6CC`），装 `com.glass.dining.phone`，调 `AuthorizationHelper.requestAuthorization`。
- 误判：`AuthorizationHelper: cursor null` 和 `INTERACT_ACROSS_USERS` 会挡授权。
- 根因：那两条日志不致命。乐奇 1.11.11（vercode 10110011）满足 ≥1.7.14。授权页是 `com.rokid.sprite.aiapp/.kuikly.KuiklyRenderActivity`。同意后 token 约 32 字节，写入 `getExternalFilesDir()/cxr-token.txt`。
- 以后先做：`adb devices` 确认是手机再 `installDebug`；Manifest 加 `<queries>` 查 `com.rokid.sprite.aiapp`；logcat 看 `cxr token len=`，不要把完整 token 打进聊天。
- 不要做：把 `cursor null` 当失败停手；线插眼镜时对手机装 APK。

## 2026-08-24 — AI Chat 是问答会话，拍照只是工具

- 场景：用户要像乐奇一样连续对话：提问 → AI 回答并播报。看店识别只是 agent 可调用的能力。
- 误判：把「开始」映射成拍照，热路径做成口令机。
- 根因：官方是 `startAiChat` → `toAiChat(question)` → 流式 `onAiChatAnswer` → TTS；`onAiTakePhoto` 才是设备动作。本机企业 AI Chat 绑不上，自研 host 应对齐这条时序。
- 以后先做：`scripts/glass_ai_host.py` 默认 `tool=none` 只回答；明确「看店/拍照」才 `look`。密钥放 `.cursor/glass-ai.env`（`OPENAI_API_KEY` / `ANTHROPIC_API_KEY`）。
- 不要做：把每句 ASR 都当成看店指令。

## 2026-08-24 — AI 热路径在电脑常驻进程，播报走扬声器拉 WAV

- 场景：用户要和 AI 一直说话，由 AI 认意图并控眼镜。Cursor 暂代 AI，以后换手机云端。
- 误判：电脑脚本听到「开始」就拍照，等于把热路径做成口令机。
- 根因：产品脑在 AI 会话里。inbox 只投递 `{text, ts, hasPhoto}`。回控仍是 `look|next|ask|hud`。Cursor 聊天要靠 `UTTERANCE` 唤醒，不能让用户打「说完了」。
- 以后先做：inbox 不分类、不 ADB；ASR 尽量出完整原话；本窗把每条 UTTERANCE 当作用户对 AI 说的话。以后把同一 JSON 改打到手机云端即可。
- 不要做：在 Mac 脚本或眼镜上写死「开始→拍照」当正式方案。

## 2026-08-24 — 眼镜 POST HTTP 到 Mac 会被 Cleartext 拦截

- 场景：Vosk 已出字 `开始` / `开始认定`，Cursor inbox 仍是 `ping-from-mac`。
- 误判：防火墙挡了 18765，或眼镜没发出请求。
- 根因：targetSdk 34 默认禁止明文 HTTP。`InboxClient` 打 `http://192.168.0.122:18765/utterance` 抛 `Cleartext HTTP traffic ... not permitted`。
- 以后先做：`application` 加 `android:usesCleartextTraffic="true"`（或只放行局域网的 networkSecurityConfig）；logcat 看 `inbox posted` 而不是只看 Mac 侧。
- 不要做：只 curl 本机测 inbox 就当眼镜上报通了。

## 2026-08-24 — 眼镜只上报原话，意图由 Cursor 判断

- 场景：Vosk 把「开始看店」听成「开始开」，眼镜端口令门禁把请求丢掉。
- 误判：要在眼镜上把口令认准才能继续。
- 根因：小模型会吞字；业务判断应在电脑。眼镜 POST `http://<Mac>:18765/utterance` 写入 `.cursor/glass-inbox.json`。回控用 `am start --es cursor_cmd look|next|ask --es hud|text ...`。
- 以后先做：放宽 VAD，任意非空识别都上报；不要在眼镜上 matches 口令。
- 不要做：把「开始开」当识别失败丢掉。

## 2026-08-24 — 「总是断开」是 LMK 杀进程，不是蓝牙掉了

- 场景：HUD 在听或刚拍照，画面突然没了，日志里 Camera disconnect / bindSuccess=false 刷屏。
- 误判：企业服务或蓝牙在反复断连。
- 根因：`GlassSdk.bindSecurityService` 在无 `e` 固件上永远 `bindSuccess=false`，旧代码每 3s 重试，看起来像断开。真正把前台杀掉的是 `lowmemorykiller`：Vosk 模型占 swap ~160MB，再开 Camera2，2GB 眼镜把 `oom_score_adj 0` 的 TOP 进程杀掉。
- 以后先做：绑定失败两次就停；拍照前停麦并 `release` Vosk 模型；不要看 Camera disconnect 当原因（那是进程死后的结果）。
- 不要做：对着 bindSuccess=false 死循环重试。

## 2026-08-24 — 镜腿长按不是按住 NOTIFICATION，而是 PROG_BLUE + 打开乐奇

- 场景：用户长按功能键说话，应用没开始 ASR，反而去拍照，随后进程被杀。
- 误判：长按会让 `keyCode=83` 保持 DOWN。
- 根因：单击是 NOTIFICATION down/up 约 8ms，随后才出 `KEYCODE_PROG_BLUE`，`PhoneWindowManager.launchRokidAI`。Vosk + Camera2 同时开会把 2GB 眼镜打到 LMK。
- 以后先做：前台自动听（VAD），不要用功能键长按当说话开关。拍照时先停麦。
- 不要做：在 83 上做 550ms 长按检测。

## 2026-08-24 — 企业 SDK 没有 ASR 时，眼镜可本地 Vosk 识别

- 场景：`GlassSdk.isReady()` 为 false，官方 `startSpeech` / 离线口令不可用，仍要语音看店。
- 误判：没有企业固件就不能用麦克风；必须等在线 ASR。
- 根因：FAQ 明确 **不支持离线 ASR**，在线 ASR 要企业服务。但硬件是双麦，`AudioRecord` 的 `VOICE_RECOGNITION`（source=6）能开。本机无 TTS 引擎。自研路径：长按功能键录音 + Vosk `vosk-model-small-cn-0.22`（放到 app `files/asr/`）。`adb push` 直接进 `Android/data/.../files` 会 `secure_mkdirs` 失败，先推 `/data/local/tmp` 再 `cp`。单击仍是拍照，长按才是说话。
- 以后先做：`pm grant RECORD_AUDIO`；确认模型目录有 `am/` `conf/`；logcat 看 `app asr recording` / `app asr text=`。
- 不要做：把「口令没反应」只怪词条；不要用 `AudioRecord.read` 阻塞时只设标志而不 `record.stop()`。

## 2026-08-24 — 企业 SDK 绑不上也能用 Camera2 自拍 JPEG

- 场景：`GlassSdk.isReady()` 为 false，官方 `takePhoto` 不可用，仍要拍店招。
- 误判：没有企业 SDK 就不能开相机；必须等 OTA 带 `e` 的固件。
- 根因：`dumpsys media.camera` 有 1 个 Camera HAL 设备 0（Back，API2）。自有 App 申请 `CAMERA` 后可用标准 Camera2：dummy preview Surface + JPEG ImageReader，出 1280×720 JPEG（EXIF `Rokid` / `RG-glasses`）。RokidMirror（`com.rokid.glass.mirrorscan`）占用时会 `ERROR_CAMERA_IN_USE`。眼镜上看不见运行时权限框，要用 `pm grant ... CAMERA`。`mCurrentFocus=MockWindow` 时 `input keyevent` 进不了 Activity，广播 `com.rokid.glass3.action.button.CLICK` 仍能触发。
- 以后先做：`dumpsys media.camera` 看 Active Camera Clients 是否为空；空则走 App Camera2。投屏占用就先停 mirrorscan。
- 不要做：把「绑不上 Security 服务」当成「硬件没有相机」；不要在占用相机时反复重试。

## 2026-08-24 — 这台眼镜是 `1.23.009`（无 `e`），企业 SDK 绑不上

- 场景：要用 `glass3.open.sdk:2.2.0-E` 做拍照/TTS/口令，`bindSuccess = false`。
- 误判：ADB `device` + 能装 APK = 企业 SDK 可用。
- 根因：`ro.build.version.incremental = 1.23.009-20260725-150201`，版本号**不含 `e`**。启动器是 `com.rokid.os.sprite.launcher`。设备上没有 `com.rokid.security.system.server`。FAQ：版本号带 `e` 才是企业/工作系统；企业 SDK 与消费 SDK 完全不同。当前 SDK 推荐 OTA 是 `1.19.e006-20260806-150201` 及以上。
- 以后先做：`adb shell getprop ro.build.version.incremental`，看有没有 `e`。没有就先走企业版 App OTA，不要继续调 `takePhoto`/口令。
- 不要做：在无 `e` 的固件上把绑定失败当成业务 bug。

## 2026-08-24 — 功能键是 KeyEvent，不是广播；Security 服务绑不上则口令全死

- 场景：到店餐饮 HUD 在前台，用户说离线口令、单击功能键都没反应。
- 误判：只监听 `com.rokid.glass3.action.button.CLICK` / `DeviceEventCode.BUTTON_ONE_CLICK`；口令注册失败只怪词条。
- 根因：
  1. 镜腿功能键实际打到 Activity 的 `KeyEvent`：`keyCode=83`（NOTIFICATION）随后 `66`（ENTER）。WindowManager 记 `Unhandled key`。广播和 SDK 按键监听都没到。
  2. `GlassSdk.bindSecurityService` 打出 `bindSuccess = false`。本机没有包 `com.rokid.security.system.server`（SDK 绑的是 `SecurityCoreService`）。`DeviceEventListener`、离线口令、TTS、拍照都不会就绪。
  3. 非有序广播里 `abortBroadcast()` 会抛异常，广播路径更死。
  4. Demo 要求离线口令 3–5 个词、不要叠音。「看看这家店」这类词本身也不适合。
- 以后先做：`logcat` 看 `Unhandled key` 的 `keyCode`；在 `HudActivity.dispatchKeyEvent` 里吃 ENTER/NOTIFICATION。SDK 未就绪时单击仍走本地 mock 出卡片。口令等 `glass sdk ready` 后再验。
- 不要做：假定功能键一定走 Rokid 广播；SDK 没 ready 就怪用户没说话。

## 2026-08-23 — ADB 通了但镜片没画面；HUD 字幕只能自己画

- 场景：RokidMirror 无线投屏 + `192.168.0.124:5555` 已是 `device`，要控制眼镜并显示文字。
- 误判：`am start` 设置页、音量键、滑动「已经成功」= 用户能看见。用 `screencap` 验证显示。
- 根因：
  1. 系统 `mWakefulness=Asleep`，display `OFF`。调试桥通，合成器没在出光。
  2. 前台常为 `MockWindow`，和普通 `Activity` 窗口不是一层。
  3. 默认显示 `FLAG_SECURE`，截图全黑，不能当反馈。
  4. 乐奇字幕层无公开 API；`InstructService` 带 extras 不会出字。
  5. `cmd notification post` / `am --es` 遇到空格会被 shell 截断。
  6. 本机无 `media` 命令，不能用 `media volume`。
  7. 亮度拉到 255 在光机上会刺眼；出厂约 `51`。
- 以后先做：`KEYCODE_WAKEUP` → 确认 `Awake` + `state ON` → 再 `am start`。要一行字就开 `com.noel.glass.hud/.HudActivity`。结束恢复亮度 `51`。
- 不要做：用截图判断成败；为了「看得见」把亮度拉满；把系统设置页当成乐奇 HUD。
