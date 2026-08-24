package com.glass.dining.phone

import android.app.Application

class PhoneDiningApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneSdkHost.init(this)
    }
}
