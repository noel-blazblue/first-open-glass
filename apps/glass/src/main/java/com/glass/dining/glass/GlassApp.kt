package com.glass.dining.glass

import android.app.Application

class GlassApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GlassWifi.hold(this)
    }
}
