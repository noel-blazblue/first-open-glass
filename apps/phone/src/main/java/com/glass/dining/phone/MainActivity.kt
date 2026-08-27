package com.glass.dining.phone

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.glass.dining.phone.nav.PhoneGps

class MainActivity : ComponentActivity() {
    private val viewModel: DiningViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
        if (!spoken.isNullOrBlank()) {
            viewModel.onHeard(spoken)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.onStartVoice = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "说看店识别，或问这家店")
            }
            speechLauncher.launch(intent)
        }
        viewModel.onNeedGlassAuth = {
            viewModel.setStatus(CxrAuth.requestPermissions(this))
        }
        requestNeededPermissions()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent.getBooleanExtra("stop_glass", false)) {
            CxrLinkHost.closeGlassWhenReady = true
        }
        setContent {
            PhoneDiningScreen(viewModel)
        }
        viewModel.setStatus(CxrAuth.start(this))
        if (intent.getBooleanExtra("stop_glass", false)) {
            viewModel.setStatus("正在关掉镜片到餐页…")
        }
        applyExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyExtras(intent)
    }

    private fun applyExtras(intent: Intent) {
        if (intent.getBooleanExtra("rtc_stop", false)) {
            viewModel.stopVisionLink()
        }
        if (intent.getBooleanExtra("rtc_start", false)) {
            viewModel.startVisionLink()
        }
        intent.getStringExtra("ask")?.let { viewModel.onHeard(it) }
    }

    override fun onResume() {
        super.onResume()
        PhoneGps.start(this)
        if (!intent.getBooleanExtra("stop_glass", false)) {
            viewModel.onPhoneForeground()
        }
        viewModel.reloadCatalog()
    }

    override fun onPause() {
        viewModel.onPhoneBackground()
        PhoneGps.stop()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CxrAuth.REQUEST_CODE) {
            viewModel.setStatus(CxrAuth.handleResult(this, resultCode, data))
        }
    }

    private fun requestNeededPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
