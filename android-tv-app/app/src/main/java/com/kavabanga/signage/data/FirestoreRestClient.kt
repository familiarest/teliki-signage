package com.kavabanga.signage.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Firestore REST API client.
 * Автоматически перебирает доступные домены, обходя гео-блокировки.
 * Порядок: web.app -> firebaseapp.com -> cloudfunctions.net
 */
class FirestoreRestClient {

    companion object {
        private const val TAG = "FirestoreRest"

        // Домены для перебора (от наименее блокируемого к наиболее)
        private val API_URLS = listOf(
            "https://teliki-signage.vercel.app/api",
            "https://kava-signage-2026.web.app/api",
            "https://kava-signage-2026.firebaseapp.com/api",
            "https://us-central1-kava-signage-2026.cloudfunctions.net/api"
        )

        // Найденный рабочий URL (кэшируется после первого успеха)
        @Volatile
        var workingUrl: String? = null
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Слушатель для вывода логов на экран
    var logListener: ((String) -> Unit)? = null

    private fun log(msg: String) {
        Log.d(TAG, msg)
        mainHandler.post { logListener?.invoke(msg) }
    }

    fun getCollection(
        path: String,
        onSuccess: (List<Map<String, Any?>>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        executor.execute {
            // Если уже нашли рабочий URL — используем его
            val urlsToTry = if (workingUrl != null) {
                listOf(workingUrl!!)
            } else {
                API_URLS
            }

            var lastError: Exception? = null

            for (baseUrl in urlsToTry) {
                try {
                    val fullUrl = "$baseUrl?path=$path"
                    log("📡 $baseUrl")

                    val conn = URL(fullUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("Accept", "application/json")

                    val code = conn.responseCode
                    if (code == 403) {
                        val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(100) ?: "" } catch (_: Exception) { "" }
                        conn.disconnect()
                        log("🚫 $baseUrl → 403 BLOCKED")
                        lastError = Exception("403 Forbidden: $baseUrl")
                        continue // Пробуем следующий домен
                    }

                    if (code !in 200..299) {
                        val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: "" } catch (_: Exception) { "" }
                        conn.disconnect()
                        log("❌ $baseUrl → HTTP $code")
                        lastError = Exception("HTTP $code: $err")
                        continue
                    }

                    val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    conn.disconnect()

                    val json = JSONObject(body)
                    val docs = mutableListOf<Map<String, Any?>>()

                    if (json.has("documents")) {
                        val arr = json.getJSONArray("documents")
                        for (i in 0 until arr.length()) {
                            docs.add(parseDoc(arr.getJSONObject(i)))
                        }
                    }

                    // Успех! Запоминаем рабочий URL
                    workingUrl = baseUrl
                    log("✅ $baseUrl → ${docs.size} docs")
                    Log.i(TAG, "Working URL: $baseUrl, got ${docs.size} docs from '$path'")
                    mainHandler.post { onSuccess(docs) }
                    return@execute

                } catch (e: Exception) {
                    log("❌ $baseUrl → ${e.message?.take(60)}")
                    lastError = e
                    continue
                }
            }

            // Все URL провалились
            val finalError = lastError ?: Exception("All API URLs blocked")
            log("💀 Все домены заблокированы!")
            Log.e(TAG, "All URLs failed for '$path'", finalError)
            mainHandler.post { onError(finalError) }
        }
    }

    fun getSubcollection(
        collection: String, docId: String, sub: String,
        onSuccess: (List<Map<String, Any?>>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        getCollection("$collection/$docId/$sub", onSuccess, onError)
    }

    private fun parseDoc(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val name = obj.optString("name", "")
        map["__id__"] = name.substringAfterLast("/")

        val fields = obj.optJSONObject("fields") ?: return map
        for (key in fields.keys()) {
            map[key] = parseValue(fields.getJSONObject(key))
        }
        return map
    }

    private fun parseValue(v: JSONObject): Any? {
        return when {
            v.has("stringValue")    -> v.getString("stringValue")
            v.has("integerValue")   -> v.getString("integerValue").toLongOrNull() ?: 0L
            v.has("doubleValue")    -> v.getDouble("doubleValue")
            v.has("booleanValue")   -> v.getBoolean("booleanValue")
            v.has("timestampValue") -> v.getString("timestampValue")
            v.has("nullValue")      -> null
            v.has("arrayValue")     -> {
                val vals = v.getJSONObject("arrayValue").optJSONArray("values")
                if (vals == null) emptyList()
                else (0 until vals.length()).map { parseValue(vals.getJSONObject(it)) }
            }
            v.has("mapValue") -> {
                val f = v.getJSONObject("mapValue").optJSONObject("fields")
                if (f == null) {
                    emptyMap<String, Any?>()
                } else {
                    val m = mutableMapOf<String, Any?>()
                    for (k in f.keys()) m[k] = parseValue(f.getJSONObject(k))
                    m
                }
            }
            else -> null
        }
    }
}
