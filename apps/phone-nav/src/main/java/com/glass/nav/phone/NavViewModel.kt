package com.glass.nav.phone

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.glass.dining.shared.nav.NavHint
import com.glass.dining.shared.nav.NavProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class NavPhoneUi(
    val status: String = "未连接",
    val navigating: Boolean = false,
    val stepIndex: Int = 0,
    val hint: NavHint = NavHint(),
    val yaw: Float = 0f,
    val photo: Bitmap? = null,
    val photoCount: Int = 0,
    val lastPhotoBytes: Int = 0,
)

class NavViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(NavPhoneUi())
    val ui: StateFlow<NavPhoneUi> = _ui

    init {
        NavLinkHost.onStatus = { line ->
            _ui.update { it.copy(status = line) }
        }
        NavLinkHost.onReady = {
            _ui.update { it.copy(status = "眼镜 server 就绪") }
            if (_ui.value.navigating) {
                NavLinkHost.startIndoorNav(_ui.value.hint)
            }
        }
        NavLinkHost.onPose = { yaw ->
            _ui.update { it.copy(yaw = yaw) }
        }
        NavLinkHost.onFrame = { jpeg ->
            onFrame(jpeg)
        }
    }

    fun setStatus(line: String) {
        _ui.update { it.copy(status = line) }
    }

    fun startIndoor() {
        val first = NavProtocol.scriptFor("目的店", 40).firstOrNull() ?: return
        _ui.update {
            it.copy(
                navigating = true,
                stepIndex = 0,
                hint = first,
                status = "开始室内指引：${first.text}",
            )
        }
        NavLinkHost.startIndoorNav(first)
    }

    fun stopIndoor() {
        _ui.update { it.copy(navigating = false, status = "已停止室内指引") }
        NavLinkHost.stopGlassApp()
    }

    fun calibrate() {
        NavLinkHost.calibrate()
        _ui.update { it.copy(status = "已发送朝向校准") }
    }

    fun launchGlass() {
        NavLinkHost.queryAndStart()
    }

    private fun onFrame(jpeg: ByteArray) {
        val bitmap = decode(jpeg)
        val nextIndex = (_ui.value.stepIndex + 1).coerceAtMost(
            NavProtocol.scriptFor("目的店", 40).lastIndex.coerceAtLeast(0),
        )
        val script = NavProtocol.scriptFor("目的店", 40)
        val hint = if (_ui.value.navigating && script.isNotEmpty()) {
            script[nextIndex]
        } else {
            _ui.value.hint
        }
        _ui.update {
            it.copy(
                photo = bitmap,
                photoCount = it.photoCount + 1,
                lastPhotoBytes = jpeg.size,
                stepIndex = if (it.navigating) nextIndex else it.stepIndex,
                hint = if (it.navigating) hint else it.hint,
                status = "收到路标图 ${jpeg.size}B · ${hint.text}",
            )
        }
        if (_ui.value.navigating) {
            NavLinkHost.updateHint(hint)
        }
        NavLinkHost.ackFrame()
        Log.i(TAG, "frame n=${_ui.value.photoCount} bytes=${jpeg.size} hint=${hint.text}")
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val sample = when {
                bounds.outWidth > 1280 -> 4
                bounds.outWidth > 640 -> 2
                else -> 1
            }
            BitmapFactory.decodeByteArray(
                jpeg,
                0,
                jpeg.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (error: Exception) {
            Log.w(TAG, "decode jpeg failed", error)
            null
        }
    }

    companion object {
        private const val TAG = "GlassNavPhone"
    }
}
