package com.example.millisautosend

import android.app.Application

class MillisAutoSendApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuBridge.initialize(this)
    }
}
