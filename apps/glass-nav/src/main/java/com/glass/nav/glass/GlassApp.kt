package com.glass.nav.glass

import android.app.Application

class GlassApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GlassWifi.hold(this)
    }
}
