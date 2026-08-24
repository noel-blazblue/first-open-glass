package com.glass.dining.glass

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.glass.dining.shared.hud.StoreHud

class HudActivity : ComponentActivity() {
    private val viewModel: HudViewModel by viewModels()
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true)
        }
        requestRuntimePermissions()
        GlassSdkHost.init(applicationContext)
        setContent {
            val state by viewModel.ui.collectAsState()
            StoreHud(state = state, modifier = Modifier.fillMaxSize())
        }
        handleDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        GlassSdkHost.ensureReady()
        GlassSdkHost.setAlwaysListen(true)
    }

    override fun onDestroy() {
        GlassSdkHost.setAlwaysListen(false)
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (GlassSdkHost.handleHardwareKey(event.keyCode, event.action)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (GlassSdkHost.handleHardwareKey(keyCode, KeyEvent.ACTION_UP)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun requestRuntimePermissions() {
        val needed = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        ).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun handleDebugIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("listen", false)) {
            GlassSdkHost.debugListen(4_000L)
        }
        val hud = intent.getStringExtra("hud")
        if (!hud.isNullOrBlank()) {
            viewModel.showStatus(hud)
        }
        when (intent.getStringExtra("cursor_cmd")) {
            "look" -> viewModel.look()
            "next" -> viewModel.next()
            "ask" -> viewModel.ask(intent.getStringExtra("text").orEmpty())
        }
    }
}
