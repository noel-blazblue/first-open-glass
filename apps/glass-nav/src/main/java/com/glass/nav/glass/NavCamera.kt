package com.glass.nav.glass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.glass.dining.shared.nav.NavProtocol
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class NavCamera(private val context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val inFlight = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    @Volatile var opened: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    var onJpeg: ((ByteArray) -> Boolean)? = null

    @SuppressLint("MissingPermission")
    fun start(): String {
        if (opened) return "相机已开"
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            lastError = "没有相机权限"
            return lastError!!
        }
        if (manager.cameraIdList.isEmpty()) {
            lastError = "没有相机"
            return lastError!!
        }
        lastError = null
        val cameraId = manager.cameraIdList[0]
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return "相机无输出配置".also { lastError = it }
        val jpegSize = pickSize(map.getOutputSizes(ImageFormat.JPEG), NavProtocol.JPEG_WIDTH, NavProtocol.JPEG_HEIGHT)
        val previewSize = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 320, 240)
        val camThread = HandlerThread("nav-cam")
        camThread.start()
        thread = camThread
        val camHandler = Handler(camThread.looper)
        handler = camHandler
        try {
            val imageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ pending ->
                val image = pending.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val accepted = onJpeg?.invoke(bytes) == true
                    if (!accepted) {
                        inFlight.set(false)
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "read jpeg failed", error)
                    inFlight.set(false)
                } finally {
                    image.close()
                }
            }, camHandler)
            val texture = SurfaceTexture(false)
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            surfaceTexture = texture
            val preview = Surface(texture)
            previewSurface = preview
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        try {
                            camera.createCaptureSession(
                                listOf(preview, imageReader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(captureSession: CameraCaptureSession) {
                                        session = captureSession
                                        try {
                                            val previewRequest = camera.createCaptureRequest(
                                                CameraDevice.TEMPLATE_PREVIEW,
                                            )
                                            previewRequest.addTarget(preview)
                                            captureSession.setRepeatingRequest(
                                                previewRequest.build(),
                                                null,
                                                camHandler,
                                            )
                                            opened = true
                                            lastError = null
                                            Log.i(TAG, "camera opened jpeg=${jpegSize.width}x${jpegSize.height}")
                                        } catch (error: Exception) {
                                            lastError = error.message ?: "预览失败"
                                            Log.w(TAG, "preview failed", error)
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        lastError = "相机会话失败"
                                    }
                                },
                                camHandler,
                            )
                        } catch (error: Exception) {
                            lastError = error.message ?: "打开相机会话失败"
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        lastError = "相机断开"
                        opened = false
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        lastError = when (error) {
                            ERROR_CAMERA_IN_USE -> "相机被占用，先关掉投屏"
                            ERROR_MAX_CAMERAS_IN_USE -> "相机被占用"
                            else -> "相机错误 $error"
                        }
                        opened = false
                        Log.w(TAG, lastError!!)
                    }
                },
                camHandler,
            )
        } catch (error: CameraAccessException) {
            lastError = error.message ?: "无法打开相机"
            return lastError!!
        } catch (error: SecurityException) {
            lastError = "没有相机权限"
            return lastError!!
        }
        return "正在打开相机"
    }

    fun captureIfIdle(): Boolean {
        if (!opened || inFlight.get()) return false
        val camera = device ?: return false
        val captureSession = session ?: return false
        val jpegSurface = reader?.surface ?: return false
        val camHandler = handler ?: return false
        if (!inFlight.compareAndSet(false, true)) return false
        return try {
            val still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            still.addTarget(jpegSurface)
            still.set(CaptureRequest.JPEG_QUALITY, NavProtocol.JPEG_QUALITY.toByte())
            captureSession.capture(still.build(), null, camHandler)
            true
        } catch (error: Exception) {
            inFlight.set(false)
            lastError = error.message
            Log.w(TAG, "capture failed", error)
            false
        }
    }

    fun markIdle() {
        inFlight.set(false)
    }

    fun busy(): Boolean = inFlight.get()

    fun stop() {
        opened = false
        inFlight.set(false)
        val camHandler = handler
        if (camHandler == null) {
            releaseLocked()
            return
        }
        camHandler.post {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            device?.close()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            previewSurface?.release()
        } catch (_: Exception) {
        }
        try {
            surfaceTexture?.release()
        } catch (_: Exception) {
        }
        session = null
        device = null
        reader = null
        previewSurface = null
        surfaceTexture = null
        thread?.quitSafely()
        thread = null
        handler = null
        Log.i(TAG, "camera stopped")
    }

    private fun pickSize(sizes: Array<Size>?, targetW: Int, targetH: Int): Size {
        val list = sizes?.toList().orEmpty()
        if (list.isEmpty()) return Size(targetW, targetH)
        val target = targetW * targetH
        return list.filter { it.width * it.height <= 640 * 480 * 2 }
            .minByOrNull { abs(it.width * it.height - target) }
            ?: list.minBy { abs(it.width * it.height - target) }
    }

    companion object {
        private const val TAG = "GlassNav"
    }
}
