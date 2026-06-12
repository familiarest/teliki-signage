package com.kavabanga.signage.data

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "kavabanga_signage_prefs"
        private const val KEY_LOCATION_ID = "location_id"
        private const val KEY_LOCATION_NAME = "location_name"
        private const val KEY_SLOT_NUMBER = "slot_number"
        private const val KEY_LAST_SYNC = "last_sync_time"

        @Volatile
        private var instance: PrefsManager? = null

        fun getInstance(context: Context): PrefsManager {
            return instance ?: synchronized(this) {
                instance ?: PrefsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(locationId: String, locationName: String, slotNumber: Int) {
        prefs.edit()
            .putString(KEY_LOCATION_ID, locationId)
            .putString(KEY_LOCATION_NAME, locationName)
            .putInt(KEY_SLOT_NUMBER, slotNumber)
            .apply()
    }

    fun getLocationId(): String? {
        return prefs.getString(KEY_LOCATION_ID, null)
    }

    fun getLocationName(): String? {
        return prefs.getString(KEY_LOCATION_NAME, null)
    }

    fun getSlotNumber(): Int {
        return prefs.getInt(KEY_SLOT_NUMBER, 1)
    }

    fun isConfigured(): Boolean {
        return getLocationId() != null
    }

    // Кэш расписания для оффлайн-режима
    fun saveScheduleCache(locationId: String, slotNumber: Int, json: String) {
        prefs.edit().putString("schedule_cache_${locationId}_$slotNumber", json).apply()
    }

    fun getScheduleCache(locationId: String, slotNumber: Int): String? {
        return prefs.getString("schedule_cache_${locationId}_$slotNumber", null)
    }

    fun saveLastSyncTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC, time).apply()
    }

    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC, 0L)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
