---
prev:
  text: API 总览
  link: /terminal-sdk/api-reference/
next:
  text: 手机端 API 总览
  link: /terminal-sdk/api-reference/Glass3  SDK(手机端) API文档.md
---

# 眼镜SDK API接口说明

本文整理眼镜端 `glass3.open.sdk` 对外能力，适用于眼镜端应用接入、能力调用和问题排查。建议先阅读 [快速开始](/terminal-sdk/getting-started/快速开始.md) 完成 SDK 依赖集成，再按能力域查找对应接口。

## API 分类总览

| 分类 | 适合查什么 |
| --- | --- |
| [接入与初始化](#api-init) | SDK 绑定、注册、初始化状态确认 |
| [GlassSdk 服务入口](#glasssdk-service-entry-table) | 通过 `GlassSdk` 获取各能力服务 |
| [通用信息接口](#common-info-interfaces) | 用户信息、伴生端 App 信息、眼镜端配置变更 |
| [离线语音指令接口](#offline-command-interfaces) | 离线语音指令词配置、添加、移除和清空 |
| [语音与 AI](#voice-ai) | AI 问答、ASR 语音转文本 |
| [媒体能力](#media-capabilities) | 音视频预览、拍照录像、媒体状态监听 |
| [相机共享 Helper](#camerasharehelper) | Surface 预览、NV21 数据导出、纹理刷新和回调 |
| [消息与文件传输](#messaging-file-transfer) | 手机与眼镜之间的消息、文件传输 |
| [文件系统能力](#file-system-capabilities) | 文件上传、文件状态和上传回调 |
| [视觉识别能力](#visual-recognition-capabilities) | 人脸、车牌、人车检测等识别能力 |
| [连接与外设](#connectivity-peripherals) | Wi-Fi P2P、经典蓝牙、蓝牙指环等连接能力 |
| [设备与系统能力](#device-system-capabilities) | 设备状态、应用可见性、电量等系统能力 |
| [参数取值与数据结构](#parameter-models) | 常量、枚举、复杂参数和回调数据结构 |


<a id="api-init"></a>

## 1 接入与初始化

在眼镜端应用模块的 `build.gradle` 中添加 SDK 依赖：

```groovy
dependencies {
    implementation ('com.rokid.security:glass3.open.sdk:2.2.0-E') {
        exclude group: "org.slf4j"
    }
}
```

完整 Maven 仓库配置和 native 库冲突处理请参考 [快速开始 4.1 眼镜端 SDK](/terminal-sdk/getting-started/快速开始.md#quickstart-glass-sdk-dependency)。

```kotlin
fun initSdk() {
        // 如果SDK已经初始化了，则直接返回
        if (GlassSdk.isReady()) {
            Log.d(TAG, "sdk已经初始化了")
            return
        }
        GlassSdk.bindSecurityService(Utils.getApp(), object : IServiceConnectionCallback {
            override fun onServiceConnected() {
                //眼镜端的clientId与手机端注册的clientId要相同,
                //这样手机端就知道把数据发给眼镜端那个应用程序了
                GlassSdk.registerClient("GlassSample", mClientMessageCallback)
            }

            override fun onServiceDisconnected() {
                Log.i(TAG, "onServiceDisconnected: ")
            }

            override fun onBindingDied() {
                Log.i(TAG, "onBindingDied: ")               
            }
        })
    }
```

## 2 GlassSdk 服务入口与通用能力

`GlassSdk` 是眼镜端 SDK 的统一入口。开发者通常先完成服务绑定和客户端注册，再通过 `getGlassXxxService()` 获取具体能力服务。

### 2.1 常用入口方法

| 方法 | 说明 |
| --- | --- |
| `bindSecurityService(context, callback)` | 绑定眼镜端系统服务，绑定成功后才能注册客户端和获取能力服务。 |
| `registerClient(clientId, callback)` | 注册当前眼镜端应用，`clientId` 需要和手机端保持一致。 |
| `isReady()` | 判断 SDK 服务是否已绑定成功。 |
| `unbindSecurityService()` | 解绑眼镜端系统服务连接，通常在应用退出或不再需要系统服务时调用。 |
| `release()` | 释放 SDK 连接和内部资源，通常在应用退出或不再使用 SDK 时调用。 |


`GlassSdk` 的具体能力通过 `getGlassXxxService()` 获取。服务对象可能为空，调用前建议先确认 `GlassSdk.isReady()`，并对返回值做空值处理。

```kotlin
if (!GlassSdk.isReady()) {
    // 先完成 bindSecurityService 和 registerClient
    return
}

val mediaService = GlassSdk.getGlassMediaService()
val messageService = GlassSdk.getGlassMessageService()
```

<a id="glasssdk-service-entry-table"></a>

### 2.2 能力服务入口

| 方法 | 返回服务与说明 |
| --- | --- |
| <a id="getclassicbluetoothservice"></a>[`getClassicBluetoothService()`](#ibtservice) | <a id="ibtservice-entry"></a>[`IBTService`](#ibtservice)<br>经典蓝牙能力。 |
| <a id="getp2pgoservice"></a>[`getP2PGoService()`](#iwifip2pgoservice) | <a id="iwifip2pgoservice-entry"></a>[`IWifiP2PGoService`](#iwifip2pgoservice)<br>Wi-Fi P2P 连接能力。 |
| <a id="getglassmessageservice"></a>[`getGlassMessageService()`](#imessageserver) | <a id="imessageserver-entry"></a>[`IMessageServer`](#imessageserver)<br>消息通信能力。 |
| <a id="getglasscommonservice"></a>[`getGlassCommonService()`](#icommoninfoserver) | <a id="icommoninfoserver-entry"></a>[`ICommonInfoServer`](#icommoninfoserver)<br>通用信息能力。 |
| <a id="getglassmediaservice"></a>[`getGlassMediaService()`](#imediaserver) | <a id="imediaserver-entry"></a>[`IMediaServer`](#imediaserver)<br>媒体预览、拍照、录像等能力。 |
| <a id="getglassofflinefeaturerecservice"></a>[`getGlassOfflineFeatureRecService()`](#iofflinefeaturerecservice) | <a id="iofflinefeaturerecservice-entry"></a>[`IOfflineFeatureRecService`](#iofflinefeaturerecservice)<br>离线特征识别能力。 |
| <a id="getglassofflinerecservice"></a>[`getGlassOfflineRecService()`](#iofflinerecserver) | <a id="iofflinerecserver-entry"></a>[`IOfflineRecServer`](#iofflinerecserver)<br>离线识别能力。 |
| <a id="getglassonlinerecservice"></a>[`getGlassOnlineRecService()`](#ionlinerecservice) | <a id="ionlinerecservice-entry"></a>[`IOnlineRecService`](#ionlinerecservice)<br>在线人/车识别能力。 |
| <a id="getglasscollectservice"></a>[`getGlassCollectService()`](#icollectservice) | <a id="icollectservice-entry"></a>[`ICollectService`](#icollectservice)<br>图像采集能力。 |
| <a id="getglasstrackservice"></a>[`getGlassTrackService()`](#itrackservice) | <a id="itrackservice-entry"></a>[`ITrackService`](#itrackservice)<br>人/车检测跟踪能力。 |
| <a id="getglassdeviceservice"></a>[`getGlassDeviceService()`](#ideviceservice) | <a id="ideviceservice-entry"></a>[`IDeviceService`](#ideviceservice)<br>设备与系统能力。 |
| <a id="getglassbluetoothringservice"></a>[`getGlassBluetoothRingService()`](#ibluetoothringservice) | <a id="ibluetoothringservice-entry"></a>[`IBluetoothRingService`](#ibluetoothringservice)<br>蓝牙指环能力。 |
| <a id="getglassnotificationservice"></a>[`getGlassNotificationService()`](#inotificationservice) | <a id="inotificationservice-entry"></a>[`INotificationService`](#inotificationservice)<br>消息通知能力。 |
| <a id="getglassaichatservice"></a>[`getGlassAiChatService()`](#iaichatservice) | <a id="iaichatservice-entry"></a>[`IAiChatService`](#iaichatservice)<br>AI 问答能力。 |
| <a id="getglassfilesystemservice"></a>[`getGlassFileSystemService()`](#ifilesystemservice) | <a id="ifilesystemservice-entry"></a>[`IFileSystemService`](#ifilesystemservice)<br>文件系统能力。 |
| <a id="getglasstranslateservice"></a>[`getGlassTranslateService()`](#itranslateservice) | <a id="itranslateservice-entry"></a>[`ITranslateService`](#itranslateservice)<br>翻译能力。 |
| <a id="getglassttsservice"></a>[`getGlassTtsService()`](#ittsservice) | <a id="ittsservice-entry"></a>[`ITtsService`](#ittsservice)<br>在线 TTS 能力。 |
| <a id="getglassofflinettsservice"></a>[`getGlassOfflineTtsService()`](#iofflinettsservice) | <a id="iofflinettsservice-entry"></a>[`IOfflineTtsService`](#iofflinettsservice)<br>离线 TTS 能力。 |
| <a id="getglassasrservice"></a>[`getGlassAsrService()`](#iasrservice) | <a id="iasrservice-entry"></a>[`IAsrService`](#iasrservice)<br>ASR 语音转文本能力。 |
| <a id="getglassofflinecmdservice"></a>[`getGlassOfflineCmdService()`](#iofflinecmdservice) | <a id="iofflinecmdservice-entry"></a>[`IOfflineCmdService`](#iofflinecmdservice)<br>离线语音指令能力。 |
| <a id="getglassidentificationservice"></a>[`getGlassIdentificationService()`](#iidentificationservice) | <a id="iidentificationservice-entry"></a>[`IIdentificationService`](#iidentificationservice)<br>身份识别能力。 |


<a id="2-3-通用信息接口"></a>

<a id="common-info-interfaces"></a>

### 2.3 通用信息接口

`ICommonInfoServer` 来自 `GlassSdk.getGlassCommonService()`，用于读取用户信息、伴生端 App 信息，并注册通用信息回调。`ICommonInfoListener` 是对应的监听器，用于接收用户信息、伴生端 App 信息和眼镜端配置变更。

<a id="icommoninfoserver"></a>

**ICommonInfoServer：通用信息服务**

```java
interface ICommonInfoServer {
    /**
     * 获取用户信息
     * @return UserInfo
     */
    UserInfo getUserInfo();

    /**
     * 获取伴生端 App 信息
     * @return CompanionAppInfo
     */
    CompanionAppInfo getCompanionAppInfo();

    /**
     * 注册监听器
     * @param listener
     */
    void setCommonInfoListener(ICommonInfoListener listener);

    /**
     * 注销监听器
     * @param listener
     */
    void removeCommonInfoListener(ICommonInfoListener listener);

}

```

<a id="icommoninfolistener"></a>

**ICommonInfoListener：通用信息回调**

```java
interface ICommonInfoListener {
    /**
     * 更新用户信息
     * @param info
     */
   void onUserInfo(in UserInfo info);

    /**
     * 更新伴生端 App 信息
     * @param info
     */
   void onCompanionAppInfo(in CompanionAppInfo info);

   /**
    * 眼镜端的配置信息
    */
   void onConfig(in String config);
}

```

<a id="2-4-离线语音指令接口"></a>

<a id="offline-command-interfaces"></a>

### 2.4 离线语音指令接口

推荐直接通过 `GlassSdk` 配置离线语音指令语言和指令词。

| 方法 | 说明 |
| --- | --- |
| `setOfflineCmdLanguage(language)` | 设置离线指令语言，例如 `ZH_CN`、`EN_US`。 |
| `setOfflineCmdWords(language, voiceActions)` | 按语言覆盖离线指令词。 |
| `clearOfflineCmdWords(language)` | 清空指定语言下的离线指令词。 |

调用示例：

```kotlin
GlassSdk.setOfflineCmdLanguage(language)
GlassSdk.setOfflineCmdWords(language, voiceActions)
GlassSdk.clearOfflineCmdWords(language)
```

<a id="iofflinecmdservice"></a>

**IOfflineCmdService：离线语音指令服务**

```kotlin
interface IOfflineCmdService {
         /**
          * 初始化
          * */
         void init();

          /**
          * 恢复上次设置的指令词
          * */
         void restore();

          /**
          * 添加指令词
          *@Param VoiceAction 指令词
          * */
         void add(in VoiceAction voiceAction);

          /**
           * 添加指令词
           *@Param voiceActions 一组指令词
           * */
         void addAll(in List<VoiceAction> voiceActions);

          /**
           * 移除指令词
           *@Param VoiceAction 指令词
           * */
         void remove(in VoiceAction voiceAction);

           /**
            * 移除所有指令词
            * */
         void removeAll();

         /**
          * 释放
           * */
         void release();

         /**
          * 获取当前离线指令语言（ZH_CN / EN_US）
          */
         String getLanguage();

         /**
          * 切换离线指令语言（ZH_CN / EN_US）
          */
         void setLanguage(String language);

         /**
          * 按语言批量覆盖指令词（不会影响其它语言的缓存）
          */
         void setWords(String language, in List<VoiceAction> voiceActions);

         /**
          * 清空某个语言下的指令词
          */
         void clearWords(String language);

         /**
          * 启动语音录制
          */
         void startRecord();

         /**
          * 停止语音录制
          */
         void stopRecord();

}
```

<a id="voice-ai"></a>

## 3 语音与 AI

<a id="iaichatservice"></a>

**IAiChatService：AI 问答服务**

```java
interface IAiChatService {

        /**
         * 开始ai问答
         * @param isCameraOpen 当前摄像头状态是否打开
         */
        void startAiChat(boolean isCameraOpen );

        /**
         * 结束ai问答
         */
        void endAiChat();

        /**
         * 问答
         * @param question ai问题的问题
         * @param listener 接受问题回答的回调
         */
        void toAiChat(String question, AiChatListener listener);


         /**
         * 带意图识别的问答
         * @param question ai问题的问题
         * @param intent 意图
         * @param intentJson 意图json
         * @param listener 接受问题回答的回调
         */
        void toAiChatWithIntent(String question, int intent, String intentJson, AiChatListener listener);


         /**
          * 巡检
          * @param question
          * @param listener
          */
        void toInspectChat(String question, AiChatListener listener);

         /**
          * 使用base64图片进行AI聊天
          * @param code 图片的base64编码
          * @param listener AI聊天监听器
          */
        void toAiCode( String code,  AiChatListener listener);


        void removeAIChatListener();

}
```

<a id="aichatlistener"></a>

**AiChatListener**

```java
interface AiChatListener {



        /**
         * ai回答的答案
         * @param answer 答案，流式的
         * @param isFinish 答案的终止符号
         */
   void onAiChatAnswer(String answer,boolean isFinish,String contentType,String sessionId);



       /**
        * ai问题的错误回调
        * @param code 错误码
        * @param message 错误信息
       */
       void onError( int code , String message);


    void onAiTakePhoto(String filePath);

    void onContinuousModeUpdate( boolean continuousMode,  long timeout,  boolean keepSessionActive);


}
```

<a id="itranslateservice"></a>

**ITranslateService：翻译服务**

`ITranslateService` 来自 `GlassSdk.getGlassTranslateService()`，用于启动和停止语音翻译任务。`src` 和 `dest` 使用 [TranslateLanguage](#translatelanguage) 中的语言码。

```java
interface ITranslateService {
    /**
     * 开始翻译
     * @param src 源语言，取值见 TranslateLanguage
     * @param dest 目标语言，取值见 TranslateLanguage
     * @param callback 翻译过程回调
     */
    void startTranslate(String src, String dest, TranslateCallback callback);

    /**
     * 停止翻译
     */
    void stopTranslate();
}
```

<a id="translatecallback"></a>

**TranslateCallback：翻译回调**

```java
interface TranslateCallback {
    void onStart();
    void onTranslate(String text);
    void onFinalTranslate(String text);
    void onEnd();
    void onServiceConnectState(boolean connect);
}
```

<a id="ittsservice"></a>

**ITtsService：在线 TTS 服务**

`ITtsService` 来自 `GlassSdk.getGlassTtsService()`，用于在线文本转语音播放。

```java
interface ITtsService {
    /**
     * 开始 TTS 播放
     * @param content 需要播放的文本内容
     */
    void doSpeechTts(String content);

    /**
     * 取消 TTS 播放
     */
    void doCancelTts();

    /**
     * 设置 TTS 播放完成监听器
     */
    void setSpeechCompleteListener(SpeechCompleteListener listener);

    /**
     * 移除 TTS 播放完成监听器
     */
    void removeSpeechCompleteListener();
}
```

<a id="iofflinettsservice"></a>

**IOfflineTtsService：离线 TTS 服务**

`IOfflineTtsService` 来自 `GlassSdk.getGlassOfflineTtsService()`，用于播放离线文本语音。

```java
interface IOfflineTtsService {
    /**
     * 播放文本
     * @param ttsMsg 需要播放的文本内容
     */
    void playTtsMsg(String ttsMsg);
}
```

<a id="speechcompletelistener"></a>

**SpeechCompleteListener：TTS 回调**

```java
interface SpeechCompleteListener {
    void onComplete();
    void onServiceConnectState(boolean connect);
}
```



<a id="media-capabilities"></a>

## 4 媒体能力

<a id="imediaserver"></a>

**IMediaServer**

```kotlin
// CameraSize 已在 Java/Kotlin 中实现 Parcelable
parcelable CameraSize;

interface IMediaServer {
    /**
     * 开始录像
     * @param callback 录像的回调
     * @param recordConfig 录制的参数设置
     */
    void startRecord(VideoCallback callback, in RecordConfig recordConfig);

    /**
     * 结束录像
     */
    void stopRecord();

    /**
     * 拍照，会触发[PhotoFileCallback]回调拍摄文件
     */
    void  takePhoto(int photoResolution,String path);

    /**
     * 添加图片文件回调
     * @param expectedResolution (Resolution)
     * @param photoFileCallback
     */
    void addPhotoCallback(PhotoFileCallback photoFileCallback);

    /**
     * 移除拍照文件回调
      */
    void removePhotoCallback(PhotoFileCallback photoFileCallback);

    /**
     * @return 相机最大缩放档位
     */
    int getMaxZoomLevel();

    int getZoomLevel();

    /**
     * 缩放相机
     * @param level 缩放档位，1 表示不缩放，建议传 1..getMaxZoomLevel()
     */
    void zoomCamera(int level);

    /**
     * 开启音频录制
     * @param callback 音频回调
     */
    void startAudioRecord(AudioCallback callback);

    /**
     * 结束音频录制
     * @param callback 音频回调
     */
    void stopAudioRecord(AudioCallback callback);

   /**
     * 注册媒体服务状态监听器
     * @param listener
     */
    void setMediaStateLister(IMediaStateLister listener);

    /**
      * 注销媒体服务状态监听器
      * @param listener
      */
    void removeMediaStateLister(IMediaStateLister listener);


      void removeAllPhotoCallback();

    /**
     * 开始跨进程Surface共享（仅渲染camera画面到Surface，不含NV21数据）
     * @param surface 客户端创建的Surface，用于接收相机画面
     * @param callback 生命周期回调
     */
    void startCameraShare(in Surface surface, ICameraSurfaceCallback callback);

    /**
     * 停止跨进程Surface共享
     * @param callback 之前注册的回调
     */
    void stopCameraShare(ICameraSurfaceCallback callback);

    /**
     * 开始NV21数据导出（通过SharedMemory双缓冲共享帧数据）
     * @param callback 回调接口，通过onNv21FrameAvailable接收帧数据
     * @param enableMix 是否启用叠加模式（camera + screen叠加），
     *                  false: 导出纯camera的NV21数据
     *                  true:  导出camera叠加屏幕内容后的NV21数据
     */
    void startCameraNv21Export(ICameraShareCallback callback, boolean enableMix);

    /**
     * 停止NV21数据导出
     * @param callback 之前注册的回调
     */
    void stopCameraNv21Export(ICameraShareCallback callback);

    /**
     * 关闭相机
     * @param callback 回调接口，true关闭相机成功，false相机未关闭
     */
    void closeCamera(ICameraCloseCallback callback);

    /**
     * 开始NV21导出（可配置分辨率、帧率、防抖）
     * @param callback 回调接口，通过onNv21FrameAvailable接收帧数据
     * @param enableMix 是否启用AR Mix模式（camera + screen叠加）
     * @param config 配置项，与 CameraShareConfig 一致
     */
    void startCameraNv21ExportWithConfig(ICameraShareCallback callback, boolean enableMix, in CameraShareConfig config);

    /**
     * 开始跨进程Surface共享（可配置版本）
     * @param surface 客户端创建的Surface，用于接收相机画面
     * @param callback 生命周期回调
     * @param enableMix 是否启用AR Mix模式（camera + screen叠加）
     * @param config 配置项，与 CameraShareConfig 一致
     */
    void startCameraShareWithConfig(in Surface surface, ICameraSurfaceCallback callback, boolean enableMix, in CameraShareConfig config);

    /**
     * 获取 Camera API 支持的预览分辨率列表
     * @return 支持的分辨率列表（请注意： Camera 原始分辨率,因为camera有旋转，因此此处获取的分辨率和CameraShareConfig中要设置的分辨率宽高是对调的）
     */
    List<CameraSize> getSupportedPreviewSizes();
}
```

相机缩放使用离散档位：`1` 表示不缩放，最大值以 `getMaxZoomLevel()` 返回值为准。调用 `zoomCamera(level)` 时建议传 `1..getMaxZoomLevel()`，不要传 `0` 或负数。

<a id="imediastatelister"></a>

**IMediaStateLister**

```java
interface IMediaStateLister {

    void onCameraResolutionChange(int width, int height);

    void onCameraError(int code,String errorMsg);
}
```

<a id="icamerasharecallback"></a>

**ICameraShareCallback**

```java
interface ICameraShareCallback {

    /**
     * 相机已打开，提供分辨率和NV21双缓冲SharedMemory
     * @param width 相机宽度
     * @param height 相机高度
     * @param nv21Buffer0 NV21双缓冲区0
     * @param nv21Buffer1 NV21双缓冲区1
     */
    void onCameraOpened(int width, int height, in SharedMemory nv21Buffer0, in SharedMemory nv21Buffer1);

    /**
     * 新的NV21帧已写入指定的缓冲区
     * @param bufferIndex 缓冲区索引 (0 或 1)
     * @param width 帧宽度
     * @param height 帧高度
     * @param timestamp 帧时间戳(毫秒)
     */
    void onNv21FrameAvailable(int bufferIndex, int width, int height, long timestamp);

    /**
     * 相机已关闭
     */
    void onCameraClosed();

    /**
     * 发生错误
     * @param code 错误码
     * @param msg 错误信息
     */
    void onError(int code, String msg);

    /**
     * 回调唯一标识
     */
    String getCallbackId();

    /**
     * 新的NV21帧已写入指定的缓冲区（异步版本，性能更优）
     * @param bufferIndex 缓冲区索引 (0 或 1)
     * @param width 帧宽度
     * @param height 帧高度
     * @param timestamp 帧时间戳(毫秒)
     */
    oneway void onNv21FrameAvailableAsync(int bufferIndex, int width, int height, long timestamp);

    void onNv21ExportResolutionChanged(int width, int height, in SharedMemory nv21Buffer0, in SharedMemory nv21Buffer1, int appliedPreviewFps);

    void onNv21ExportRuntimeParamsChanged(int appliedPreviewFps, boolean videoStabilizationEnabled);

    /**
     * Zoom 等级已变更
     * @param zoomLevel 当前缩放档位，1 表示不缩放
     */
    void onZoomLevelChanged(int zoomLevel);
}
```

<a id="icamerasurfacecallback"></a>

**ICameraSurfaceCallback**

```java
/**
 * 跨进程Surface共享的轻量回调（仅生命周期，不含NV21数据）
 */
interface ICameraSurfaceCallback {

    /**
     * 相机已打开
     * @param width 相机宽度
     * @param height 相机高度
     */
    void onCameraOpened(int width, int height);

    /**
     * 相机已关闭
     */
    void onCameraClosed();

    /**
     * 发生错误
     * @param code 错误码
     * @param msg 错误信息
     */
    void onError(int code, String msg);

    /**
     * 回调唯一标识
     */
    String getCallbackId();

    /**
     * Surface共享配置已变更（分辨率、帧率、防抖等）
     * 当通过 startCameraShareWithConfig 更新配置时触发，避免重启相机
     * @param width 新的宽度
     * @param height 新的高度
     * @param appliedPreviewFps 协商后的帧率
     * @param videoStabilizationEnabled 防抖是否启用
     */
    void onSurfaceShareConfigChanged(int width, int height, int appliedPreviewFps, boolean videoStabilizationEnabled);

    /**
     * Zoom 等级已变更
     * @param zoomLevel 当前缩放档位，1 表示不缩放
     */
    void onZoomLevelChanged(int zoomLevel);
}

```

<a id="camerasharehelper"></a>

**CameraShareHelper：相机共享客户端 Helper**

`CameraShareHelper` 是眼镜端相机共享的上层辅助类，用于简化 Surface 预览和 NV21 数据导出。需要直接渲染相机画面时使用 Surface 共享；需要获取帧数据做算法处理时使用 NV21 导出。

| 方法 | 说明 |
| --- | --- |
| `initSurface(callback)` | 初始化 Surface 共享。需要在 GL 线程调用，适合只渲染相机画面的场景。 |
| `updateTexture()` | 在渲染线程更新当前帧纹理，并刷新变换矩阵。通常在 `onDrawFrame` 中调用。 |
| `getTextureId()` | 获取 OES 纹理 ID，用于渲染相机画面。 |
| `getTransformMatrix()` | 获取 SurfaceTexture 变换矩阵，用于修正纹理坐标。 |
| `getCameraWidth()` | 获取当前相机画面宽度。 |
| `getCameraHeight()` | 获取当前相机画面高度。 |
| `initNv21Export(enableMix, callback)` | 初始化 NV21 数据导出。`enableMix=false` 表示导出纯 camera 数据，`true` 表示导出 camera 与屏幕叠加后的数据。 |

Surface 预览示例：

```kotlin
val helper = CameraShareHelper()

helper.initSurface(object : CameraShareHelper.SurfaceCallback {
    override fun onCameraOpened(width: Int, height: Int) {
        // 相机已打开，可记录画面宽高
    }

    override fun onFrameAvailable() {
        // 通知 GL 线程刷新画面
        glSurfaceView.requestRender()
    }
})

// 在 GL 线程的 onDrawFrame 中调用
helper.updateTexture()
val textureId = helper.getTextureId()
val transformMatrix = helper.getTransformMatrix()
val width = helper.getCameraWidth()
val height = helper.getCameraHeight()
```

NV21 数据导出示例：

```kotlin
val helper = CameraShareHelper()

helper.initNv21Export(enableMix = false, callback = object : CameraShareHelper.Nv21Callback {
    override fun onCameraOpened(width: Int, height: Int) {
        // 相机已打开，可根据宽高初始化算法处理链路
    }

    override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestamp: Long) {
        // 处理 NV21 帧数据
    }
})
```

<a id="camerasharehelper-surfacecallback"></a>

**CameraShareHelper.SurfaceCallback：Surface 共享回调**

| 方法 | 说明 |
| --- | --- |
| `onCameraOpened(width, height)` | 相机已打开，返回当前画面宽高。 |
| `onFrameAvailable()` | 新帧可用，通常在这里触发 GL 渲染刷新。 |
| `onCameraClosed()` | 相机已关闭。 |
| `onError(code, msg)` | Surface 共享过程中发生错误。 |
| `onSurfaceShareConfigChanged(width, height, appliedPreviewFps, videoStabilizationEnabled)` | Surface 共享配置发生变化。 |
| `onZoomLevelChanged(zoomLevel)` | 相机缩放档位发生变化，`1` 表示不缩放。 |

<a id="camerasharehelper-nv21callback"></a>

**CameraShareHelper.Nv21Callback：NV21 导出回调**

| 方法 | 说明 |
| --- | --- |
| `onCameraOpened(width, height)` | 相机已打开，返回当前画面宽高。 |
| `onNv21Frame(nv21, width, height, timestamp)` | 返回一帧 NV21 图像数据。`timestamp` 为毫秒时间戳。 |
| `onCameraClosed()` | 相机已关闭。 |
| `onError(code, msg)` | NV21 导出过程中发生错误。 |
| `onNv21ExportResolutionChanged(width, height, appliedPreviewFps)` | NV21 导出分辨率或帧率发生变化。 |
| `onNv21ExportRuntimeParamsChanged(appliedPreviewFps, videoStabilizationEnabled)` | NV21 导出运行参数发生变化。 |
| `onZoomLevelChanged(zoomLevel)` | 相机缩放档位发生变化，`1` 表示不缩放。 |

<a id="photofilecallback"></a>

**PhotoFileCallback**

```java
interface PhotoFileCallback {
   void onTakePhoto(String path);

    /**
     * 统一的回调唯一标识方法（由基类实现）
     */
    String getCallbackId();

    void onTakePhotoV2(String path, int width, int height);
}

```

<a id="videocallback"></a>

**VideoCallback**

```java
// VideoCallback.aidl
package com.rokid.security.system.server.media.callback;

interface VideoCallback {

    void onStart();

    void onError();

    void onFinish();

    void onNewFile(long startTime, long endTime, String path, boolean isLast);

    void onErrorWithDetail(int code, String errorMsg);
}
```

### AudioCallback

```java
// AudioCallback.aidl
package com.rokid.security.system.server.media.callback;

interface AudioCallback {

    void onAudioStream(in byte[] buffer, int bufferLen);

    /**
     * 统一的回调唯一标识方法（由基类实现）
     */
    String getCallbackId();
}
```

### ICameraCloseCallback

```java
// ICameraCloseCallback.aidl
package com.rokid.security.system.server.media.callback;

interface ICameraCloseCallback {
   void onClosed(boolean success);
}
```



<a id="messaging-file-transfer"></a>

## 5 消息与文件传输

<a id="imessageserver"></a>

**IMessageServer：消息通信服务**

```kotlin
interface IMessageServer {
    /**
     * 注册消息监听器
     *  @param listener
     */
    void setMessageListener(IMessageListener listener);

    /**
     * 注销消息监听器
     *  @param listener
     */
    void removeMessageListener(IMessageListener listener);

    /**
     * 通过P2P方式发送文本消息(默认发送给第一个ClientId对应的手机端应用)
     * @param message
     */
    void sendTextMessageByP2P(String message);

    /**
     * 通过P2P方式发送文本消息, 发给手机端指定的clientId应用
     * @param message
     * @param clientId
     */
    void sendTextMessageByP2PWithClient(String message, String clientId);

    /**
     * 通过经典蓝牙方式发送文本消息 (默认发送给第一个ClientId对应的手机端应用)
     * @param message
     */
    void sendTextMessageByClassicBT(String message);

    /**
     * 通过经典蓝牙方式发送文本消息, 发给手机端指定的clientId应用
     * @param message
     * @param clientId
     */
    void sendTextMessageByClassicBTWithClient(String message, String clientId);

    /**
     * 开始发送音频流数据(PCM)
     */
    void sendAudioStreamData();

    /**
     *  停止发送音频流数据
     */
    void stopAudioStreamData();

    /**
     * 开始发送H264视频流数据
     */
    void sendVideoStreamData();

    /**
     * 停止发送H264视频流数据
     */
    void stopVideoStreamData();

    /**
     * 发送二进制流数据
     * @param tag
     * @param data
     * @param clientId
     * @param callback
     */
    void sendStreamData(String tag,in byte[] data, String clientId, IResultCallback callback);

    /**
     * 获取P2P发送文件的管理器
     * @return IGlassFileOperate
     */
    IGlassFileOperate getGlassFileOperater();

    /**
     * 获取蓝牙发送文件的管理器
     * @return IGlassFileOperate
     */
    IGlassFileOperate getGlassBtFileOperater();

    /**
      * 开始发送H264视频流数据
      * @param callback
      */
    void sendVideoStreamDataV2(IResultCallback callback);
}
```

<a id="imessagelistener"></a>

**IMessageListener**

```kotlin
interface IMessageListener {
    /**
     * 接收文本消息
     * @param msg
     */
    void onTextMessage(String msg);

    /**
     * 接收音频数据
     * @param buffer 音频数据缓冲区
     */
    void onAudioStream(in byte[] buffer);

    /**
     * 接收二进制数据
     * @param device 发送数据的蓝牙设备
     * @param tag 数据标签
     * @param data 二进制数据
     */
    void onStreamDataReceived(String tag, in byte[] data);

}
```

<a id="inotificationservice"></a>

**INotificationService：消息通知服务**

`INotificationService` 来自 `GlassSdk.getGlassNotificationService()`，用于监听眼镜端系统通知和人脸告警通知。

```java
interface INotificationService {
    /**
     * 设置通知监听器
     * @param listener 通知回调
     */
    void setNotificationListener(NotificationListener listener);

    /**
     * 移除通知监听器
     */
    void removeNotificationListener();
}
```

<a id="notificationlistener"></a>

**NotificationListener：通知回调**

```java
interface NotificationListener {
    /**
     * 回调常规通知
     */
    void onNotificationReceived(in NotificationMessage notification);

    /**
     * 回调人脸告警通知
     */
    void onFaceRecognizeNotification(in byte[] face1, in byte[] face2, in String message);
}
```

`NotificationMessage` 的字段说明见 [NotificationMessage](#notificationmessage)。

<a id="iglassfileoperate"></a>

**IGlassFileOperate**

```java
interface IGlassFileOperate {
    /**
     * 发送文件
     * @param dir 自定义文件接收的目录 例如: "coustom" 或者 "coustom/file/"
     * @param filePath (注意: 文件放在需要sdcard中能访问的公有目录)
     * @param listener 文件发送的监听器
     * @param resultCallback 操作结果回调
     */
    void  sendFile(String dir,String filePath, FileReceiveListener listener, IResultCallback resultCallback);

    /**
     * 停止发送文件
     */
    void stopSendFile();

    /**
     * 是否正在发送文件
     * @return
     */
    boolean isSendingFile();

    /**
     * @Deprecated  message = "V2.1.4版本废弃,setFileReceiveV2Listener方法", replaceWith = ReplaceWith("setFileReceiveV2Listener(listener)"),
     *  设置本端文件接收的监听器
     *  @param listener
     */
    void setFileReceiveListener(FileReceiveListener listener);

    /**
     *  @Deprecated  message = "V2.1.4版本废弃,removeFileReceiveV2Listener方法", replaceWith = ReplaceWith("removeFileReceiveV2Listener(listener)"),
     * 移除本端文件接收的监听器
     *  @param listener
     */
    void removeFileReceiveListener(FileReceiveListener listener);

    /**
     *  设置本端文件接收的监听器
     *  @param listener
     */
    void setFileReceiveV2Listener(FileReceiveV2Listener listener);

    /**
     * 移除本端文件接收的监听器
     *  @param listener
     */
    void removeFileReceiveV2Listener(FileReceiveV2Listener listener);

    /**
     * 是否正在接收文件
     */
    boolean isReceivingFile();

}

```

<a id="filereceivev2listener"></a>

**FileReceiveV2Listener**

```java
interface FileReceiveV2Listener {
    void onStart(String filePath);
    void onProgressChanged(String filePath, float progress);
    void onComplete(String filePath);
    void onFail();
    void onCancel(String filePath);
}
```

<a id="filereceivelistener"></a>

**FileReceiveListener**

```java
interface FileReceiveListener {
    void onStart();
    void onProgressChanged(float progress);
    void onComplete(String filePath);
    void onFail();
    void onCancel();
}
```

`onProgressChanged(progress)` 中的 `progress` 按百分比进度理解，范围为 `0..100`。成功以 `onComplete()` 为准，失败或取消以 `onFail()` / `onCancel()` 为准，不要假设失败或中断前一定会回调到 `100`。



<a id="file-system-capabilities"></a>

## 6 文件系统能力

<a id="ifilesystemservice"></a>

**IFileSystemService**

```kotlin
interface IFileSystemService {
    /**
      * 发送将文件上传云端的任务
      * @param filePath 文件路径
      * @param fileTag 文件tag类型，变量见FileTag
      * @param lastModifiedTime 文件创建时间
      * @param FileUploadListener 文件上传监听
      */
     void  sendUploadFileTask(String filePath, String fileTag, long lastModifiedTime, FileUploadListener listener);


     /**
     * 清除文件上传任务
     */
     void clearUploadTask();
}
```

`sendUploadFileTask()` 的 `fileTag` 取值见 [FileTag](#filetag)。

<a id="fileuploadlistener"></a>

**FileUploadListener**

```java
interface FileUploadListener {

       /**上传排队**/
       void onQueue(String filepath);
   /**
       *文件服务端上传开始，
      * */
      void onStart(String fileMd5);


      void onProgressChanged(float progress) ;


      void onComplete(String fileId, String fileUrl, String fileMd5);

      /**
       *状态更新
       * */
      void onFail(String filepath,String fileMd5);

      /**
       *进度更新
       * */
      void onCancel(String fileMd5);

}

```

`FileUploadListener.onProgressChanged(progress)` 中的 `progress` 同样按百分比进度理解，范围为 `0..100`。上传成功以 `onComplete()` 为准，失败或取消时不保证最终进度会到 `100`。

<a id="uploadstatus"></a>

**UploadStatus**

```java
interface UploadStatus {
    const int UPLOAD_STATUS_ING = 0;
    const int UPLOAD_STATUS_SUCCESS = 1;
    const int UPLOAD_STATUS_FAILED = -1;
}
```



<a id="visual-recognition-capabilities"></a>

## 7 视觉识别能力

```kotlin
    /**
     *  获取人/车检测在线识别管理服务
     */
    @JvmStatic
    fun getGlassOnlineRecService(): IOnlineRecService? {
        return getService(ServerType.ONLINE_REC, IOnlineRecService::class.java)
    }

   GlassSdk.getGlassOnlineRecService()
```

<a id="ionlinerecservice"></a>

**IOnlineRecService**

参数说明：`startDetection(mode)` 的 `mode` 取值见 [AIRecgMode](#airecgmode)。

```java
interface IOnlineRecService {
    /**
     * 开始检测
     * @param mode 识别模式，取值见 AIRecgMode
     */
    void startDetection(int mode);

    /**
     * 停止检测
     */
    void stopDetection();

    /**
     * 注册监听
     * @param listener 检测监听器
     */
    void setGlassOnlineRecListener(IGlassDetectionListener listener);

    /**
     * 移除监听
     * @param listener 检测监听器
     */
    void removeGlassOnlineRecListener(IGlassDetectionListener listener);

    /**
     * 人脸识别
     * @param param 人脸识别参数
     * @param callback 识别结果的回调
     */
    void recognizeFace(in FaceRecognizeParam param, IResultCallback callback);

    /**
     * 获取人脸小图
     * @param trackId
     * @param Bitmap
     */
    Bitmap getFaceSamllBitmap(long trackId);

    /**
     * 获取人脸小图(圆角)
     * @param trackId
     * @param Bitmap
     */
    Bitmap getFaceRoundCornerSamllBitmap(long trackId);

    /**
     * 获取车牌小图
     * @param plateNo
     * @param Bitmap
     */
    Bitmap getLprSamllBitmap(String plateNo);
}
```

<a id="iglassdetectionlistener"></a>

**IGlassDetectionListener**

参数说明：`onModeChange(mode)` 的 `mode` 取值见 [AIRecgMode](#airecgmode)。

```kotlin
interface IGlassDetectionListener {
    /**
     * 检测模式发送改变的回调
     * @param mode 检测模式，取值见 AIRecgMode
     */
    void onModeChange(int mode);

    /**
     * 人脸检测的回调(可用于更新人脸追踪框)
     * @param faceModels 人脸模型列表
     */
    void onFaceTrack(in List<FaceModel> faceModels);

    /**
     * 筛选最优的人脸图像 (可用于在线人脸识别接口的调用)
     * @param processedFaceModels 处理后的人脸模型列表
     */
    void onProcessedFaceModels(in List<FaceModel> processedFaceModels);

    /**
     * 车牌检测的回调
     * @param lprModel 车牌模型
     */
    void onLPRTrack(in LPRModel lprModel);
}
```

<a id="iresultcallback"></a>

**IResultCallback**

```java
interface IResultCallback {

    void  onSuccess(in RecognizePersonInfo data);

    void onFailed(int code, String errormsg);
}
```

`FaceModel`、`LPRModel` 的字段说明见 [视觉识别数据结构](#visual-data-models)。

<a id="icollectservice"></a>

**ICollectService：图像采集服务**

`ICollectService` 来自 `GlassSdk.getGlassCollectService()`，用于启动人脸/车牌图像采集、提交采集图片，并获取采集过程中的小图。

参数说明：`startCollect(mode)` 和 `changeMode(mode)` 的 `mode` 取值见 [AIRecgMode](#airecgmode)。

```java
interface ICollectService {
    /**
     * 开始采集
     * @param mode 采集模式，取值见 AIRecgMode
     */
    void startCollect(int mode);

    /**
     * 停止采集
     */
    void stopCollect();

    /**
     * 设置采集监听器
     */
    void setCollectListener(IGlassCollectListener listener);

    /**
     * 移除采集监听器
     */
    void removeCollectListener(IGlassCollectListener listener);

    /**
     * 提交人脸图片
     * @param identifier 本次采集标识
     * @param param 人脸采集参数，字段见 CollectParam
     * @param callback 操作结果回调
     */
    void sendFacePicture(String identifier, in CollectParam param, ICollectCallback callback);

    /**
     * 提交车牌图片
     * @param identifier 本次采集标识
     * @param platNo 车牌号
     * @param callback 操作结果回调
     */
    void sendLPRPicture(String identifier, String platNo, ICollectCallback callback);

    /**
     * 切换采集模式
     * @param mode 采集模式，取值见 AIRecgMode
     */
    void changeMode(int mode);

    Bitmap getFaceSmallBitmap(long frameId, long trackId);

    Bitmap getLprSmallBitmap(String plateNo);
}
```

`CollectParam` 字段说明见 [CollectParam](#collectparam)。

<a id="iglasscollectlistener"></a>

**IGlassCollectListener：图像采集回调**

参数说明：`onModeChange(mode)` 的 `mode` 取值见 [AIRecgMode](#airecgmode)。

```java
interface IGlassCollectListener {
    /**
     * 筛选最优的人脸图像
     * @param processedFaceModels 处理后的人脸模型列表
     */
    void onProcessedFaceModels(in List<FaceModel> processedFaceModels);

    /**
     * 车牌检测回调
     * @param lprModel 车牌模型
     */
    void onLPRTrack(in LPRModel lprModel);

    /**
     * 采集模式变化
     * @param mode 取值见 AIRecgMode
     */
    void onModeChange(int mode);
}
```

<a id="icollectcallback"></a>

**ICollectCallback：采集提交结果回调**

```java
interface ICollectCallback {
    void onSuccess(String identifier);

    void onFailed(int code, String errormsg);
}
```

<a id="itrackservice"></a>

**ITrackService：人/车检测跟踪服务**

`ITrackService` 来自 `GlassSdk.getGlassTrackService()`，用于启动或停止人脸、车牌检测跟踪。

参数说明：`startTrack(mode)` 的 `mode` 取值见 [AIRecgMode](#airecgmode)。

```java
interface ITrackService {
    /**
     * 开始检测跟踪
     * @param mode 检测模式，取值见 AIRecgMode
     */
    void startTrack(int mode);

    /**
     * 停止检测跟踪
     */
    void stopTrack();

    /**
     * 注册跟踪监听器
     */
    void setTrackListener(IGlassTrackListener listener);

    /**
     * 注销跟踪监听器
     */
    void removeTrackListener(IGlassTrackListener listener);
}
```

<a id="iglasstracklistener"></a>

**IGlassTrackListener：人/车检测跟踪回调**

```java
interface IGlassTrackListener {
    /**
     * 人脸检测回调
     * @param faceModels 人脸跟踪模型列表
     */
    void onFaceTrack(in List<FaceTrack> faceModels);

    /**
     * 车牌检测回调
     * @param lprModel 车牌跟踪模型
     */
    void onLPRTrack(in LPRTrack lprModel);
}
```

<a id="connectivity-peripherals"></a>

## 8 连接能力：Wi-Fi P2P 与经典蓝牙

### 8.1 Wi-Fi P2P

<a id="iwifip2pgoservice"></a>

**IWifiP2PGoService：Wi-Fi P2P 连接服务**

`IWifiP2PGoService` 来自 `GlassSdk.getP2PGoService()`，用于查询 Wi-Fi P2P 群组、连接信息、客户端设备列表，并监听 P2P 通道状态。

```java
interface IWifiP2PGoService {
    /**
     * 获取群组信息
     */
    void getGroupInfo(IWifiP2pGroupCallback callback);

    /**
     * 获取 Wi-Fi P2P 连接信息
     */
    void requestConnectionInfo(IWifiP2pInfoCallback callback);

    /**
     * 获取 P2P 群组中连接的设备
     */
    void getClientList(IWifiP2pDeviceListCallback callback);

    /**
     * 判断 P2P 通讯是否已连接
     */
    void isConnect(IResultCallback callback);

    /**
     * 注册 P2P 客户端监听器
     */
    void setWifiP2PClientListener(IWifiP2PGoListener listener);

    /**
     * 注销 P2P 客户端监听器
     */
    void removeWifiP2PClientListener(IWifiP2PGoListener listener);
}
```

<a id="iwifip2pgolistener"></a>

**IWifiP2PGoListener：Wi-Fi P2P 状态回调**

```java
interface IWifiP2PGoListener {
    void onWifiP2pEnabled(boolean enabled);

    void onConnectionInfoAvailable(in WifiP2pInfo wifiP2pInfo);

    void onChannelDisconnected();

    void onSelfDeviceAvailable(in WifiP2pDevice device);

    void onPeersAvailable(in List<WifiP2pDevice> devices);
}
```

<a id="iwifip2p-callbacks"></a>

**Wi-Fi P2P 查询回调**

```java
interface IWifiP2pGroupCallback {
    void onResult(in WifiP2pGroup group);
}

interface IWifiP2pInfoCallback {
    void onResult(in WifiP2pInfo info);
}

interface IWifiP2pDeviceListCallback {
    void onResult(in List<WifiP2pDevice> devices);
}

interface IResultCallback {
    void onResult(boolean result);
}
```

<a id="ibtservice"></a>

### 8.2 经典蓝牙

**IBTService：经典蓝牙服务**

```kotlin
interface IBTService {

    /**
     * 获取连接上蓝牙的设备
     */
    List<BluetoothDevice> getConnectDevices();

    /**
     * 允许设备被发现
     */
    void makeDeviceDiscoverable();

    /**
     * 设置经典蓝牙事件回调
     */
    void setClassicBTListener(IClassicBTListener listener);

    /**
     * 移除经典蓝牙事件回调
     */
    void removeBlueToothServerListener(IClassicBTListener listener);

    /**
     * 获取经典蓝牙连接的状态
     */
    boolean isConnect();
}
```

<a id="iclassicbtlistener"></a>

**IClassicBTListener**

```java
interface IClassicBTListener {
    /**
     * 经典蓝牙设备连接成功的回调
     * @param device 连接上的设备信息
     */
    void onClientConnected(in BluetoothDevice device);

    /**
     * 经典蓝牙设备连接中断的回调
     * @param device 中断的设备信息
     */
    void onClientDisconnected(in BluetoothDevice device);

    /**
     * 有连接被拒绝
     * @param device 被拒绝连接的设备信息
     */
    void onConnectionRejected(in BluetoothDevice device);
}
```

## 9 外设能力：蓝牙指环

<a id="ibluetoothringservice"></a>

**IBluetoothRingService：蓝牙指环服务**

```kotlin
interface IBluetoothRingService {
     /**
        * 设置蓝牙指环连接状态回调
        * @param listener 回调实例
        */
       void setBluetoothRingState(IBluetoothRingConnectListener listener);

       /**
        * 判断指环是否已连接
        * @return 连接状态
        */
       boolean isRingConnect();

       /**
        * 获取已连接的指环设备
        * @return 蓝牙设备实例
        */
       BluetoothDeviceBean getRingConnectDevice();

       /**
        * 释放资源
        */
       void release();


}
```

<a id="ibluetoothringconnectlistener"></a>

**IBluetoothRingConnectListener**

```java
interface IBluetoothRingConnectListener {

        /**
         * 指环连接状态
         * @param BluetoothDeviceBean 蓝牙指环设备信息
         * @param connect 是否连接上
         */
       void onConnect(in BluetoothDeviceBean bean, boolean connect);

}
```



## 10 离线识别能力

<a id="iofflinerecserver"></a>

**IOfflineRecServer：离线识别服务**

参数说明：`startRecognition(sceneId, mode, listener)` 的 `mode` 取值见 [AIRecgMode](#airecgmode)。

```java
interface IOfflineRecServer {

    /**
     * 开始识别
     * @param sceneId 场景ID
     * @param mode 识别模式，取值见 AIRecgMode
     * @param listener
     */
    void startRecognition(String sceneId, int mode,IGlassRecListener listener);

    /**
     * 停止识别
     *  @param sceneId 场景ID
     *  @param listener
     */
    void stopRecognition(String sceneId,IGlassRecListener listener);

    /**
     * 人员库检查更新
     *  @param sceneId 场景ID
     *  @param callback
     */
    void checkUpdateLibVersion(String sceneId, CheckUpdateCallback callback);

    /**
     * 判断场景的人员库是否存在
     * @param sceneId
     */
    boolean isExistLib(String sceneId);

    /**
     * 获取人员详情信息
     * @param personnelId
     * @param callback 结果回调
     */
    void getPersonnelInfo(String personnelId,ResultCallback callback);

    /**
     * 获取人脸小图
     * @param frameId
     * @param Bitmap
     */
    Bitmap getFaceSamllBitmap(long frameId,long trackId);

    /**
     * 获取人脸小图(圆角)
     * @param frameId
     * @param Bitmap
     */
    Bitmap getFaceRoundCornerSamllBitmap(long frameId,long trackId);

    /**
     * 上报人脸识别结果
     * $param face: 人脸数据
     */
    void submitRecognizedFaceInfo(in RecognizeFaceSubmitInfo info, in Bitmap face);
}
```

<a id="iofflinefeaturerecservice"></a>

**IOfflineFeatureRecService**

参数说明：`startRecognition(mode, listener)` 的 `mode` 取值见 [AIRecgMode](#airecgmode)。

```kotlin
interface IOfflineFeatureRecService {
    /**
     * 添加特征库
     * @param featureName 特征库名称
     * @param featurePath 特征路文件路径
     */
    void addFaceFeatureFile(String featureName, String featurePath);

    /**
     * 移除特征库
     * @param featureName 特征库名称
     */
    void removeFaceFeature(String featureName);

    /**
     * 开始识别
     * @param mode 识别模式，取值见 AIRecgMode
     * @param listener 识别结果回调监听器
     */
    void startRecognition(int mode, IGlassRecListener listener);

    /**
     * 停止识别
     * @param listener 识别结果回调监听器
     */
    void stopRecognition(IGlassRecListener listener);

    /**
     * 获取人脸小图
     * @param frameId
     * @param Bitmap
     */
    Bitmap getFaceSamllBitmap(long frameId,long trackId);

    /**
     * 获取人脸小图(圆角)
     * @param frameId
     * @param Bitmap
     */
    Bitmap getFaceRoundCornerSamllBitmap(long frameId,long trackId);

}
```

<a id="iglassreclistener"></a>

**IGlassRecListener**

参数说明：`onModeChange(mode)` 的 `mode` 取值见 [AIRecgMode](#airecgmode)。

```java
interface IGlassRecListener {
    /**
     * 统一的回调唯一标识方法（由基类实现）
     */
    String getCallbackId();
    /**
     * 检测模式发送改变的回调
     * @param mode 识别模式，取值见 AIRecgMode
     */
    void onModeChange(int mode);

    /**
     * 人脸检测的回调
     * @param faceModels 人脸模型列表
     */
    void onFaceTrack(in List<FaceModel> faceModels);

    /**
     * 车牌检测的回调
     * @param lprModel 车牌模型
     */
    void onLPRTrack(in LPRModel lprModel);

    /**
     * 人脸识别结果的回调
     * @param result 人脸识别结果
     */
    void onFaceRecognize(in FaceRecgResult result);


    /**
     * 人脸识别结果的回调
     * @param result 人脸识别结果
     */
    void onFaceRecognizeNotInLib(in FaceRecgResult result);

    /**
     * 引擎初始化回调
     */
    void onEngineInit();

}
```

### CheckUpdateCallback

```java
// CheckUpdateCallback.aidl
package com.rokid.security.system.server.recog.offline.callback;

import com.rokid.security.sdk.base.common.outside.PersonnelLibInfo;

interface CheckUpdateCallback {
    /**
     * 完成
     */
    void onFinish(in PersonnelLibInfo libInfo);

    /**
     * 失败
     */
    void onFail(int code, String errMsg);

    void onProgress(int progress);
}
```

`CheckUpdateCallback.onProgress(progress)` 的 `progress` 按百分比进度理解，范围为 `0..100`。更新完成以 `onFinish()` 为准，失败以 `onFail()` 为准。

### ResultCallback

```java
// ResultCallback.aidl
package com.rokid.security.system.server.recog.offline.callback;

import com.rokid.security.sdk.base.common.outside.PersonnelInfo;

interface ResultCallback {
    /**
     * 成功回调
     * @param result 结果数据
     */
    void onSuccess(in PersonnelInfo result);

    /**
     * 失败回调
     * @param error 错误信息
     */
    void onFailure(String error);
}
```



<a id="device-system-capabilities"></a>

## 11 设备与系统能力

<a id="ideviceservice"></a>

**IDeviceService：设备与系统服务**

Wi-Fi 配网相关参数见 [WifiConnectRequest](#wificonnectrequest)、[WifiRemoveRequest](#wifiremoverequest)、[WifiNetworkInfo](#wifinetworkinfo)、[WifiOperationStage](#wifioperationstage) 和 [WifiOperationCode](#wifioperationcode)。

```kotlin
interface IDeviceService {
    /**
     * 获取设备名称
     */
    String getDeviceName();

    /**
     * 获取设备SN号
     */
    String getSerialNumber();

    /**
     * 获取系统版本
     */
    String getSystemVersion();

    /**
     * 获取设备状态信息
     */
    DeviceStatusInfo getDeviceStatusInfo();

    /**
     * 切换麦克风录音状态，切换耗时约需要3S，需要应用做一定的交互处理
     * @param state
    0: 近场定向:只拾取佩戴者语音，屏蔽环境噪声
    1：远场定向:屏蔽佩戴者声音，水平±60度，俯仰±60度的立体锥体，距离跟周围环境信噪比有关。静音环境3m声源55dB满足拾音要求。
    3：全向：360度无差别拾音，不管声音来自哪个方向，包括佩戴者，都会被拾取
     */
    fun switchMicScene(state: Int)

    /**
     * 设置音量
     * @param value Android STREAM_MUSIC 音量值，范围为 0..AudioManager.getStreamMaxVolume()
     */
    void setVolume(int value);

    /**
     * 设置亮度
     * @param value 亮度值，范围为 0..255
     */
    void setBrighing(int value);


    /**
     * 设备重启
     */
    void reboot();


    /**
     * 发送自定义事件
     * @param eventCode 客户自定义事件码，会通过 DeviceEventListener.onCusEvent() 回调给监听方
     * @param extra 客户自定义附加信息
     */
    void sendCusEvent(int eventCode,String extra);


    /**
     * 设置监听设备事件的监听
     */
    void setDeviceEventListener(DeviceEventListener listener);

    /**
     * 设置监听设备电量更新的监听
     */
    void setBatteryUpdateListener(BatteryUpdateListener listener);



    /**
     * 设置系统时间
     * @param timestamp 时间戳
     */
    void setSystemTime(long timestamp);

    /**
     * 查询所有已安装的包信息
     * @return 包名为key的键值对
     */
    Map<String, AppPackageInfo> queryAllPackages();



      /**
       * 设置拍摄灯光
       * @param timestamp 时间戳
        */
     void setCameraLedEnable(boolean enable);



      /**
       * 设置网络访问方式
       * @param type 0 表示手机中继访问，1 表示眼镜直连访问
        */
     void setNetworkType(int type);



     int getNetworkType();


    /**
     * 配置应用可见性与第三方应用显示顺序
     * 用于批量配置系统内置应用和第三方应用的显示/隐藏状态。
     *
     * @param appConfig 应用配置信息，包含：
     *                  - appList: 需要隐藏的系统内置应用列表
     *                  - thirdApps: 第三方应用列表,将显示在最前面
     * @param callback 设置结果回调，success=true 表示设置成功，false 表示设置失败
     *
     */
     void configureAppVisibility(in  GlassAppConfig appConfig, IAppVisibilityListener callback);

    /**
     * 设置时间同步服务器地址
     * 支持HTTP/HTTPS协议和NTP协议，根据URL自动识别协议类型
     *
     * @param serverUrl 时间服务器地址，支持以下格式：
     *                  - HTTP/HTTPS协议: https://time.example.com 或 http://time.example.com
     *                  - NTP协议: ntp.example.com 或 192.168.1.100
     *                  - null或空字符串: 使用默认的NTP协议和公共NTP服务器
     *
     * @note 如果URL以http://或https://开头，则使用HTTP协议获取时间
     *       否则使用NTP协议（标准UDP协议，端口123）
     */
    void setTimeServer(String serverUrl);

    /**
     * 获取当前配置的时间服务器地址
     *
     * @return 当前配置的时间服务器地址，如果未配置则返回null
     */
    String getTimeServer();

    /**
     * 注册系统级 primary 设备事件监听器。
     * system-server 只会在调用方仍处于前台时启用 primary 抢占；
     * 后台或进程死亡后自动回退到普通设备事件广播。
     */
    void setPrimaryEventListener(DeviceEventListener listener);

    /**
     * 清除当前调用进程注册的 primary 设备事件监听器。
     */
    void clearPrimaryEventListener();

    /**
     * 连接指定 Wi-Fi。
     *
     * 支持 open、WEP、WPA/WPA2-PSK、WPA3-Personal/SAE、Enterprise、WPA3-Enterprise、
     * WPA2/WPA3 自动模式等安全类型。request.ssid 必填，且不需要调用方额外添加双引号。
     *
     * @param request Wi-Fi 连接参数。
     * @param callback 操作进度和最终结果回调；可为空。
     */
    void connectWifi(in WifiConnectRequest request, IWifiOperationCallback callback);

    /**
     * 连接指定 Wi-Fi，回调参数前置。
     *
     * @param callback 操作进度和最终结果回调；可为空。
     * @param request Wi-Fi 连接参数。
     */
    void connectWifiWithCallback(IWifiOperationCallback callback, in WifiConnectRequest request);

    /**
     * 移除指定 Wi-Fi 配置。
     *
     * @param request Wi-Fi 移除参数，networkId、ssid、bssid 至少传一个。
     * @param callback 操作进度和最终结果回调；可为空。
     */
    void removeWifi(in WifiRemoveRequest request, IWifiOperationCallback callback);

    /**
     * 获取已连接过的 Wi-Fi 列表。
     *
     * @return Wi-Fi 连接记录列表；无记录时返回空列表。
     */
    List<WifiNetworkInfo> getConnectedWifiList();

    /**
     * 获取当前正在连接或已经连接的 Wi-Fi 信息。
     *
     * @return 当前 Wi-Fi 信息，未连接时返回 null。
     */
    WifiNetworkInfo getCurrentWifiInfo();
}

```

`setVolume(value)` 会设置 Android `STREAM_MUSIC` 音量，合法范围以系统 `AudioManager.getStreamMaxVolume()` 为准；`setBrighing(value)` 的亮度范围为 `0..255`，方法名以当前 SDK 接口为准。SDK 会将音量和亮度裁剪到合法范围，但仍建议调用方主动传入合法值，避免不同系统版本下表现不一致。

`setNetworkType(type)` 只建议传 `0` 或 `1`，取值见 [NetworkType](#networktype)。`getNetworkType()` 返回同一套取值。

<a id="iidentificationservice"></a>

**IIdentificationService：身份识别服务**

`IIdentificationService` 来自 `GlassSdk.getGlassIdentificationService()`，用于进入/退出身份识别模式、执行识别任务，并接收识别结果或图片回调。

```java
interface IIdentificationService {
    /**
     * 进入识别模式
     * @param isCameraOpen 当前摄像头是否已打开
     */
    void enterIdentificationMode(boolean isCameraOpen);

    /**
     * 退出识别模式
     */
    void exitIdentificationMode();

    /**
     * 执行识别任务
     * @param extra 识别任务参数
     */
    void executeIdentificationTask(String extra);

    /**
     * 结束当前任务
     */
    void endTask();

    /**
     * 设置身份识别回调
     */
    void setIdentificationListener(IdentificationListener listener);

    /**
     * 移除身份识别回调
     */
    void removeIdentificationListener();

    /**
     * 设置图片回调
     */
    void setImageListener(ImageListener listener);

    /**
     * 移除图片回调
     */
    void removeImageListener();

    /**
     * 执行修改识别任务
     * @param extra 修改任务参数
     */
    void executeModifyIdentificationTask(String extra);

    /**
     * 结束身份识别
     */
    void endIdentification();
}
```

<a id="identificationlistener"></a>

**IdentificationListener：身份识别结果回调**

```java
interface IdentificationListener {
    void onIdentificationAnswer(String answer, String type, boolean isFinish);

    void onError(int code, String message);
}
```

<a id="imagelistener"></a>

**ImageListener：身份识别图片回调**

```java
interface ImageListener {
    void onImageBack(String imageBase64);
}
```

<a id="iappvisibilitylistener"></a>

**IAppVisibilityListener**

```java
/**
 * 应用可见性设置回调接口
 */
interface IAppVisibilityListener {
    /**
     * 设置应用可见性的回调
     * @param success true 表示设置成功，false 表示设置失败
     */
    void onResult(boolean success);
}

```

<a id="deviceeventlistener"></a>

**DeviceEventListener**

```java
interface DeviceEventListener {
    void onDeviceEvent(int eventCode,String extra);


    void onCusEvent(int eventCode,String extra);
}
```

`onDeviceEvent()` 的 `eventCode` 取值见 [DeviceEventCode](#deviceeventcode)。`onCusEvent()` 是自定义事件回调，`eventCode` 由业务方自行约定。

<a id="batteryupdatelistener"></a>

**BatteryUpdateListener**

```java
interface BatteryUpdateListener {
    void onBatteryStatsuUpdate(int powrNumber,boolean charging);
}
```

<a id="iwifioperationcallback"></a>

**IWifiOperationCallback：Wi-Fi 配网/移除操作回调**

```java
interface IWifiOperationCallback {
    /**
     * 操作进度回调。
     *
     * @param stage 当前阶段，取值见 WifiOperationStage：
     *              VALIDATING=1 / CONFIGURING=2 / CONNECTING=3 / VERIFYING=4 / REMOVING=5
     * @param message 阶段描述，可能随版本变化，不建议作为业务协议解析。
     */
    void onProgress(int stage, String message);

    /**
     * 操作最终结果。
     *
     * @param code 结果码，取值见 WifiOperationCode；0 表示成功。
     * @param message 结果描述或失败原因，适合用于日志和提示。
     * @param networkId 系统 Wi-Fi 配置 ID；连接成功时可用于后续 removeWifi 精确移除，失败时可能为 -1。
     * @param ssid 本次操作目标 SSID。
     */
    void onResult(int code, String message, int networkId, String ssid);
}
```

`onProgress()` 的 `stage` 取值见 [WifiOperationStage](#wifioperationstage)，`onResult()` 的 `code` 取值见 [WifiOperationCode](#wifioperationcode)。Wi-Fi 操作阶段不保证按固定完整顺序回调，某些失败路径可能只回调部分阶段；业务判断建议优先看 `code`，`message` 更适合用于日志和用户提示。回调运行在 Binder 线程，如果需要更新 UI，请切回主线程。

## 12 ASR 语音转文本

<a id="iasrservice"></a>

**IAsrService：ASR 语音转文本服务**

```java
interface IAsrService {

       /**
          *开始Asr任务
          * @param callback asr返回的回调函数
          */
         void startSpeech( SpeechCallback callback);

         /**
          *结束Asr任务
          */
         void stopSpeech();

}
```

<a id="speechcallback"></a>

**SpeechCallback**

```java
interface SpeechCallback {
  /**
       * asr识别开始
       */
      void onStart();
      /**
       * asr识别过程结果
       * @param content 识别的内容
       */
      void onIntermediateVad(String content);

      /**
       * asr识别完成的结果
       * @param content 识别的内容
       */
      void onAsrComplete(String content);

      /**
       * 异常返回
       * @param code 错误码
       */
      void onError(int code);


      void onServiceConnectState(boolean connect);

      /**
       * asr识别完成的结果 带意图识别
       * @param content 识别的内容
       * @param intent 意图枚举
       * @param intentJson 意图json
       */
      void onAsrCompleteWithIntent(String content, int intent, String intentJson);
}
```

<a id="parameter-models"></a>

## 13 参数取值与数据结构

这一节用于承接前面 API 中的常量、枚举和复杂参数。遇到识别模式、语言码、Parcelable 参数或回调数据时，可以先到这里确认具体取值和字段含义。

<a id="airecgmode"></a>

**AIRecgMode：识别/检测模式**

`AIRecgMode` 用于在线识别、离线识别、图像采集和人/车检测跟踪等接口中的 `mode` 参数。

相关接口：

- [IOnlineRecService.startDetection(mode)](#ionlinerecservice)
- [IGlassDetectionListener.onModeChange(mode)](#iglassdetectionlistener)
- [ICollectService.startCollect(mode) / changeMode(mode)](#icollectservice)
- [IGlassCollectListener.onModeChange(mode)](#iglasscollectlistener)
- [ITrackService.startTrack(mode)](#itrackservice)
- [IOfflineRecServer.startRecognition(sceneId, mode, listener)](#iofflinerecserver)
- [IOfflineFeatureRecService.startRecognition(mode, listener)](#iofflinefeaturerecservice)
- [IGlassRecListener.onModeChange(mode)](#iglassreclistener)

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `AIRecgMode.MODE_NONE` | `0` | 无识别模式，不开启识别。 |
| `AIRecgMode.MODE_FACE` | `1` | 人脸识别模式。 |
| `AIRecgMode.MODE_LPR` | `2` | 车牌识别模式。 |
| `AIRecgMode.MODE_MIX` | `3` | 混合识别模式。 |
| `AIRecgMode.MODE_MOTOR_LPR` | `4` | 非机动车牌识别模式。 |

<a id="translatelanguage"></a>

**TranslateLanguage：翻译语言码**

`TranslateLanguage` 用于 `ITranslateService.startTranslate(src, dest, callback)` 的 `src` 和 `dest` 参数。

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `TranslateLanguage.auto` | `auto` | 自动识别，通常用于源语言。 |
| `TranslateLanguage.zh` | `zh` | 中文。 |
| `TranslateLanguage.en` | `en` | 英文。 |
| `TranslateLanguage.ja` | `ja` | 日文。 |
| `TranslateLanguage.yue` | `yue` | 粤语。 |
| `TranslateLanguage.ko` | `ko` | 韩语。 |
| `TranslateLanguage.de` | `de` | 德语。 |
| `TranslateLanguage.fr` | `fr` | 法语。 |
| `TranslateLanguage.it` | `it` | 意大利语。 |
| `TranslateLanguage.es` | `es` | 西班牙语。 |
| `TranslateLanguage.ug` | `ug` | 维语。 |
| `TranslateLanguage.ru` | `ru` | 俄语。 |

<a id="filetag"></a>

**FileTag：文件上传标签**

`FileTag` 用于 `IFileSystemService.sendUploadFileTask(filePath, fileTag, lastModifiedTime, listener)` 的 `fileTag` 参数。

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `FileTag.face` | `face` | 人脸相关文件。 |
| `FileTag.car` | `car` | 车辆/车牌相关文件。 |
| `FileTag.picture` | `picture` | 图片文件。 |
| `FileTag.aichat` | `aichat` | AI Chat 相关文件。 |
| `FileTag.video` | `video` | 视频文件。 |

<a id="progress-parameter"></a>

**Progress：进度参数**

文件接收、文件上传和离线识别库更新等回调中的 `progress` 按百分比进度理解，范围为 `0..100`。`Float` 和 `Int` 只是接口类型不同，语义一致。

成功以对应的 `onComplete()`、`onSuccess()` 或 `onFinish()` 回调为准；失败或中断时，不保证一定会先回调最终 `100`。

<a id="zoomlevel"></a>

**ZoomLevel：相机缩放档位**

相机缩放使用离散档位，`1` 表示不缩放，最大值以 `IMediaServer.getMaxZoomLevel()` 返回值为准。调用 `IMediaServer.zoomCamera(level)` 时建议传 `1..getMaxZoomLevel()`，不要传 `0` 或负数。

<a id="deviceeventcode"></a>

**DeviceEventCode：设备事件码**

`DeviceEventCode` 用于 `DeviceEventListener.onDeviceEvent(eventCode, extra)` 的 `eventCode` 参数。

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `DeviceEventCode.BUTTON_ONE_CLICK` | `10001` | 按钮单击事件。 |
| `DeviceEventCode.BUTTON_DOUBLE_CLICK` | `10002` | 按钮双击事件。 |
| `DeviceEventCode.OFFLINE_INSTRUCT_CALLBACK` | `11000` | 离线指令响应事件。 |

`IDeviceService.sendCusEvent(eventCode, extra)` 发送的是客户自定义事件，对应 `DeviceEventListener.onCusEvent(eventCode, extra)`，不会进入 `onDeviceEvent()`。`onCusEvent()` 的 `eventCode` 由业务方自行约定。

`DeviceEventCode.OFFLINE_INSTRUCT_CALLBACK` 当前不携带结构化 `extra`，调用方不要依赖 `extra` 内容做业务解析。

<a id="networktype"></a>

**NetworkType：网络访问方式**

`NetworkType` 用于 `IDeviceService.setNetworkType(type)` 和 `IDeviceService.getNetworkType()`。

| 取值 | 说明 |
| --- | --- |
| `0` | 手机中继访问。 |
| `1` | 眼镜直连访问。 |

调用 `setNetworkType(type)` 时只建议传 `0` 或 `1`。

<a id="error-code-note"></a>

**错误码说明**

多个服务回调中都会出现 `code` 参数，例如 `onError(code, message)`、`onFail(code, errMsg)`、`onFailed(code, errormsg)`。这些错误码不是全模块统一的一张错误码表，业务判断时应优先参考对应服务自己的常量或接口说明。

`0` 在部分枚举中可能表示 `success` 或 `none`，但不要把它作为所有错误回调里的业务失败码来依赖。

<a id="wifioperationstage"></a>

**WifiOperationStage：Wi-Fi 操作阶段**

`WifiOperationStage` 用于 `IWifiOperationCallback.onProgress(stage, message)` 的 `stage` 参数。

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `VALIDATING` | `1` | 校验 Wi-Fi 参数。 |
| `CONFIGURING` | `2` | 写入或更新 Wi-Fi 配置。 |
| `CONNECTING` | `3` | 正在连接目标 Wi-Fi。 |
| `VERIFYING` | `4` | 正在校验连接结果。 |
| `REMOVING` | `5` | 正在移除 Wi-Fi 配置。 |

Wi-Fi 操作阶段不保证固定完整顺序。参数校验失败时可能只回调校验阶段，自动安全类型探测时也可能出现阶段重复或跳过。

<a id="wifioperationcode"></a>

**WifiOperationCode：Wi-Fi 操作结果码**

`WifiOperationCode` 用于 `IWifiOperationCallback.onResult(code, message, networkId, ssid)` 的 `code` 参数。业务判断建议优先看 `code`，`message` 更适合用于日志和用户提示。失败时 `networkId` 可能为 `-1`。

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `SUCCESS` | `0` | 操作成功。 |
| `ERROR_INVALID_ARGUMENT` | `1` | 参数不合法。 |
| `ERROR_PERMISSION_DENIED` | `2` | 权限不足。 |
| `ERROR_UNSUPPORTED_SECURITY` | `3` | 不支持的 Wi-Fi 安全类型。 |
| `ERROR_WIFI_DISABLED` | `4` | Wi-Fi 未开启。 |
| `ERROR_ADD_OR_UPDATE_FAILED` | `5` | 添加或更新 Wi-Fi 配置失败。 |
| `ERROR_AUTH_FAILED` | `6` | 鉴权失败，通常与密码或认证方式有关。 |
| `ERROR_TIMEOUT` | `7` | 操作超时。 |
| `ERROR_NOT_FOUND` | `8` | 未找到目标 Wi-Fi 配置或记录。 |
| `ERROR_INTERNAL` | `100` | 内部异常。 |

<a id="wifisecuritytype"></a>

**WifiSecurityType：Wi-Fi 安全类型**

`securityType` 是 SDK 定义的 `Int` 类型安全类型常量，不是字符串，也不是直接透出的 Android 系统常量。`WifiConnectRequest.securityType`、`WifiNetworkInfo.securityType` 使用同一套取值；扫描或历史结果中还可能出现 `SECURITY_UNKNOWN = -1`，表示未知安全类型。因为它不是字符串，所以不存在大小写敏感问题。

<a id="wificonnectrequest"></a>

**WifiConnectRequest：Wi-Fi 连接参数**

`WifiConnectRequest` 用于 `IDeviceService.connectWifi(request, callback)` 和 `IDeviceService.connectWifiWithCallback(callback, request)`。

| 字段 | 说明 |
| --- | --- |
| `ssid` | 目标 Wi-Fi 名称，必填，不需要额外添加双引号。 |
| `bssid` | 目标 Wi-Fi BSSID，可选。 |
| `password` | Wi-Fi 密码，开放网络可为空。 |
| `securityType` | Wi-Fi 安全类型，取值见 [WifiSecurityType](#wifisecuritytype)。支持 open、WEP、WPA/WPA2-PSK、WPA3-Personal/SAE、Enterprise、WPA3-Enterprise、WPA2/WPA3 自动模式等。 |

<a id="wifiremoverequest"></a>

**WifiRemoveRequest：Wi-Fi 移除参数**

`WifiRemoveRequest` 用于 `IDeviceService.removeWifi(request, callback)`，`networkId`、`ssid`、`bssid` 至少传一个。

| 字段 | 说明 |
| --- | --- |
| `networkId` | 系统 Wi-Fi 配置 ID。 |
| `ssid` | 要移除的 Wi-Fi 名称。 |
| `bssid` | 要移除的 Wi-Fi BSSID。 |

<a id="wifinetworkinfo"></a>

**WifiNetworkInfo：Wi-Fi 连接记录**

`WifiNetworkInfo` 用于 `IDeviceService.getConnectedWifiList()` 和 `IDeviceService.getCurrentWifiInfo()` 的返回值。

| 字段 | 说明 |
| --- | --- |
| `networkId` | 系统 Wi-Fi 配置 ID。 |
| `ssid` | Wi-Fi 名称。 |
| `bssid` | Wi-Fi BSSID。 |
| `securityType` | Wi-Fi 安全类型，取值见 [WifiSecurityType](#wifisecuritytype)。 |
| `connected` | 是否为当前连接网络。 |

<a id="collectparam"></a>

**CollectParam：人脸采集参数**

`CollectParam` 用于 `ICollectService.sendFacePicture(identifier, param, callback)`。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `trackId` | `String` | 人脸跟踪 ID。 |
| `frameId` | `String` | 帧 ID。 |
| `faceScore` | `Float` | 人脸置信度，范围 `0` 到 `1.0`，数值越高表示置信度越高。 |
| `iqaScore` | `Float` | 图像质量分，范围 `0` 到 `100`，数值越高表示图像质量越好。 |

`faceScore` 和 `iqaScore` 主要用于业务侧参考识别结果质量。不同场景对阈值的要求可能不同，建议结合业务场景和实际样本效果决定是否过滤。

<a id="notificationmessage"></a>

**NotificationMessage：通知消息**

`NotificationMessage` 用于 `NotificationListener.onNotificationReceived(notification)`。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `pkg` | `String` | 应用包名。 |
| `appName` | `String` | 应用名称。 |
| `titleValue` | `String` | 通知标题。 |
| `msgValue` | `String` | 通知内容。 |
| `msgTime` | `Long` | 通知时间戳。 |

<a id="visual-data-models"></a>

**视觉识别数据结构**

| 数据结构 | 字段 | 说明 |
| --- | --- | --- |
| `FaceModel` | `frameWidth`, `frameHeight` | 图像帧宽高。 |
| `FaceModel` | `rect` | 人脸检测框。 |
| `FaceModel` | `trackId` | 人脸跟踪 ID。 |
| `FaceModel` | `faceScore` | 人脸置信度，范围 `0` 到 `1.0`，数值越高表示置信度越高。 |
| `FaceModel` | `iqaScore` | 图像质量分，范围 `0` 到 `100`，数值越高表示图像质量越好。 |
| `FaceModel` | `frameId` | 帧 ID。 |
| `LPRModel` | `frameWidth`, `frameHeight` | 图像帧宽高。 |
| `LPRModel` | `rect` | 车牌检测框。 |
| `LPRModel` | `score` | 车牌质量分，范围 `0` 到 `1.0`，数值越高表示结果越优。 |
| `LPRModel` | `plateNo` | 识别到的车牌号。 |
| `LPRModel` | `color` | 识别到的车牌颜色。 |

`FaceModel` 来自算法识别结果。`faceScore`、`iqaScore` 和 `LPRModel.score` 可用于业务侧筛选更可信的识别结果，但文档不提供固定阈值；阈值应结合识别场景、光照、距离和业务容错要求调整。
