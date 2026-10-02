package com.example.bead

import android.app.Application
import com.example.bead.data.BeadStore
import com.example.bead.data.BeadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BeadApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store by lazy { BeadStore(this) }
    val repository by lazy { BeadRepository(store) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { repository.ensureSeeded() }
    }
}
