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

class CacheManager(private val context: Context) {

    companion object {
        private const val TAG = "CacheManager"
        private const val CACHE_DIR_NAME = "media_cache"
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
     * Returns the cached File.
     */
    suspend fun downloadAndCache(url: String): File = withContext(Dispatchers.IO) {
        val cachedFile = getCachedFile(url)
        if (cachedFile != null) {
            Log.d(TAG, "Cache hit for: $url")
            return@withContext cachedFile
        }

        Log.d(TAG, "Downloading: $url")
        val targetFile = File(cacheDir, hashUrl(url))
        val tempFile = File(cacheDir, "${hashUrl(url)}.tmp")

        // Route through Cloud Function proxy if URL is on blocked googleapis.com
        val downloadUrl = proxyUrl(url)
        if (downloadUrl != url) {
            Log.d(TAG, "Proxied via Cloud Function")
        }

        try {
            val connection = URL(downloadUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
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

            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download: $url", e)
            tempFile.delete()
            throw e
        }
    }

    /**
     * Removes cached files that are NOT in the provided set of active URLs.
     * Then enforces a max cache size, evicting oldest files first.
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

        // Enforce max cache size: evict oldest files first
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
     * SHA-256 hash of URL used as the cache filename.
     */
    private fun hashUrl(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(url.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun proxyUrl(url: String): String {
        // Firebase Storage is geo-blocked in certain regions (403 "not available in your location")
        // Route through Cloud Function proxy which runs in us-central1 (no geo-block)
        if (url.contains("firebasestorage.googleapis.com")) {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            return "https://kava-signage-2026.web.app/api?media=$encoded"
        }
        return url
    }
}
