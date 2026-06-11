package com.kavabanga.signage.data

import android.content.Context
import android.util.Log
import com.kavabanga.signage.model.ScheduleItem
import com.kavabanga.signage.model.Screen
import org.json.JSONArray
import org.json.JSONObject

class SignageRepository(private val context: Context) {

    companion object {
        private const val TAG = "SignageRepository"
    }

    private val rest = FirestoreRestClient()
    private val prefs by lazy { PrefsManager.getInstance(context) }

    fun fetchScreen(locationId: String, slotNumber: Int, onResult: (Screen?) -> Unit) {
        Log.d(TAG, "Fetching screen: loc=$locationId, slot=$slotNumber")

        rest.getSubcollection("locations", locationId, "screens",
            onSuccess = { docs ->
                val screenDoc = docs.find { doc ->
                    when (val s = doc["slot_number"]) {
                        is Long -> s.toInt() == slotNumber
                        is Number -> s.toInt() == slotNumber
                        else -> false
                    }
                }

                if (screenDoc == null) {
                    Log.w(TAG, "No screen for slot $slotNumber in ${docs.size} docs")
                    // Сервер ответил но слота нет — пробуем кэш
                    val cached = loadFromCache(locationId, slotNumber)
                    onResult(cached)
                    return@getSubcollection
                }

                try {
                    val parsed = parseScreen(screenDoc)
                    Log.d(TAG, "Screen parsed: ${parsed.schedule.size} items")

                    // Сохраняем в кэш для оффлайн-режима
                    saveToCache(locationId, slotNumber, parsed)

                    onResult(parsed)
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error", e)
                    onResult(loadFromCache(locationId, slotNumber))
                }
            },
            onError = {
                Log.e(TAG, "REST error: ${it.message}")
                // Нет сети — загружаем из кэша
                val cached = loadFromCache(locationId, slotNumber)
                if (cached != null) {
                    Log.i(TAG, "Загружено из оффлайн-кэша: ${cached.schedule.size} элементов")
                }
                onResult(cached)
            }
        )
    }

    // ── Кэширование расписания ─────────────────────────────

    private fun saveToCache(locationId: String, slotNumber: Int, screen: Screen) {
        try {
            val arr = JSONArray()
            for (item in screen.schedule) {
                val obj = JSONObject()
                obj.put("media_url", item.mediaUrl)
                obj.put("media_type", item.mediaType)
                obj.put("file_name", item.fileName)
                obj.put("has_schedule", item.hasSchedule)
                obj.put("end_time", item.endTime ?: JSONObject.NULL)
                arr.put(obj)
            }
            val json = JSONObject()
            json.put("id", screen.id)
            json.put("slot_number", screen.slotNumber)
            json.put("schedule", arr)

            prefs.saveScheduleCache(locationId, slotNumber, json.toString())
            Log.d(TAG, "Расписание сохранено в кэш")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения кэша", e)
        }
    }

    private fun loadFromCache(locationId: String, slotNumber: Int): Screen? {
        try {
            val raw = prefs.getScheduleCache(locationId, slotNumber) ?: return null
            val json = JSONObject(raw)
            val arr = json.getJSONArray("schedule")
            val schedule = mutableListOf<ScheduleItem>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                schedule.add(ScheduleItem(
                    mediaUrl = obj.optString("media_url", ""),
                    mediaType = obj.optString("media_type", ""),
                    fileName = obj.optString("file_name", ""),
                    hasSchedule = obj.optBoolean("has_schedule", false),
                    endTime = if (obj.isNull("end_time")) null else obj.optString("end_time")
                ))
            }

            return Screen(
                id = json.optString("id", ""),
                slotNumber = json.optInt("slot_number", slotNumber),
                schedule = schedule,
                updatedAt = 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения кэша", e)
            return null
        }
    }

    // ── Парсинг документа Firestore ────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseScreen(doc: Map<String, Any?>): Screen {
        val schedule = (doc["schedule"] as? List<*> ?: emptyList<Any>()).mapNotNull { item ->
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            ScheduleItem(
                mediaUrl = m["media_url"] as? String ?: "",
                mediaType = m["media_type"] as? String ?: "",
                fileName = m["file_name"] as? String ?: "",
                hasSchedule = m["has_schedule"] as? Boolean ?: false,
                endTime = m["end_time"] as? String
            )
        }

        return Screen(
            id = doc["__id__"] as? String ?: "",
            slotNumber = (doc["slot_number"] as? Long)?.toInt() ?: 0,
            schedule = schedule,
            updatedAt = 0L
        )
    }
}
