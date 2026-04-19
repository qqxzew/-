package com.meemaw.defender

import android.app.Application

class DefenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: DefenderApp
            private set
    }
}
