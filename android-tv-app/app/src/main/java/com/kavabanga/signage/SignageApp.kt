package com.kavabanga.signage

import android.app.Application
import com.google.firebase.FirebaseApp
import android.util.Log

class SignageApp : Application() {

    companion object {
        private const val TAG = "SignageApp"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseApp.initializeApp(this)
            Log.i(TAG, "Firebase initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase", e)
        }
    }
}
