package com.glass.dining.phone

import android.app.Application
import com.rokid.cxr.link.CXRLink

class PhoneDiningApp : Application() {
    var sharedLink: CXRLink? = null

    override fun onCreate() {
        super.onCreate()
        PhoneAi.load(this)
        PhoneTts.init(this)
        GlassAsr.preload(this)
    }
}
