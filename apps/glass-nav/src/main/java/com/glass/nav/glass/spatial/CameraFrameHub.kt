package com.glass.nav.glass.spatial

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 单一 Camera2 会话：YUV 给 VIO，可选 I420 给 WebRTC，避免双 owner。
 */
class CameraFrameHub(private val context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var texture: SurfaceTexture? = null
    private var preview: Surface? = null
    @Volatile var opened: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    var onGray: ((tNs: Long, width: Int, height: Int, gray: ByteArray, sharpness: Double) -> Unit)? = null
    private val rtcLock = Any()
    private var rtcObserver: CapturerObserver? = null
    private val running = AtomicBoolean(false)

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
        val cameraId = manager.cameraIdList[0]
        val map = manager.getCameraCharacteristics(cameraId)
            .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return "相机无输出配置".also { lastError = it }
        val yuv = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888), 640, 480)
        val previewSize = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 320, 240)
        val camThread = HandlerThread("spatial-cam")
        camThread.start()
        thread = camThread
        val camHandler = Handler(camThread.looper)
        handler = camHandler
        val imageReader = ImageReader.newInstance(yuv.width, yuv.height, ImageFormat.YUV_420_888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ pending ->
            val image = pending.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                onImage(image)
            } catch (error: Exception) {
                Log.w(TAG, "yuv frame ${error.message}")
            } finally {
                image.close()
            }
        }, camHandler)
        val st = SurfaceTexture(false)
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        texture = st
        val previewSurface = Surface(st)
        preview = previewSurface
        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    camera.createCaptureSession(
                        listOf(previewSurface, imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(captureSession: CameraCaptureSession) {
                                session = captureSession
                                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                req.addTarget(previewSurface)
                                req.addTarget(imageReader.surface)
                                captureSession.setRepeatingRequest(req.build(), null, camHandler)
                                opened = true
                                running.set(true)
                                lastError = null
                                Log.i(TAG, "frame hub ${yuv.width}x${yuv.height}")
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                lastError = "相机会话失败"
                            }
                        },
                        camHandler,
                    )
                }
                override fun onDisconnected(camera: CameraDevice) {
                    opened = false
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    lastError = "相机错误 $error"
                    opened = false
                }
            },
            camHandler,
        )
        return "正在打开相机"
    }

    fun attachRtc(observer: CapturerObserver) {
        synchronized(rtcLock) { rtcObserver = observer }
    }

    fun detachRtc() {
        synchronized(rtcLock) { rtcObserver = null }
    }

    fun stop() {
        opened = false
        running.set(false)
        detachRtc()
        handler?.post { releaseLocked() } ?: releaseLocked()
    }

    private fun onImage(image: Image) {
        val tNs = image.timestamp
        val gray = yGray(image, GRAY_W, GRAY_H)
        val sharp = sharpness(GRAY_W, GRAY_H, gray)
        onGray?.invoke(tNs, GRAY_W, GRAY_H, gray, sharp)
            val observer = synchronized(rtcLock) { rtcObserver } ?: return
            val i420 = try {
                toI420(image)
            } catch (error: Exception) {
                Log.w(TAG, "i420 ${error.message}")
                null
            } ?: return
        val frame = VideoFrame(i420, 0, tNs)
        observer.onFrameCaptured(frame)
        frame.release()
    }

    private fun releaseLocked() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { preview?.release() } catch (_: Exception) {}
        try { texture?.release() } catch (_: Exception) {}
        session = null
        device = null
        reader = null
        preview = null
        texture = null
        thread?.quitSafely()
        thread = null
        handler = null
        Log.i(TAG, "frame hub stopped")
    }

    private fun pickSize(sizes: Array<Size>?, targetW: Int, targetH: Int): Size {
        val list = sizes?.toList().orEmpty()
        if (list.isEmpty()) return Size(targetW, targetH)
        val target = targetW * targetH
        return list.filter { it.width * it.height <= 1280 * 720 }
            .minByOrNull { abs(it.width * it.height - target) }
            ?: list.minBy { abs(it.width * it.height - target) }
    }

    companion object {
        private const val TAG = "GlassNav"
        const val GRAY_W = 160
        const val GRAY_H = 90

        fun yGray(image: Image, outW: Int, outH: Int): ByteArray {
            val y = image.planes[0]
            val srcW = image.width
            val srcH = image.height
            val stride = y.rowStride
            val buf = y.buffer.duplicate()
            val gray = ByteArray(outW * outH)
            for (oy in 0 until outH) {
                val sy = oy * srcH / outH
                for (ox in 0 until outW) {
                    val sx = ox * srcW / outW
                    gray[oy * outW + ox] = buf.get(sy * stride + sx)
                }
            }
            return gray
        }

        fun sharpness(width: Int, height: Int, gray: ByteArray): Double {
            var sum = 0.0
            var n = 0
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val i = y * width + x
                    val lap = (gray[i - 1].toInt() and 0xff) + (gray[i + 1].toInt() and 0xff) +
                        (gray[i - width].toInt() and 0xff) + (gray[i + width].toInt() and 0xff) -
                        4 * (gray[i].toInt() and 0xff)
                    sum += lap * lap
                    n++
                }
            }
            return if (n == 0) 0.0 else sum / n
        }

        fun toI420(image: Image): JavaI420Buffer? {
            val w = image.width
            val h = image.height
            val buffer = JavaI420Buffer.allocate(w, h)
            copyPlane(image.planes[0], buffer.dataY, w, h, buffer.strideY)
            copyChroma(image.planes[1], buffer.dataU, w / 2, h / 2, buffer.strideU)
            copyChroma(image.planes[2], buffer.dataV, w / 2, h / 2, buffer.strideV)
            return buffer
        }

        private fun copyPlane(plane: Image.Plane, dst: ByteBuffer, width: Int, height: Int, dstStride: Int) {
            val src = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val row = ByteArray(width)
            for (y in 0 until height) {
                if (pixelStride == 1) {
                    src.position(y * rowStride)
                    src.get(row, 0, width)
                } else {
                    var sx = y * rowStride
                    for (x in 0 until width) {
                        row[x] = src.get(sx)
                        sx += pixelStride
                    }
                }
                dst.position(y * dstStride)
                dst.put(row)
            }
        }

        private fun copyChroma(plane: Image.Plane, dst: ByteBuffer, width: Int, height: Int, dstStride: Int) {
            val src = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val row = ByteArray(width)
            for (y in 0 until height) {
                var sx = y * rowStride
                for (x in 0 until width) {
                    row[x] = src.get(sx)
                    sx += pixelStride
                }
                dst.position(y * dstStride)
                dst.put(row)
            }
        }
    }
}
