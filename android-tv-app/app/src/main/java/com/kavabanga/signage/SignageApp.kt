package com.kavabanga.signage

import android.app.Application
import android.util.Log

class SignageApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Глобальный обработчик крэшей — сохраняет стектрейс ПЕРЕД смертью процесса
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = throwable.stackTraceToString()
                Log.e("SignageApp", "💥 CRASH:\n$trace")

                // commit() вместо apply() — синхронная запись до смерти процесса
                getSharedPreferences("crash", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", trace)
                    .putLong("crash_time", System.currentTimeMillis())
                    .commit()
            } catch (_: Exception) {}

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
