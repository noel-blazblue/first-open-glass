package com.glass.dining.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Base64
import android.util.Log
import com.glass.dining.shared.protocol.DiningIds
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.open.sdk.client.IServiceConnectionCallback
import com.rokid.security.glass3.sdk.base.data.annotation.DeviceEventCode
import com.rokid.security.glass3.sdk.base.data.media.PhotoResolution
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import com.rokid.security.system.server.IClientCallback
import com.rokid.security.system.server.aichat.listener.AiChatListener
import com.rokid.security.system.server.asr.listener.SpeechCallback
import com.rokid.security.system.server.device.listener.DeviceEventListener
import com.rokid.security.system.server.media.callback.PhotoFileCallback
import com.rokid.security.system.server.message.listener.IMessageListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

object GlassSdkHost {
    private const val TAG = "GlassDining"
    const val ACTION_BUTTON_CLICK = "com.rokid.glass3.action.button.CLICK"

    @Volatile
    var ready: Boolean = false
        private set

    var onTextMessage: ((String) -> Unit)? = null
    var onLook: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onAsk: ((String) -> Unit)? = null
    var onListen: (() -> Unit)? = null
    var onAsrText: ((String) -> Unit)? = null
    var onAsrPartial: ((String) -> Unit)? = null
    var onAsrError: ((String) -> Unit)? = null
    var onListenState: ((Boolean) -> Unit)? = null
    var onSdkReady: ((Boolean) -> Unit)? = null
    var onAiReply: ((InboxClient.Reply?) -> Unit)? = null

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTriggerAt = 0L
    private var commandsRegistered = false
    private var photoCallbackBound = false
    private var buttonReceiverRegistered = false
    private var bindAttempts = 0
    private var bindGaveUp = false
    private var binding = false
    private var lookInFlight = false
    private var photoConsumed = false
    private var pendingPhotoPath: String? = null
    private var lookTimeout: Runnable? = null
    private var pendingLook: ((String?, Long) -> Unit)? = null
    private var pendingLookFailed: ((String) -> Unit)? = null
    private var aiAnswer = StringBuilder()
    private var asrStarted = false
    private var sdkAsr = false
    private var alwaysListen = false

    private val messageListener = object : IMessageListener.Stub() {
        override fun onTextMessage(msg: String?) {
            if (!msg.isNullOrBlank()) {
                onTextMessage?.invoke(msg)
            }
        }

        override fun onAudioStream(buffer: ByteArray?) {}

        override fun onStreamDataReceived(tag: String?, data: ByteArray?) {}
    }

    private val clientCallback = object : IClientCallback.Stub() {
        override fun onReady() {
            ready = true
            Log.i(TAG, "glass sdk ready = ${GlassSdk.isReady()}")
            bindMessageAndButtons()
            onSdkReady?.invoke(true)
        }
    }

    private val photoCallback = object : PhotoFileCallback.Stub() {
        override fun onTakePhoto(path: String) {
            Log.d(TAG, "onTakePhoto path=$path")
            handlePhoto(path)
        }

        override fun getCallbackId(): String = "dining-look"

        override fun onTakePhotoV2(path: String, width: Int, height: Int) {
            Log.d(TAG, "onTakePhotoV2 ${width}x$height path=$path")
            handlePhoto(path)
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        registerButtonReceiver(appContext)
        AppSpeech.preload(appContext)
        ensureReady()
    }

    fun ensureReady() {
        if (!::appContext.isInitialized) return
        if (GlassSdk.isReady()) {
            if (!ready) {
                ready = true
                Log.i(TAG, "glass sdk ready")
                bindMessageAndButtons()
            }
            onSdkReady?.invoke(true)
            return
        }
        if (bindGaveUp || binding) return
        if (bindAttempts >= 2) {
            bindGaveUp = true
            Log.w(TAG, "enterprise sdk unavailable, stop binding")
            return
        }
        binding = true
        bindAttempts += 1
        Log.i(TAG, "binding security service attempt=$bindAttempts")
        GlassSdk.bindSecurityService(
            appContext,
            object : IServiceConnectionCallback {
                override fun onServiceConnected() {
                    Log.i(TAG, "security service connected")
                    binding = false
                    bindGaveUp = false
                    GlassSdk.registerClient(DiningIds.CLIENT_ID, clientCallback)
                }

                override fun onServiceDisconnected() {
                    Log.w(TAG, "security service disconnected")
                    binding = false
                    ready = false
                    commandsRegistered = false
                    onSdkReady?.invoke(false)
                }

                override fun onBindingDied() {
                    Log.w(TAG, "security service died")
                    binding = false
                    ready = false
                    commandsRegistered = false
                    onSdkReady?.invoke(false)
                }
            },
        )
        mainHandler.postDelayed({
            if (!GlassSdk.isReady() && bindAttempts >= 2) {
                bindGaveUp = true
                binding = false
                Log.w(TAG, "enterprise sdk unavailable, stop binding")
            }
        }, 2_000L)
    }

    fun sendText(json: String) {
        if (!GlassSdk.isReady()) return
        val msg = GlassSdk.getGlassMessageService() ?: return
        try {
            msg.sendTextMessageByClassicBTWithClient(json, DiningIds.PHONE_CLIENT_ID)
        } catch (error: RemoteException) {
            Log.w(TAG, "classic bt send failed", error)
        }
        try {
            msg.sendTextMessageByP2PWithClient(json, DiningIds.PHONE_CLIENT_ID)
        } catch (error: RemoteException) {
            Log.w(TAG, "p2p send failed", error)
        }
    }

    fun speak(text: String, audioUrl: String? = null, onDone: (() -> Unit)? = null) {
        if (!audioUrl.isNullOrBlank()) {
            pauseAlwaysListen()
            AppTts.play(audioUrl) {
                mainHandler.post {
                    resumeAlwaysListen()
                    onDone?.invoke()
                }
            }
            return
        }
        if (text.isNotBlank() && GlassSdk.isReady()) {
            try {
                GlassSdk.getGlassOfflineTtsService()?.playTtsMsg(text)
            } catch (error: RemoteException) {
                Log.w(TAG, "tts failed", error)
            }
        }
        onDone?.invoke()
    }

    fun forwardUtterance(text: String) {
        InboxClient.send(appContext, text) { reply ->
            mainHandler.post { onAiReply?.invoke(reply) }
        }
    }

    fun captureAndRecognize(
        onResult: (visionHint: String?, fingerprint: Long) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        if (lookInFlight) {
            Log.i(TAG, "look already in flight")
            return
        }
        lookInFlight = true
        photoConsumed = false
        pendingLook = onResult
        pendingLookFailed = onFailed
        val dir = appContext.getExternalFilesDir(null) ?: appContext.cacheDir
        val file = File(dir, "look.jpg")
        pendingPhotoPath = file.absolutePath
        scheduleLookTimeout()
        Thread({
            pauseAlwaysListen()
            AppSpeech.awaitStopped(1_200L)
            AppSpeech.releaseModel()
            mainHandler.post { startCapture(file) }
        }, "pause-asr").start()
    }

    private fun startCapture(file: File) {
        val media = if (GlassSdk.isReady()) GlassSdk.getGlassMediaService() else null
        if (media != null) {
            try {
                media.takePhoto(PhotoResolution.RESOLUTION_720P, file.absolutePath)
                return
            } catch (error: Exception) {
                Log.w(TAG, "sdk takePhoto failed, fallback to Camera2", error)
            }
        }
        Log.i(TAG, "using app Camera2")
        AppCamera.takeJpeg(appContext, file) { captured, error ->
            if (captured != null) {
                handlePhoto(captured.absolutePath)
            } else {
                finishLookFailed(error ?: "拍照失败")
            }
        }
    }

    fun setAlwaysListen(enabled: Boolean) {
        alwaysListen = enabled
        if (enabled) {
            if (!lookInFlight) startListening()
        } else {
            stopListening()
        }
    }

    fun startListening() {
        if (asrStarted || AppSpeech.running) return
        val mediaAsr = if (GlassSdk.isReady()) GlassSdk.getGlassAsrService() else null
        if (mediaAsr != null) {
            try {
                mediaAsr.startSpeech(speechCallback)
                asrStarted = true
                sdkAsr = true
                mainHandler.post { onListenState?.invoke(true) }
                return
            } catch (error: RemoteException) {
                Log.w(TAG, "startSpeech failed", error)
            }
        }
        Log.i(TAG, "using app Vosk ASR")
        sdkAsr = false
        asrStarted = true
        mainHandler.post { onListenState?.invoke(true) }
        AppSpeech.start(
            appContext,
            onPartial = { text ->
                if (text.isNotBlank()) onAsrPartial?.invoke(text)
            },
            onResult = { text ->
                val spoken = text.trim()
                if (spoken.isNotBlank()) {
                    mainHandler.post { onAsrText?.invoke(spoken) }
                }
            },
            onError = { message ->
                asrStarted = false
                mainHandler.post {
                    onListenState?.invoke(false)
                    onAsrError?.invoke(message)
                }
                if (alwaysListen && !lookInFlight) {
                    mainHandler.postDelayed({ startListening() }, 1_500L)
                }
            },
        )
    }

    fun stopListening() {
        if (sdkAsr) {
            if (!asrStarted) return
            try {
                GlassSdk.getGlassAsrService()?.stopSpeech()
            } catch (error: RemoteException) {
                Log.w(TAG, "stopSpeech failed", error)
            }
            asrStarted = false
            sdkAsr = false
            mainHandler.post { onListenState?.invoke(false) }
            return
        }
        asrStarted = false
        Log.i(TAG, "stopListening")
        AppSpeech.stop()
        mainHandler.post { onListenState?.invoke(false) }
    }

    private fun pauseAlwaysListen() {
        if (AppSpeech.running || asrStarted) {
            Log.i(TAG, "pause asr for camera")
            stopListening()
        }
    }

    private fun resumeAlwaysListen() {
        if (alwaysListen && !lookInFlight && !AppSpeech.running) {
            startListening()
        }
    }

    private fun bindMessageAndButtons() {
        try {
            GlassSdk.getGlassMessageService()?.setMessageListener(messageListener)
            GlassSdk.getGlassDeviceService()?.setDeviceEventListener(
                object : DeviceEventListener.Stub() {
                    override fun onDeviceEvent(eventCode: Int, extra: String?) {
                        when (eventCode) {
                            DeviceEventCode.BUTTON_ONE_CLICK -> triggerLook()
                        }
                    }

                    override fun onCusEvent(eventCode: Int, extra: String?) {}
                },
            )
            if (!photoCallbackBound) {
                GlassSdk.getGlassMediaService()?.addPhotoCallback(photoCallback)
                photoCallbackBound = true
            }
            registerOfflineCommands()
        } catch (error: RemoteException) {
            Log.w(TAG, "bind listeners failed", error)
        }
    }

    private fun registerOfflineCommands() {
        val cmd = GlassSdk.getGlassOfflineCmdService() ?: return
        if (commandsRegistered) return
        try {
            GlassSdk.setOfflineCmdLanguage("ZH_CN")
            cmd.add(voice("开始看店", "kai shi kan dian") { triggerLook() })
            cmd.add(voice("识别门店", "shi bie men dian") { triggerLook() })
            cmd.add(voice("下一家", "xia yi jia") { triggerNext() })
            cmd.add(voice("排队多久", "pai dui duo jiu") { onAsk?.invoke("排队多久") })
            cmd.add(voice("有什么优惠", "you shen me you hui") { onAsk?.invoke("有什么优惠") })
            commandsRegistered = true
            Log.i(TAG, "offline commands registered")
        } catch (error: RemoteException) {
            Log.w(TAG, "offline cmd failed", error)
        }
    }

    private fun voice(text: String, pinyin: String, action: () -> Unit): VoiceAction {
        return VoiceAction(
            text,
            pinyin,
            object : IVoiceCallback.Stub() {
                override fun onVoiceTriggered() {
                    mainHandler.post { action() }
                }
            },
        )
    }

    private fun registerButtonReceiver(context: Context) {
        if (buttonReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_BUTTON_CLICK)
            priority = 100
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_BUTTON_CLICK) return
                Log.i(TAG, "button click broadcast")
                if (isOrderedBroadcast) {
                    abortBroadcast()
                }
                triggerLook()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        buttonReceiverRegistered = true
        Log.i(TAG, "button receiver registered")
    }

    fun handleHardwareKey(keyCode: Int, action: Int): Boolean {
        if (action != android.view.KeyEvent.ACTION_UP) {
            val downKey = keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == android.view.KeyEvent.KEYCODE_NOTIFICATION ||
                keyCode == 83
            return downKey
        }
        val lookKey = keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == android.view.KeyEvent.KEYCODE_NOTIFICATION ||
            keyCode == 83
        if (!lookKey) {
            Log.i(TAG, "unhandled key code=$keyCode")
            return false
        }
        Log.i(TAG, "hardware look key code=$keyCode")
        triggerLook()
        return true
    }

    fun debugListen(holdMs: Long = 4_000L) {
        Log.i(TAG, "debugListen ${holdMs}ms")
        alwaysListen = true
        startListening()
    }

    private fun triggerLook() {
        Log.i(TAG, "triggerLook ready=$ready onLook=${onLook != null}")
        if (!debounce(900)) return
        mainHandler.post { onLook?.invoke() }
    }

    private fun triggerNext() {
        if (!debounce(400)) return
        onNext?.invoke()
    }

    private fun triggerListen() {
        if (!debounce(400)) return
        onListen?.invoke()
        startListening()
    }

    private fun debounce(gapMs: Long): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < gapMs) return false
        lastTriggerAt = now
        return true
    }

    private fun handlePhoto(path: String) {
        if (!lookInFlight || photoConsumed) return
        photoConsumed = true
        val file = when {
            path.isNotBlank() && File(path).exists() -> File(path)
            pendingPhotoPath != null && File(pendingPhotoPath!!).exists() -> File(pendingPhotoPath!!)
            else -> {
                finishLookFailed("没有拍到照片")
                return
            }
        }
        pendingPhotoPath = file.absolutePath
        val fingerprint = crc32(file)
        if (GlassSdk.isReady()) {
            askVision(file, fingerprint)
        } else {
            finishLook(null, fingerprint)
        }
    }

    private fun askVision(file: File, fingerprint: Long) {
        val ai = GlassSdk.getGlassAiChatService()
        val image = jpegBase64(file)
        if (ai == null || image.isNullOrBlank()) {
            finishLook(null, fingerprint)
            return
        }
        aiAnswer = StringBuilder()
        try {
            ai.startAiChat(false)
            ai.toAiCode(
                image,
                object : AiChatListener.Stub() {
                    override fun onContinuousModeUpdate(
                        continuousMode: Boolean,
                        timeout: Long,
                        keepSessionActive: Boolean,
                    ) {}

                    override fun onAiChatAnswer(
                        answer: String?,
                        isFinish: Boolean,
                        contentType: String?,
                        sessionId: String?,
                    ) {
                        if (!answer.isNullOrBlank()) aiAnswer.append(answer)
                        if (isFinish) {
                            finishLook(aiAnswer.toString(), fingerprint)
                        }
                    }

                    override fun onError(code: Int, message: String) {
                        Log.w(TAG, "ai chat error $code $message")
                        finishLook(null, fingerprint)
                    }

                    override fun onAiTakePhoto(filePath: String) {}
                },
            )
        } catch (error: Exception) {
            Log.w(TAG, "toAiCode failed", error)
            finishLook(null, fingerprint)
        }
    }

    private fun scheduleLookTimeout() {
        lookTimeout?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (!lookInFlight) return@Runnable
            Log.w(TAG, "look timed out")
            val file = pendingPhotoPath?.let { File(it) }?.takeIf { it.exists() }
            if (file != null) {
                val hint = aiAnswer.toString().ifBlank { null }
                finishLook(hint, crc32(file))
            } else {
                finishLookFailed("识别超时")
            }
        }
        lookTimeout = timeout
        mainHandler.postDelayed(timeout, 12_000L)
    }

    private fun finishLook(hint: String?, fingerprint: Long) {
        if (!lookInFlight) return
        lookInFlight = false
        lookTimeout?.let { mainHandler.removeCallbacks(it) }
        lookTimeout = null
        endAiChatQuietly()
        val callback = pendingLook
        pendingLook = null
        pendingLookFailed = null
        mainHandler.post { callback?.invoke(hint, fingerprint) }
        mainHandler.postDelayed({ resumeAlwaysListen() }, 800L)
    }

    private fun finishLookFailed(reason: String) {
        if (!lookInFlight && pendingLookFailed == null) return
        lookInFlight = false
        lookTimeout?.let { mainHandler.removeCallbacks(it) }
        lookTimeout = null
        endAiChatQuietly()
        val callback = pendingLookFailed
        pendingLook = null
        pendingLookFailed = null
        mainHandler.post { callback?.invoke(reason) }
        mainHandler.postDelayed({ resumeAlwaysListen() }, 800L)
    }

    private fun endAiChatQuietly() {
        try {
            GlassSdk.getGlassAiChatService()?.endAiChat()
        } catch (_: Exception) {
        }
    }

    private val speechCallback = object : SpeechCallback.Stub() {
        override fun onStart() {
            mainHandler.post { onListenState?.invoke(true) }
        }

        override fun onIntermediateVad(content: String) {}

        override fun onAsrComplete(content: String?) {
            asrStarted = false
            val text = content.orEmpty().trim()
            mainHandler.post {
                onListenState?.invoke(false)
                if (text.isBlank()) {
                    onAsrError?.invoke("没听清，请再说一遍")
                } else {
                    onAsrText?.invoke(text)
                }
            }
        }

        override fun onAsrCompleteWithIntent(content: String?, intent: Int, intentJson: String) {}

        override fun onError(code: Int) {
            asrStarted = false
            Log.w(TAG, "asr error $code")
            mainHandler.post {
                onListenState?.invoke(false)
                onAsrError?.invoke("在线语音不可用，请说离线口令")
            }
        }

        override fun onServiceConnectState(connect: Boolean) {
            if (!connect) {
                asrStarted = false
                mainHandler.post { onListenState?.invoke(false) }
            }
        }
    }

    private fun jpegBase64(file: File): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val maxEdge = 640
            val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            val sample = (longest / maxEdge).coerceAtLeast(1)
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            val scaled = if (bitmap.width > maxEdge || bitmap.height > maxEdge) {
                val ratio = maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (error: Exception) {
            Log.w(TAG, "jpeg encode failed", error)
            null
        }
    }

    private fun crc32(file: File): Long {
        return try {
            val crc = CRC32()
            crc.update(file.readBytes())
            crc.value
        } catch (_: Exception) {
            file.length()
        }
    }
}
