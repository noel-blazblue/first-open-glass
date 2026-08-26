package com.glass.nav.phone

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.util.Pair
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import java.io.File

object NavAuth {
    const val TAG = "GlassNavPhone"
    const val REQUEST_CODE = 1101

    private val needed = arrayOf(
        GlassPermission.MICROPHONE,
        GlassPermission.CAMERA,
        GlassPermission.MEDIA,
    )

    fun start(activity: Activity): String {
        return requestPermissions(activity)
    }

    fun requestPermissions(activity: Activity): String {
        val helper = AuthorizationHelper
        val installed = try {
            helper.isRequiredRokidAppInstalled(activity)
        } catch (error: Exception) {
            Log.w(TAG, "check Rokid AI App failed", error)
            return "检查乐奇失败: ${error.message}"
        }
        if (!installed) {
            return "未检测到 Rokid AI App，或版本低于 1.7.14"
        }
        return try {
            val immediate: Pair<Int, Intent>? = helper.requestAuthorization(
                activity,
                needed,
                REQUEST_CODE,
            )
            if (immediate != null) {
                handleResult(activity, immediate.first, immediate.second)
            } else {
                "已跳转乐奇授权页，请勾选相机并同意"
            }
        } catch (error: Exception) {
            Log.w(TAG, "requestAuthorization failed", error)
            "拉起授权失败: ${error.message}"
        }
    }

    fun handleResult(activity: Activity, resultCode: Int, data: Intent?): String {
        val result = try {
            AuthorizationHelper.parseAuthorizationResult(resultCode, data)
        } catch (error: Exception) {
            Log.w(TAG, "parseAuthorizationResult failed", error)
            return "解析授权失败: ${error.message}"
        }
        return when (result) {
            is AuthResult.AuthSuccess -> {
                val token = result.token.orEmpty()
                if (token.isBlank()) {
                    "授权成功但 token 为空"
                } else {
                    saveToken(activity, token)
                    Log.i(TAG, "cxr token len=${token.length}")
                    NavLinkHost.connect(activity.application, token)
                }
            }
            is AuthResult.AuthFail -> "授权失败"
            is AuthResult.AuthCancel -> connectSaved(activity) ?: "授权已取消"
            else -> "未知授权结果 ${result?.javaClass?.simpleName}"
        }
    }

    private fun connectSaved(activity: Activity): String? {
        val saved = loadToken(activity) ?: return null
        return NavLinkHost.connect(activity.application, saved)
    }

    private fun saveToken(activity: Activity, token: String) {
        tokenFile(activity).writeText(token)
    }

    private fun loadToken(activity: Activity): String? {
        val file = tokenFile(activity)
        if (!file.exists()) return null
        return file.readText().trim().takeIf { it.isNotBlank() }
    }

    private fun tokenFile(activity: Activity): File {
        val dir = activity.getExternalFilesDir(null) ?: activity.filesDir
        return File(dir, "cxr-nav-token.txt")
    }
}
