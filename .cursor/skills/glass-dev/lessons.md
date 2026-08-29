## 2026-08-29 — 动态 HUD 左边被光机裁掉

- 场景：`draw_hud` 画出来的线和字，镜片左边缺一块，像超出屏幕。
- 误判：眼镜没重装新 renderer；或 CXR 把画面裁了。
- 根因：协议原先把坐标夹到 0–480。光机可见区比 framebuffer 窄，贴左边的 path/text 会出安全区。官方约定顶约 40px 倒影、底约 80px。
- 以后先做：`HudDraw` 夹到 x=64–416、y=56–552，prompt 写明不要贴边。圆/矩形/填充要改眼镜 `HudDrawRenderer`，必须 `installGlassAdb`。
- 不要做：为了「看得见」把坐标再放到 x=0；只装手机期望旧眼镜能画 circle/rect。

## 2026-08-28 — ADB 空但 USB 还在：先看是不是镜腿折叠关机

- 场景：线还插着，RokidMirror 报 Could not find any ADB device / Server connection failed。电脑 `system_profiler` 仍有 Rokid `0x4ee7`，`adb devices -l` 空。
- 误判：开 Wi-Fi / Direct 踢掉了 USB 调试；或电脑 adbd 被 `kill-server` 弄挂；或发烫把 USB 烫断了。
- 根因：眼镜 `sys.boot.reason=shutdown,leg_fold_timeout`。摘下并折叠镜腿后固件超时关机，USB 设备还在枚举，adbd 已经没了。恢复后 `uptime` 只有一两分钟，内核有 `USB_STATE=DISCONNECTED` 再 `CONNECTED/CONFIGURED`。
- 以后先做：ADB 空先 `getprop sys.boot.reason` 和 `/proc/uptime`。是 `leg_fold_timeout` 就醒眼镜或展开镜腿等它重新开机，不要拔线、不要当投屏坏了。
- 不要做：把折叠关机说成「电脑认不到 USB」；为恢复 ADB 反复拔插；用发烫单独解释 USB 消失（发烫常导致人摘下折叠，关机原因写在 bootreason）。



- 场景：说话最后一个字还没看见，镜片已经变成思考或 AI 回复。
- 误判：ASR 丢字；或字幕折行把末字裁掉。
- 根因：NLS 末字常只出现在 SentenceEnd。`onEndpoint` 立刻换成「思考中」，同一帧 `ask` 又盖掉完整句。
- 以后先做：`HeardAskGate` 先 `listening(全文)` 停 500ms 再思考。log 看 `nls sentence=` 后 HUD 应先是听写全文，不应马上 `思考中`。
- 不要做：endpoint 和 utterance 同一帧清听写。

## 2026-08-28 — 对话字幕要离开人物头顶

- 场景：AI 回复贴着灵梦，字的下沿被挡住。
- 误判：字号太大；或折行宽度不够。
- 根因：480×640 上人物 38% 高、中心在 76%，字幕基线几乎叠进头顶，再加上 descent 和上下浮动。
- 以后先做：`TalkLayout` 人物更小更靠下，基线预留 gap + bob + descent。预览和镜片用同一套。
- 不要做：只加 Compose `padding(top=4.dp)` 当镜片间距。

## 2026-08-28 — 工具前过渡句要接着播，不要熔断成没回上

- 场景：问附近门店，模型先说「帮你看下」再调搜索，耳机却念「这次没回上」。
- 误判：模型坏了；或搜索工具没配。
- 根因：流式把 content+tool_calls 当协议错误整轮失败，工具没执行。语音状态机只按单步直答设计。
- 以后先做：混流保留过渡句并执行工具。工具返回后 `finishStep` 再 `speakMore` 接结果，不 `flush` 掐掉上一句。Prompt 允许极短过渡，禁止结果回来前猜事实。log 看 `protocol mixed` 后应有 `tools batch` 和 `phase=tool-step`，不应马上 `没回上`。
- 不要做：混流直接 `failed`；工具步把 TTS `stop` 再重开；把过渡句当最终答案。

## 2026-08-28 — 流式结束不能把同一句再送进 TTS

- 场景：模型只回了一句「你好！需要我帮你做点什么吗？」，耳机却连播两遍。
- 误判：模型生成了两次；或只是 NLS 免费试用过期导致 REST 念两遍。
- 根因：增量 `main.post` 后，结束回调把全文再喂给分段器；更短的迟到 partial 会清零重切。WS 失败转 REST 时若再开新 WS，或把已入队全文再 enqueue 一次，就会叠播。
- 以后先做：delta 与 finish 同 Main 队列。结束只 flush 余量。迟到短增量丢弃。一轮 generation 只钉一个后端，降级 leftover 按已听边界切，后续片段用 covered 偏移去重。`finishInput` 可在握手前挂起。抢话只在 `AUDIBLE`。log 看 `phase=enqueue` 同句不应出现两套相同 chars。
- 不要做：`done=true` 再 consume 一遍全文；缩短 partial 就 reset 重播；WS 失败后同轮再 `new WebSocket`。

## 2026-08-28 — Agent 流式回复必须声音驱动字幕

- 场景：问答时镜片立刻出全文，耳机里的 TTS 还在一段段 REST 合成；插话会被尚未发声的全文误判成回声。
- 误判：WebRTC 音画不同步；或只要把 `onDelta` 接到 HUD 就算流式。
- 根因：主路径 `PhoneAi.complete()` 整段结束才开口。HUD 走 `hud.card`，TTS 等完整 PCM。回声过滤用了队列里的全文。
- 以后先做：每步模型走可取消 SSE；混合 content+tool_calls 当协议错误。字幕等 `AUDIBLE` 后按字级时间戳滚动。回声只比 `echoWindow`。NLS FlowingSpeechSynthesizer 失败回退 REST 有序预取。log 看 `voice turn=` / `phase=llm-ttft|enqueue|audible|idle`，不要打全文和密钥。
- 不要做：完成后再把全文当 delta 甩一次；工具参数/reasoning 上屏或播报；合成中用未出声文本杀用户打断。

## 2026-08-28 — 环境已捕捉门口，不等于导航会立刻画箭头

- 场景：log 已有 `topology kind=ENTRANCE` 和 `space=entrance`，镜片仍无出口箭头。
- 误判：VLM 没看见门；规划器不会跟入口；该写死朝前铺路。
- 根因：环境提交和导航 HUD 是两条线。`onSemantic` 只写观察/拓扑，`onCommitted` 原先不重跑室内 hint。后续 HUD 多来自 GPS 只改 remaining，旧 hint 没有 waypoints。VLM 线程写观察、抽帧线程读，字段也没有 happens-before。
- 以后先做：门口提交后立刻 `bootstrapHint` 推带 waypoints 的 hint。log 看 `indoor refresh from look` 和 `indoor hint ... guide=true`。
- 不要做：用「捕捉到门口」当箭头已发出；无 waypoints 就朝前编假路。

## 2026-08-28 — 室内箭头要接 VLM 出口，不要写死 6 米

- 场景：对着门没有箭头，HUD 却固定显示 6 米。
- 误判：室内导航做不了；或者没箭头就该在屏幕下沿画假路。
- 根因：规划器不消费 `entrance/exits`；手机 XYZ 路点配眼镜 VIO 投丢；`ExploreHint`/`landmarkGround` 默认 6 米；无导视仍朝前铺 chevron。
- 以后先做：VLM 填了出口才 `hasGuide`，眼镜只用本地 pose 投影。未知不报米数。log 看 `indoor stage=SEEK_EXIT` 与环境 `exits`/`spaceType=entrance`。
- 不要做：写死 6 米；无导视编朝前的路；用手机 waypoint 坐标在眼镜上投。

## 2026-08-28 — 镜片「定位丢失」和漂浮三角不是贴地导航

- 场景：室内找出口时镜片仍出「定位丢失」，箭头在画面中央，不贴地、不跟转头。
- 误判：VIO 真丢了所以该藏箭头；2m 处画 chevron 就会贴在地上。
- 根因：GOOD 之后 IMU 间隙/速度尖峰把质量打成 LOST，`ExplorationPlanner` 用「定位丢失」盖掉找出口。HUD 在 `tracking_lost` 时画屏幕三角。地面点在 2.2m 仰角约 35°，30° 光机看不见，`projectToHud` 丢掉。
- 以后先做：短丢失保持上一帧 chevron 和任务文案。路点 6/9/12m、地面 z=眼高下、HUD 针孔加下俯。本地 pose 世界锁定投影。log 看 `indoor stage=SEEK_EXIT`，镜片应是「找出口出门」加下沿 V 形。
- 不要做：把短暂 LOST 写成「定位丢失」；用屏幕中央三角当 AR 指引；在 2m 脚边画箭头指望光机能看见。

## 2026-08-28 — 办公室说去海底捞，室内任务是出门不是找目的楼层

- 场景：对眼镜说「导航去海底捞」，镜片「定位丢失 / 请环视」，没有室内箭头，也不走高德。
- 误判：GPS 坏了；或者办公室 GPS 30m 该直接走高德路网。
- 根因：人确实在室内，该开 VIO。高德 POI `floor=3` 让 `destFloorOf` 在出发楼里 `LOCATE_FLOOR`。VIO 冷启动 LOST 又把箭头藏掉。
- 以后先做：室内/室外按人在哪。出发地室内忽略目的楼层，走找出口；出门 `maybeLockOutdoor` 再步行；近店才用 POI 楼层。WEAK 冷启动不说定位丢失。log 看 `indoor start ... target=1 remain=` / `indoor stage=SEEK_EXIT`。
- 不要做：用 acc>25m 取消室内；出发楼里找万象汇 3 楼；一进室内就 `tracking_lost` 藏箭头。

## 2026-08-28 — 打开到餐必须把 CustomApp 拉回前台

- 场景：停在「眼镜 Wi-Fi 正在打开」，镜片黑屏。CustomApp 进程没了，手机还在 PeerJoining。
- 误判：Wi-Fi 卡在 ENABLING；没有 CustomApp 就不能用，只能用户自己再点一次。
- 根因：`onGlassAppResume(false)` 不清 `glassReady`，开流跳过 `appStart`。CXR 早已连上时 `MainActivity.onResume` 也不 ensure。
- 以后先做：`GlassForegroundPolicy` 管前台/宽限/去重。打开到餐 `ensure("activity")`。页丢失过 2.5s 再 `onGlassLost` + `appStart`。看 log `ensure reason=` / `appStart` / `cmd=ready`。
- 不要做：`resume=false` 立刻再 start（Direct 会 pause）；ADB `am start` 当产品路径；`stop_glass` 后还自动拉起。

## 2026-08-28 — TTS 期间「去第一家」被回声过滤误杀

- 场景：AI 问去哪一家，用户说「去第一家」，TTS 不停，Agent 不接。
- 误判：麦 mute 了；或刚改的 onPause 不停麦弄坏了插话。
- 根因：NLS REST 无 partial。`EchoFilter` 两个字「一家」就算 overlap，和播报「你想去哪一家」撞上。`echo ignored 去第一家。`
- 以后先做：回声要整句子串或 ≥4 字且覆盖听写 70%。主路径 NLS WebSocket 中间结果 + `max_sentence_silence=800`。partial 不像播报就 `barge`。镜片 `listening(heard)`。log 看 `nls ws started` / `nls partial=` / `barge partial=`，不应再对「去第一家」打 `echo ignored`。
- 不要做：用 RMS 抢话；为「第一家」写死映射；REST 再等 1.8s 静音才送识别。

## 2026-08-28 — 开 Direct 时眼镜麦会叠成两路

- 场景：点对话后再开视频，NLS 把短句听成别的词；log 里 `mic started` 出现两次，PCM 大约 2 倍实时。
- 误判：ASR 模型差、某个店名要写死纠错。
- 根因：`joinP2p` 换网会让 `GlassActivity` pause/resume。旧逻辑 `onPause` 无条件 `stopMic`，`onResume` 见 `wantMic` 再开。旧 `AudioRecord.read` 还没退出，新的已经在采，两路 PCM 叠进 ASR。
- 以后先做：麦跟 `mic.on`/`mic.off`（`wantMic`），不要跟页面可见性。还在听时 `onPause` 不停麦；`GlassMic` 用 generation，stop 后旧线程不能被新的 `running=true` 救活。装包看 `mic started` 全程只有一次，resume 应是 `mic 眼镜麦已开`。签名不一致时先 `adb uninstall com.glass.dining.glass` 再 `installGlassAdb`。
- 不要做：为听错的店名写谐音表；用 `install -r` 硬覆盖不同签名的旧包。

## 2026-08-27 — 说话时姿态改走 WebRTC DataChannel，CXR 只保 PCM

- 场景：转头看标识时环境探针拿不到 yaw；麦开着又不能在蓝牙上发 pose。
- 误判：蓝牙绝对带不动一个姿态包；或者要为 pose 再开一条 UDP。
- 根因：CXR 上行和 16kHz PCM 共用 `rk_evt`。10Hz pose 会把麦挤掉。视频已经在 Wi-Fi Direct 的 RTP 上，DataChannel 当时是空实现。
- 以后先做：手机 offer 前建无序、不重传的 `pose` DataChannel。眼镜 `onDataChannel` 后才能发。OPEN 时麦也发，不再走 CXR。DC 未开且导航且没说话才稀疏 CXR。看 log `rtc pose open` / `rtc pose n=`，同时要有 `pcm n=`。
- 不要做：为 pose 单独开 Direct；可靠有序 DataChannel；麦开着还在 CXR 高频发 pose。

## 2026-08-27 — 办公室广角结算后盯着标识，画面差太小就不会再开 episode

- 场景：转头看楼层标识，VLM 写了整间办公室，context 没有层号。
- 误判：转头没捕捉到；OCR 没开。
- 根因：`transition_start` 已经因 visual 触发，1.4s 内结算成办公室广角。之后标识和办公室网格差 < 0.18，Stable 不再离开。层号又不进【当前视野】。
- 以后先做：Stable 时 OCR Jaccard ≥0.30 也开新转场（通用文字变化，不是楼层关键词）。抽帧 OCR 立刻 `fromSignage` 进 `floor_sign`，不等 VLM。说话时 yaw 走 DataChannel，heading≥35° 也能离开。看 log `transition_start ... ocr=` / `fact_promoted ... via=ocr`。
- 不要做：从 sceneBrief 抠层号；为了标识把转场阈值做成楼层专用分支。



- 场景：开着画面，转头看墙面楼层标识几秒，手机 Agent Context 没有楼层，【当前视野】仍是桌子或办公区。
- 误判：转头没触发；VLM 没跑；空闲不开相机所以完全没帧。
- 根因：log 有 `transition_start` 和 `settled`，但结算帧 OCR 是横幅「R 深港」不是「7层」。VLM 把整间办公室写成 sceneBrief，`floorCandidate` 为空。层号按规定不进场景正文；OCR 又要两次命中才写入。转回桌子后 `currentBrief` 被覆盖，【近期观察】里也没有楼层标识。
- 以后先做：`pickBest` 优先带楼层 OCR 的帧。`salientText` / 单次清晰 `parseVisibleFloor` 可以写入 `floor_sign`（观察，不是用户确认）。可见文字进 `visible_text`。VLM 提交后立刻 `publishAgentContext`。看 log `env look floor=` / `salient=` / `fact_promoted`。
- 不要做：从 sceneBrief 正则抠层号；把横幅数字当楼层；等下一帧采样才刷新 context。

## 2026-08-27 — 室内箭头世界锁定：相机和 IMU 必须同一时间基，跟踪丢失立刻藏箭头

- 场景：转头、低头、走 10m 后贴地箭头应停在原地；跟踪丢了还画假箭头会指向墙或人。
- 误判：用固定眼高 + pitch 把 2.2/3.6/5.2m 投到屏幕就算 AR；手机 heading 够用。
- 根因：旧 `GlassHudView.projectGround` 没有世界锚点。Camera2 和原始 gyro/accel 的 `event.timestamp` 必须能对上，`TimeSync.usable` 要求 ≥8 样本、|offset|<20ms、相机间隔 20–120ms。不满足时不能用 pitch 投影冒充。
- 以后先做：`SensorProbe` 读 Camera2 内参和 `SENSOR_INFO_TIMESTAMP_SOURCE`。`CameraFrameHub` 唯一 Camera2 owner，YUV 给 VIO、I420 给 WebRTC。本地 `SpatialTracker` 投影 3D waypoint。`tracking_lost` 或本地 `TrackQuality.LOST` 不画箭头。看 log `spatial probe` / `spatial sync usable=`。
- 不要做：WebRTC `Camera2Capturer` 再开一个会话；麦开着高频发 pose；无导视时编完整室内路线。

## 2026-08-27 — 转头看楼层再转回电脑，问几楼会丢，是运动中立刻抽帧

- 场景：看电脑 → 转头看楼层标识停留 → 转回电脑 → 问「我在几楼」→ AI 说眼前是显示器、看不到楼层。
- 误判：画面没变化所以没触发；OCR「7F」应直接当事实；把层号写进场景正文再正则抠。
- 根因：相邻帧差大时立刻把转头模糊帧送给 VLM；视线在标识上稳定后相邻差变小反而不触发。单个 pending 还会被转回电脑的帧覆盖。层号藏在 `currentBrief` 里，下一帧电脑场景会冲掉。
- 以后先做：`EnvironmentProbe` 走 Stable / Transition / Settling。新视野连续约 1.5s 内部相似且不同于上一锚点才提交 episode。VLM 忙时 episode 入队，不覆盖。`currentBrief` 只表示当前视野；楼层进 `recentObservations.floor_sign`。问几楼：用户确认 > 可靠标识 > 未知。看 log `transition_start` / `settled` / `episode_queued` / `episode_committed` / `fact_promoted`。
- 不要做：运动中立刻 `envLook`；用最新帧覆盖未处理的稳定视野；从通用场景散文正则抠层号；用转回后的电脑画面覆盖刚才的楼层证据。

## 2026-08-27 — 导航时也能聊天：镜片箭头用本地 IMU，不要用 CXR 高频姿态

- 场景：边导航边问 AI。姿态如果还走蓝牙，PCM 仍会被挤掉。
- 误判：导航必须把朝向每秒 10 次发给手机，否则箭头不准。
- 根因：镜片 HUD 已经在本地读 IMU（`hud.pitchDeg = imu.pitchDegrees`）。箭头的左右来自手机下发的导航卡片，不靠 `pose` 通道。
- 以后先做：麦开着时不发 pose。导航且没在说话时，朝向变超过约 12° 才最多 1 秒发一次。看 log 应持续有 `pcm n=`。
- 不要做：把「导航中」当成可以 10Hz 发 pose 的理由。

## 2026-08-27 — 眼镜姿态 10Hz 占满 CXR，说话没有 PCM，AI 就不回答

- 场景：对着眼镜说话，手机没有识别、AI 没有回复。log 全是 `cmd=pose`，看不到 `pcm n=`。
- 误判：ASR 坏了；模型没配；麦克风权限没了。
- 根因：为环境感知把 `sendPose` 改成一直 100ms 一次。CXR 自定义通道被姿态占满，眼镜麦的 PCM 过不来，`GlassAsr` 收不到声音。
- 以后先做：姿态只在导航时发。环境记忆靠画面差异，不要用 10Hz IMU 抢语音通道。看 log 应有周期性 `pcm n=`，说话后有 utterance。
- 不要做：把 pose 当常驻心跳；在 CXR 上高频发非语音数据。

## 2026-08-27 — 「眼镜 Wi-Fi 关着，正在打开」会卡很久，是 App 自己没打开

- 场景：开视频后手机/镜片反复显示「眼镜 Wi-Fi 关着，正在打开」，停很久也不进 Direct。
- 误判：只能在电脑上 `svc wifi enable`；或固件关了就永远打不开。
- 根因：眼镜 APK `targetSdk 34` 时 `WifiManager.setWifiEnabled` 被系统直接拒绝。旧逻辑每 0.8s 在 ENABLING 时再打一次 enable，状态机转不完。`isWifiEnabled==true` 还被 `wifi_on` 设置位否决。STA 每 2.5s、手机每 12s 重发 offer 又把同一句 RTC_STAT 刷到手机上。
- 以后先做：眼镜 CustomApp `targetSdk 28`，进程启动就 `GlassWifi.hold()`，用 `setWifiEnabled(true)` 打开。ENABLING 时等待，不要重入。同一 Direct SSID 不要重启 STA。同一句进度只报一次。看 log `setWifiEnabled=` / `wifi keep state=`。
- 不要做：让助手用 ADB 手动开眼镜 Wi-Fi；把「正在打开」当失败文案刷屏；ENABLING 时再 `svc wifi enable`。

## 2026-08-27 — 环境记忆用画面变化触发 VLM 中文场景，OCR 只做探针

- 场景：用户走进无字房间、有人靠近，或问「眼前是什么 / 刚才发生了什么」，AI 没有通用场景记忆。
- 误判：OCR 看到「7层」就够当环境事实；环境模型应该按导航楼层 JSON 来建。
- 根因：旧路径把 OCR 楼层写进 `EnvironmentStore`。画面结构变化没有作为主探针，VLM 也不写给下一轮对话用的中文场景。
- 以后先做：`visualGrid` 为主、OCR 为辅，经 `EnvironmentProbe` 打分。最近 3 帧里至少 2 帧中高分才触发。后台 VLM ≥8s、每分钟 ≤6 次；静止不刷，持续移动约 30s 兜底。`PhoneAi.envLook` 纯文本写入 `currentBrief` + `recentChanges`，下一轮注入 `【当前环境】`。用户口述进 `userFacts`。失败保留上一份正文。
- 不要做：`EnvironmentMerge.fromOcr` 写楼层事实；把环境记忆做成导航专用 JSON；每帧打 VLM；JPEG 进对话历史。

## 2026-08-27 — TTS 期间要能插话，但不能用 RMS 抢话

- 场景：AI 正在回复，用户继续提问，系统不听、也停不下来。
- 误判：眼镜麦关了；或该用音量阈值 barge-in。
- 根因：旧逻辑 TTS 时 `GlassAsr.muted=true`，且 `onHeard` 在 `asking` 时直接丢掉。眼镜麦会把手机喇叭听回去，RMS 会自己打断自己。
- 以后先做：TTS/思考中继续识别。`EchoFilter` 丢掉和播报重叠的回声。新用户句用 `TalkTurn.seq` 取消旧 Agent 并 `PhoneTts.stop()`。partial 出现不像回声的汉字再停 TTS。看 log `echo ignored` / `barge partial`。
- 不要做：TTS 期间 mute 麦；用 RMS 阈值抢话；过期轮的 `onStreamDelta` 继续开口。

## 2026-08-27 — 楼层不要再用 OCR 写成环境事实

- 场景：眼前有 7 楼标识，随后问几楼。
- 误判：OCR「7F」应直接晋升为环境事实。
- 根因：OCR 会抖、会看错，且写不出物体/人物/空间关系。导航 `LandmarkPlanner` 仍可消费 OCR，但不能定义通用环境模型。
- 以后先做：画面变化触发 VLM 写中文场景；口述「我在 7 楼」进 `userFacts`。导航楼层优先【用户确认】，其次从场景正文读取。看 log `env vlm start` / `env vlm ok`。
- 不要做：`fromOcr` 写 `floorFact`；空帧清掉已有 `currentBrief`。

## 2026-08-27 — 眼镜 CustomApp 进程一启动就开 Wi-Fi

- 场景：用户问眼镜 App 打开时能否自动开 Wi-Fi。USB 插着时固件仍会把 `wifi_on` 置 0。
- 误判：只能等 `p2p.offer` 或手动开；`setWifiEnabled` 在 targetSdk 34 上一定够用。
- 根因：Android Q 之后普通应用开 Wi-Fi 常被拦。`GlassWifi.hold()` 必须在 `Application.onCreate` 就跑，并用 `svc wifi enable` / `settings put global wifi_on 1`。`WRITE_SECURE_SETTINGS` 要用眼镜 ADB `pm grant`。
- 以后先做：`GlassApp` 启动即 `hold()`，Activity 不要 `release()`。装完 APK 后 `pm grant com.glass.dining.glass android.permission.WRITE_SECURE_SETTINGS`。看 log `wifi hold start` / `setWifiEnabled` / `svc wifi enable`。
- 不要做：等导航或开流才开 Wi-Fi；为开 Wi-Fi 拔 USB；Activity `onDestroy` 停掉保活。

## 2026-08-27 — USB 插着时眼镜 Wi-Fi 会被固件关掉，Direct 必挂

- 场景：视频反复连不上，用户发现眼镜 Wi-Fi 关了。
- 误判：Direct 协议不稳；要拔 USB 才能开 Wi-Fi。
- 根因：USB 调试连上后固件常把 `wifi_on` 置 0。RTP / Direct 都走 Wi-Fi，关了就永远加不进组。旧逻辑只在 `p2p.offer` 时 `setWifiEnabled` 一次，失败就放弃；手机 12s 重发 offer 还会把正在加入的流程掐掉。
- 以后先做：CustomApp 一到前台就 `GlassWifi.hold()`，`setWifiEnabled` + `svc wifi enable`，被关了再打开。同一 SSID 的 offer 不要 `stopInternal`。USB 留着，不要拔。
- 不要做：把关 Wi-Fi 当成用户操作；等导航/开流才去开 Wi-Fi；为开 Wi-Fi 拔数据线。

## 2026-08-27 — 到餐 CustomApp 全屏黑底占住镜片，关掉手机 App 也不退

- 场景：没开到餐 App，镜片系统 UI 完全不显示，像光机坏了。
- 误判：光机死了；USB 把显示踢掉了。
- 根因：CXR 会话类型 `CUSTOMAPP` 会把 `com.glass.dining.glass` 拉到前台，主题是全屏黑底。手机 App 被杀掉时没调 `appStop`，眼镜页继续占着合成器。USB 仍枚举但眼镜 `adb` 经常握手断，没法 `force-stop`。
- 以后先做：退出到餐时 `appStop`。眼镜收到手机断开就 `finishAndRemoveTask`。现场恢复：`am start ... --ez stop_glass true`，或眼镜 ADB 通了之后 `am force-stop com.glass.dining.glass` 再 `KEYCODE_WAKEUP`。不要拔 USB。
- 不要做：把「镜片全黑」先当成硬件坏了；为了恢复显示让人拔数据线。

## 2026-08-27 — 手机 Direct 组已建好，眼镜没加入就把画面关了

- 场景：开视频没有画面，提示眼镜没加入 Direct。
- 误判：手机没建组；CXR 没把 offer 发出去。
- 根因：手机 GO 约 1s 就 `group ready`（SSID `DIRECT-…`），`p2p.offer` 也发出了。眼镜走 `discoverPeers` + `connect()`，经常看不见已经当 GO 的手机，镜片上也没法点确认框。22s 超时 `abortP2p` 会 `removeGroup`，眼镜这时才连上也会落空。USB 插着时眼镜 `wifi_on=0` 仍是常见前置条件。
- 以后先做：眼镜用 SSID+口令当普通热点加入（`GlassStaJoin`），发现/connect 只作兜底。手机组建好后周期 `discoverPeers` 并重发 offer，不要第一轮超时就拆组。开画面前看 `PhoneP2p group ready`、`GlassP2p sta joined` / `GlassP2p joined`。`wifi_on=0` 就 `svc wifi enable`，不要拔 USB。
- 不要做：把 `WifiP2pManager.connect()` 当主路径；22s 没加入就 `removeGroup`；GO 的 MAC 是 `02:00:00:00:00:00` 时还按 MAC 匹配。

## 2026-08-27 — 「导航去海底捞」说没有定位，其实没去取点

- 场景：手机定位已开、`ACCESS_FINE_LOCATION` 已授予，对眼镜说「导航去海底捞」，立刻回复没有定位。
- 误判：系统定位关了；高德密钥坏了；小米拿不到点。
- 根因：`resolve_destination` 只看内存里的 `PhoneGps.last`。搜附近才会 `awaitFix`，但 `PlaceResolver` 在 `last==null` 时先返回 `NeedLocation`，根本走不到搜索。`PhoneGps.start()` 以前只在导航前台服务里开，进 App 不预热；`lastKnown` 超过 60 秒被丢掉。真机 log：`agent error name=resolve_destination 需要定位才能找海底捞`，当天 `dumpsys location` 里这个 App 没有新的 location registration。
- 以后先做：没搜过附近就先 `need_search` / `awaitFix`，不要凭空 `NeedLocation`。App 在前台就 `PhoneGps.start()`。过期 lastKnown 作 fallback。log 看 `resolve dest=`、`gps await`、`searchNearby`。
- 不要做：把「打开定位」说给已经授权的用户；等导航开始才去听 GPS。

## 2026-08-27 — USB 调试和 Wi-Fi 视频可以同时开

- 场景：家里一直 USB 插着电脑调试，同时开 Wi-Fi Direct 视频。助手把 `adb devices` 变空说成「开 Wi-Fi 把 USB 调试踢掉了」，还让人反复拔插。
- 误判：开眼镜 Wi-Fi / Direct 会关掉 USB 调试；没画面就要拔数据线。
- 根因：两件事方向相反。USB 调试连上后固件**常常把眼镜 Wi-Fi 关掉**（`wifi_on=0`），视频 RTP 需要 Wi-Fi，所以要在**数据线仍插着**时 `svc wifi enable`。USB ADB 走线，不走 Wi-Fi；Direct 最多改 STA / **无线** ADB（`IP:5555`），不能当成会拆掉有线 ADB。当天 `adb devices` 空了，USB 仍枚举 Rokid `0x4ee7`，是电脑侧 adbd 握手断了（含误执行 `adb kill-server`），不是 `svc wifi enable` 关了调试。
- 以后先做：默认 **USB 调试 + Wi-Fi 视频同时开**。`wifi_on=0` 就开 Wi-Fi，不要先让人拔线。`adb devices` 空了先看 USB 是否还在、再重启 adb；不要把「无线 ADB 可能掉」写成「USB 调试被踢」。
- 不要做：对用户说开 Wi-Fi 会踢掉 USB 调试；把拔数据线当开视频的步骤；用 `adb kill-server` 当常规修复。

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
- 根因：手机 Direct 组已经建好。USB 调试连上后眼镜 `wifi_on=0`（固件常关 Wi-Fi），`setWifiEnabled` 失败后立刻 `P2P_FAIL`。随后 `PhoneRtc.stop()` / `PhoneP2p.stop()` 把失败文案盖成「视频流已关」。
- 以后先做：关流不要覆盖 abort 原因。Wi-Fi 关着就 **USB 留着、把 Wi-Fi 打开**，不要让人拔线。开画面前看 `wifi_on` 和 `GlassP2p wifi was off`。
- 不要做：把「视频流已关」当采集失败；USB 插着时假定眼镜 Wi-Fi 是开的；把拔 USB 写成开视频的步骤。

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
- 以后先做：用原生 `createGroup`（手机当 GO）+ CXR 把 SSID/口令送给眼镜，眼镜当普通 STA 加入 `DIRECT-` 热点，避免 `connect()` 弹窗。出网段后再复用现有 WebRTC。Direct 可能让眼镜离开家里路由器，**无线 ADB（`IP:5555`）** 可能掉；**有线 USB ADB 应继续可用**，不要写成会踢掉 USB 调试。
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
- 以后先做：CustomApp 用眼镜 `AudioRecord` 16 kHz mono，经 `mic.on` / `pcm` Caps 送到手机 Vosk。`adb shell pm grant com.glass.dining.glass android.permission.RECORD_AUDIO`。不要用 resume 文案覆盖收声失败。
- 不要做：CustomApp 会话里把 CXR-L `startAudioStream` 当眼镜麦；看到「到餐页在前台」就当已经在听。

## 2026-08-26 — 小米拦截新包 USB 安装

- 场景：眼镜 APK `adb install` 成功，当时实验手机包 `com.glass.nav.phone`（已删除）报 `INSTALL_FAILED_USER_RESTRICTED`。
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
