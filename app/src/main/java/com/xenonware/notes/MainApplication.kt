package com.xenonware.notes

import android.app.Application
import com.google.firebase.FirebaseApp

class MainApplication : Application() {
    companion object {
        @Volatile
        var isFirebaseAvailable: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { FirebaseApp.initializeApp(this) }
        isFirebaseAvailable = runCatching {
            FirebaseApp.getApps(this).isNotEmpty()
        }.getOrDefault(false)
    }
}
