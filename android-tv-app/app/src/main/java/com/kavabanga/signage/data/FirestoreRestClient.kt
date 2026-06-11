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
 * Reads directly from firestore.googleapis.com (no auth needed for public collections).
 * Media downloads go through Cloud Function proxy to bypass geo-restrictions.
 */
class FirestoreRestClient {

    companion object {
        private const val TAG = "FirestoreRest"
        // Direct Cloud Function URL — always works
        private const val PROXY_URL = "https://us-central1-kava-signage-2026.cloudfunctions.net/api"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getCollection(
        path: String,
        onSuccess: (List<Map<String, Any?>>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        executor.execute {
            try {
                val url = "$PROXY_URL?path=$path"
                Log.d(TAG, "GET $url")

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 20_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Accept", "application/json")

                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = try { conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: "" } catch (_: Exception) { "" }
                    conn.disconnect()
                    throw Exception("HTTP $code: $err")
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

                Log.i(TAG, "Got ${docs.size} docs from '$path'")
                mainHandler.post { onSuccess(docs) }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching '$path': ${e.javaClass.simpleName}: ${e.message}", e)
                mainHandler.post { onError(e) }
            }
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
