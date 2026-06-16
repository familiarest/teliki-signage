package com.kavabanga.signage

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class SignageApp : Application() {

    companion object {
        const val CRASH_FILE = "crash_log.txt"
        private const val CRASH_PREFS = "crash_counter"
        private const val KEY_CRASH_COUNT = "crash_count"
        private const val KEY_LAST_CRASH_TIME = "last_crash_time"
        const val MAX_CRASHES_BEFORE_STOP = 3
        private const val CRASH_WINDOW_MS = 300_000L // 5 минут

        /**
         * Количество крэшей за последние 5 минут.
         * Если >= MAX_CRASHES_BEFORE_STOP, SetupActivity НЕ должна автозапускать плеер.
         */
        fun getCrashCount(context: Context): Int {
            val prefs = context.getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
            val lastCrash = prefs.getLong(KEY_LAST_CRASH_TIME, 0)
            // Если последний крэш был давно — счётчик не актуален
            if (System.currentTimeMillis() - lastCrash > CRASH_WINDOW_MS) {
                return 0
            }
            return prefs.getInt(KEY_CRASH_COUNT, 0)
        }

        /**
         * Сбрасывает счётчик крэшей (вызывать после успешной работы 5+ минут).
         */
        fun resetCrashCounter(context: Context) {
            context.getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_CRASH_COUNT, 0)
                .apply()
        }
    }

    /**
     * Вызывается из PlayerActivity.onCreate() — значит запуск прошёл успешно.
     * Удаляем crash file.
     */
    fun onPlayerStarted() {
        val crashFile = File(filesDir, CRASH_FILE)
        if (crashFile.exists()) crashFile.delete()
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

                // Увеличиваем счётчик крэшей
                val prefs = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE)
                val lastCrash = prefs.getLong(KEY_LAST_CRASH_TIME, 0)
                val oldCount = if (System.currentTimeMillis() - lastCrash > CRASH_WINDOW_MS) 0
                               else prefs.getInt(KEY_CRASH_COUNT, 0)
                prefs.edit()
                    .putInt(KEY_CRASH_COUNT, oldCount + 1)
                    .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
                    .commit() // commit, не apply — нужен sync до смерти процесса
                Log.e("SignageApp", "Crash count: ${oldCount + 1}")
            } catch (_: Throwable) {}

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
