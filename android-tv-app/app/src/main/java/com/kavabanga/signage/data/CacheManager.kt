package com.kavabanga.signage.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class CacheManager(private val context: Context) {

    companion object {
        private const val TAG = "CacheManager"
        private const val CACHE_DIR_NAME = "media_cache"

        // Глобальные блокировки на URL — предотвращают двойное скачивание
        private val downloadLocks = ConcurrentHashMap<String, Any>()
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR_NAME).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    /**
     * Returns the cached file for the given URL, or null if not cached.
     */
    fun getCachedFile(url: String): File? {
        if (url.isBlank()) return null
        val file = File(cacheDir, hashUrl(url))
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Downloads the media from URL and caches it to internal storage.
     * Thread-safe: только один поток может скачивать один и тот же URL.
     */
    suspend fun downloadAndCache(url: String): File = withContext(Dispatchers.IO) {
        // Быстрая проверка кэша (без блокировки)
        val cachedFile = getCachedFile(url)
        if (cachedFile != null) {
            Log.d(TAG, "Cache hit for: $url")
            return@withContext cachedFile
        }

        // Получаем или создаём lock-объект для этого URL
        val lock = downloadLocks.getOrPut(hashUrl(url)) { Any() }

        // Synchronized: второй поток с тем же URL будет ждать
        synchronized(lock) {
            // Повторная проверка — может другой поток уже скачал
            val rechecked = getCachedFile(url)
            if (rechecked != null) {
                Log.d(TAG, "Cache hit (after lock) for: $url")
                return@withContext rechecked
            }

            Log.d(TAG, "Downloading: $url")
            val hash = hashUrl(url)
            val targetFile = File(cacheDir, hash)
            val tempFile = File(cacheDir, "${hash}.tmp")

            // Удаляем битый tmp если остался от предыдущего крэша
            if (tempFile.exists()) {
                tempFile.delete()
            }

            val downloadUrl = proxyUrl(url)
            if (downloadUrl != url) {
                Log.d(TAG, "Proxied via Cloud Function")
            }

            try {
                val connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000  // 5 минут для больших видео
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "KavabangaSignage/1.0 Android")

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    connection.disconnect()
                    throw Exception("HTTP $responseCode for URL: $url")
                }

                connection.inputStream.use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        inputStream.copyTo(outputStream, bufferSize = 65536)
                    }
                }

                connection.disconnect()

                // Atomic rename from temp to final
                if (tempFile.exists() && tempFile.length() > 0) {
                    val renamed = tempFile.renameTo(targetFile)
                    if (!renamed) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                    Log.d(TAG, "Cached successfully: ${targetFile.name} (${targetFile.length()} bytes)")
                } else {
                    throw Exception("Downloaded file is empty or missing")
                }

                return@withContext targetFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download: $url", e)
                tempFile.delete()
                throw e
            } finally {
                // Убираем lock после завершения
                downloadLocks.remove(hash)
            }
        }
    }

    /**
     * Removes cached files that are NOT in the provided set of active URLs.
     */
    fun clearOldCache(activeUrls: Set<String> = emptySet()) {
        val activeHashes = activeUrls.map { hashUrl(it) }.toSet()
        val files = cacheDir.listFiles() ?: return

        var deletedCount = 0
        for (file in files) {
            if (file.name.endsWith(".tmp")) {
                file.delete()
                deletedCount++
                continue
            }
            if (file.name !in activeHashes) {
                file.delete()
                deletedCount++
            }
        }

        if (deletedCount > 0) {
            Log.d(TAG, "Cleared $deletedCount old cache files")
        }

        enforceCacheLimit()
    }

    private fun enforceCacheLimit() {
        val maxCacheBytes = 500L * 1024 * 1024 // 500 MB
        val files = cacheDir.listFiles()?.filter { !it.name.endsWith(".tmp") } ?: return
        val totalSize = files.sumOf { it.length() }

        if (totalSize <= maxCacheBytes) return

        Log.w(TAG, "Cache size ${totalSize / 1024 / 1024}MB exceeds limit, evicting oldest files")
        val sorted = files.sortedBy { it.lastModified() }
        var freed = 0L
        for (file in sorted) {
            if (totalSize - freed <= maxCacheBytes) break
            freed += file.length()
            file.delete()
            Log.d(TAG, "Evicted cache file: ${file.name}")
        }
    }

    /**
     * Deletes ALL cached files.
     */
    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "All cache cleared")
    }

    private fun hashUrl(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(url.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun proxyUrl(url: String): String {
        if (url.contains("firebasestorage.googleapis.com") ||
            url.contains("storage.googleapis.com")) {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            return "https://teliki-signage.vercel.app/api?media=$encoded"
        }
        return url
    }
}
