package com.glass.dining.glass

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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

object AppCamera {
    private const val TAG = "GlassDining"

    @SuppressLint("MissingPermission")
    fun takeJpeg(context: Context, output: File, onDone: (File?, String?) -> Unit) {
        val main = Handler(context.mainLooper)
        fun done(file: File?, error: String?) {
            main.post { onDone(file, error) }
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            done(null, "没有相机权限")
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (manager.cameraIdList.isEmpty()) {
            done(null, "没有相机")
            return
        }
        val cameraId = manager.cameraIdList[0]
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            done(null, "相机无输出配置")
            return
        }
        val jpegSize = pickSize(map.getOutputSizes(ImageFormat.JPEG), 1280, 720)
        val previewSize = pickSize(map.getOutputSizes(SurfaceTexture::class.java), 640, 480)
        Log.i(TAG, "app camera jpeg=${jpegSize.width}x${jpegSize.height}")

        val thread = HandlerThread("app-cam")
        thread.start()
        val handler = Handler(thread.looper)
        var finished = false
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var surfaceTexture: SurfaceTexture? = null
        var previewSurface: Surface? = null

        fun cleanup() {
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
            thread.quitSafely()
        }

        fun finish(file: File?, error: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            handler.post {
                cleanup()
                done(file, error)
            }
        }

        handler.postDelayed({ finish(null, "拍照超时") }, 10_000L)

        try {
            reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ pending ->
                val image = pending.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { stream -> stream.write(bytes) }
                    Log.i(TAG, "app camera saved ${output.absolutePath} ${bytes.size} bytes")
                    finish(output, null)
                } catch (error: Exception) {
                    finish(null, error.message ?: "写照片失败")
                } finally {
                    image.close()
                }
            }, handler)

            surfaceTexture = SurfaceTexture(false)
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(surfaceTexture)
            val jpegSurface = reader.surface
            val preview = previewSurface

            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        try {
                            camera.createCaptureSession(
                                listOf(preview, jpegSurface),
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
                                                handler,
                                            )
                                            handler.postDelayed({
                                                if (finished) return@postDelayed
                                                try {
                                                    val still = camera.createCaptureRequest(
                                                        CameraDevice.TEMPLATE_STILL_CAPTURE,
                                                    )
                                                    still.addTarget(jpegSurface)
                                                    still.set(CaptureRequest.JPEG_QUALITY, 80.toByte())
                                                    captureSession.capture(
                                                        still.build(),
                                                        object : CameraCaptureSession.CaptureCallback() {
                                                            override fun onCaptureFailed(
                                                                sess: CameraCaptureSession,
                                                                request: android.hardware.camera2.CaptureRequest,
                                                                failure: android.hardware.camera2.CaptureFailure,
                                                            ) {
                                                                finish(null, "拍照失败")
                                                            }
                                                        },
                                                        handler,
                                                    )
                                                } catch (error: Exception) {
                                                    finish(null, error.message ?: "拍照请求失败")
                                                }
                                            }, 450L)
                                        } catch (error: Exception) {
                                            finish(null, error.message ?: "预览失败")
                                        }
                                    }

                                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                                        finish(null, "相机会话失败")
                                    }
                                },
                                handler,
                            )
                        } catch (error: Exception) {
                            finish(null, error.message ?: "打开相机会话失败")
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        finish(null, "相机断开")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        val reason = when (error) {
                            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "相机被占用，先关掉投屏"
                            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "相机被占用"
                            else -> "相机错误 $error"
                        }
                        finish(null, reason)
                    }
                },
                handler,
            )
        } catch (error: CameraAccessException) {
            finish(null, error.message ?: "无法打开相机")
        } catch (error: SecurityException) {
            finish(null, "没有相机权限")
        } catch (error: Exception) {
            finish(null, error.message ?: "打开相机失败")
        }
    }

    private fun pickSize(sizes: Array<Size>?, targetW: Int, targetH: Int): Size {
        val list = sizes?.toList().orEmpty()
        if (list.isEmpty()) return Size(targetW, targetH)
        val target = targetW * targetH
        return list.minBy { abs(it.width * it.height - target) }
    }
}
