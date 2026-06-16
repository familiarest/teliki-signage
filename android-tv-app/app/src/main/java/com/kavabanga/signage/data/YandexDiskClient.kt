package com.kavabanga.signage.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Клиент для Яндекс Диска REST API.
 * Работает с OAuth-токеном. Без Firebase, без прокси.
 */
class YandexDiskClient(private val token: String) {

    companion object {
        private const val TAG = "YandexDisk"
        private const val API_BASE = "https://cloud-api.yandex.net/v1/disk"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 30_000
    }

    data class DiskItem(
        val name: String,
        val path: String,
        val type: String,        // "dir" или "file"
        val mimeType: String?,   // "image/jpeg", "video/mp4" и т.д.
        val size: Long,
        val downloadUrl: String? // preview URL, не для скачивания
    )

    /**
     * Получить список содержимого папки.
     * @param diskPath — путь в формате "disk:/Teliki/Ак-Мечеть/Экран 1"
     * @return список файлов и подпапок
     */
    suspend fun listFolder(diskPath: String): List<DiskItem> = withContext(Dispatchers.IO) {
        val encodedPath = URLEncoder.encode(diskPath, "UTF-8")
        val url = "$API_BASE/resources?path=$encodedPath&limit=100&sort=name"

        Log.d(TAG, "📂 Listing: $diskPath")
        val json = httpGet(url)
        val items = mutableListOf<DiskItem>()

        val embedded = json.optJSONObject("_embedded") ?: return@withContext items
        val arr = embedded.optJSONArray("items") ?: return@withContext items

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            items.add(
                DiskItem(
                    name = obj.getString("name"),
                    path = obj.getString("path"),
                    type = obj.getString("type"),
                    mimeType = obj.optString("mime_type", null),
                    size = obj.optLong("size", 0),
                    downloadUrl = obj.optString("file", null)
                )
            )
        }

        Log.d(TAG, "✅ Found ${items.size} items in $diskPath")
        items
    }

    /**
     * Получить прямую ссылку на скачивание файла.
     * @param diskPath — путь к файлу, например "disk:/Teliki/Ак-Мечеть/Экран 1/menu.jpg"
     * @return URL для скачивания (временный, от Яндекс CDN)
     */
    suspend fun getDownloadUrl(diskPath: String): String = withContext(Dispatchers.IO) {
        val encodedPath = URLEncoder.encode(diskPath, "UTF-8")
        val url = "$API_BASE/resources/download?path=$encodedPath"

        Log.d(TAG, "🔗 Getting download URL for: $diskPath")
        val json = httpGet(url)

        val href = json.getString("href")
        Log.d(TAG, "✅ Download URL: ${href.take(80)}...")
        href
    }

    /**
     * Скачать содержимое файла как строку (для schedule.json).
     */
    suspend fun downloadText(diskPath: String): String = withContext(Dispatchers.IO) {
        val downloadUrl = getDownloadUrl(diskPath)

        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.instanceFollowRedirects = true

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw Exception("HTTP $code downloading $diskPath")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * HTTP GET с OAuth-заголовком.
     */
    private fun httpGet(urlStr: String): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.setRequestProperty("Authorization", "OAuth $token")
        conn.setRequestProperty("Accept", "application/json")

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val error = try {
                    conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
                } catch (_: Exception) { "" }
                throw Exception("Yandex API error HTTP $code: $error")
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}
