package com.fireball1725.librarium

import android.app.Application
import com.fireball1725.librarium.core.di.AppContainer

class LibrariumApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
