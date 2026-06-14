package com.kavabanga.signage

import android.app.Application
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class SignageApp : Application() {

    companion object {
        const val CRASH_FILE = "crash_log.txt"
    }

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = throwable.stackTraceToString()
                Log.e("SignageApp", "💥 CRASH:\n$trace")

                // Пишем в ФАЙЛ — надёжнее чем SharedPreferences
                val crashFile = File(filesDir, CRASH_FILE)
                FileOutputStream(crashFile).use { fos ->
                    fos.write("${java.util.Date()}\n$trace".toByteArray())
                    fos.fd.sync() // Принудительный flush на диск
                }
            } catch (_: Throwable) {}

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
