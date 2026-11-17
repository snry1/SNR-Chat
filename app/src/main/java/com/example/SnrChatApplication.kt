package com.example

import android.app.Application
import com.example.core.SnrContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SnrChatApplication : Application() {
    lateinit var container: SnrContainer
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = SnrContainer(this)
        appScope.launch {
            // Initialize Firebase, DataStore & notification channels off the main thread
            container.warmUp()
        }
    }
}

