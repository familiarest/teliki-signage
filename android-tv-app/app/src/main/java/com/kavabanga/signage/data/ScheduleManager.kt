package com.kavabanga.signage.data

import android.util.Log
import org.json.JSONObject
import java.util.Calendar

/**
 * Менеджер расписания. Парсит schedule.json и определяет текущий контент.
 *
 * Формат schedule.json:
 * {
 *   "items": [
 *     {"file": "menu_утро.jpg", "start": "06:00", "end": "14:00"},
 *     {"file": "menu_вечер.jpg", "start": "14:00", "end": "23:00"}
 *   ]
 * }
 *
 * Если schedule.json нет — показываем все файлы по очереди.
 */
class ScheduleManager {

    companion object {
        private const val TAG = "ScheduleManager"
    }

    data class ScheduleItem(
        val fileName: String,
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int
    )

    private var items: List<ScheduleItem> = emptyList()
    private var hasSchedule = false

    /**
     * Парсит JSON-строку schedule.json.
     */
    fun parse(jsonStr: String): Boolean {
        return try {
            val json = JSONObject(jsonStr)
            val arr = json.getJSONArray("items")
            val parsed = mutableListOf<ScheduleItem>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val file = obj.getString("file")
                val start = parseTime(obj.getString("start"))
                val end = parseTime(obj.getString("end"))

                if (start != null && end != null) {
                    parsed.add(ScheduleItem(file, start.first, start.second, end.first, end.second))
                    Log.d(TAG, "  📅 $file: ${obj.getString("start")} — ${obj.getString("end")}")
                }
            }

            items = parsed
            hasSchedule = parsed.isNotEmpty()
            Log.i(TAG, "✅ Parsed ${parsed.size} schedule items")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse schedule: ${e.message}")
            items = emptyList()
            hasSchedule = false
            false
        }
    }

    /**
     * Возвращает имя файла для текущего времени.
     * Null если расписание пустое или ничего не подходит.
     */
    fun getCurrentFileName(): String? {
        if (!hasSchedule) return null

        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        for (item in items) {
            val startMin = item.startHour * 60 + item.startMinute
            val endMin = item.endHour * 60 + item.endMinute

            if (endMin > startMin) {
                // Обычный интервал: 06:00 — 14:00
                if (nowMinutes in startMin until endMin) {
                    return item.fileName
                }
            } else {
                // Ночной интервал: 23:00 — 06:00 (переходит через полночь)
                if (nowMinutes >= startMin || nowMinutes < endMin) {
                    return item.fileName
                }
            }
        }

        // Ничего не подошло — берём первый
        return items.firstOrNull()?.fileName
    }

    fun hasScheduleData(): Boolean = hasSchedule

    /**
     * Парсит строку "HH:MM" в пару (hour, minute).
     */
    private fun parseTime(time: String): Pair<Int, Int>? {
        return try {
            val parts = time.split(":")
            Pair(parts[0].trim().toInt(), parts[1].trim().toInt())
        } catch (e: Exception) {
            null
        }
    }
}
