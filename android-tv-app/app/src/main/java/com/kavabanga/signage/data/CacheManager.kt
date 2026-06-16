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

/**
 * Simple media cache manager.
 *
 * - Files are keyed by their Yandex Disk path (e.g. "/signage/videos/intro.mp4").
 * - The on-disk filename is the SHA-256 hex of the key, preserving the original extension.
 * - Downloads are performed from a direct download URL provided by the caller
 *   (typically the `href` from Yandex Disk's `/v1/disk/resources/download` endpoint).
 * - Thread-safe: concurrent calls for the same key are serialised via per-key locks.
 * - 500 MB LRU eviction based on last-modified time.
 */
class CacheManager(private val context: Context) {

    companion object {
        private const val TAG = "CacheManager"
        private const val CACHE_DIR_NAME = "media_cache"
        private const val MAX_CACHE_BYTES = 500L * 1024 * 1024 // 500 MB
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000 // 5 min for large videos
        private const val BUFFER_SIZE = 65_536

        /** Per-key locks to prevent duplicate downloads. */
        private val downloadLocks = ConcurrentHashMap<String, Any>()
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, CACHE_DIR_NAME).also { it.mkdirs() }
    }

    // ── public API ───────────────────────────────────────────────────────

    /** Returns the cache directory for offline browsing. */
    fun cacheDirectory(): File = cacheDir


    /**
     * Returns the cached [File] for [key], or `null` if nothing is cached.
     */
    fun getCachedFile(key: String): File? {
        if (key.isBlank()) return null
        val file = File(cacheDir, hashedFileName(key))
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Downloads the file from [downloadUrl] and caches it under [key].
     *
     * If the file is already cached the download is skipped and the cached
     * file is returned immediately.
     *
     * @param key         Logical cache key (Yandex Disk path).
     * @param downloadUrl Direct download URL (e.g. from Yandex Disk API).
     * @return The cached [File].
     * @throws Exception on network / IO / integrity errors.
     */
    suspend fun downloadAndCache(key: String, downloadUrl: String): File =
        withContext(Dispatchers.IO) {
            // Fast path — already cached.
            getCachedFile(key)?.let {
                Log.d(TAG, "Cache hit: $key")
                return@withContext it
            }

            val fileName = hashedFileName(key)
            val lock = downloadLocks.getOrPut(fileName) { Any() }

            synchronized(lock) {
                // Double-check after acquiring the lock.
                getCachedFile(key)?.let {
                    Log.d(TAG, "Cache hit (after lock): $key")
                    return@withContext it
                }

                val targetFile = File(cacheDir, fileName)
                val tempFile = File(cacheDir, "$fileName.tmp")

                try {
                    // Clean up stale temp file from a previous crash.
                    tempFile.delete()

                    Log.d(TAG, "Downloading: $key -> ${downloadUrl.take(120)}")

                    val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "KavabangaSignage/1.0 Android")
                    }

                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        connection.disconnect()
                        throw Exception("HTTP $responseCode for $key")
                    }

                    val expectedLength = connection.contentLength.toLong()

                    connection.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output, bufferSize = BUFFER_SIZE)
                        }
                    }
                    connection.disconnect()

                    // Content-Length verification.
                    if (expectedLength > 0 && tempFile.length() != expectedLength) {
                        throw Exception(
                            "Incomplete download for $key: " +
                                "expected $expectedLength bytes, got ${tempFile.length()}"
                        )
                    }

                    // Atomic rename (fallback to copy + delete).
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }

                    // Touch the file so LRU ordering is based on access time.
                    targetFile.setLastModified(System.currentTimeMillis())

                    Log.d(TAG, "Cached: ${targetFile.name} (${targetFile.length()} bytes)")
                    enforceCacheLimit()

                    targetFile
                } catch (e: Exception) {
                    tempFile.delete()
                    downloadLocks.remove(fileName)
                    Log.e(TAG, "Download failed for $key: ${e.message}")
                    throw e
                }
            }
        }

    /**
     * Removes cached files whose keys are **not** in [activeKeys].
     * Also cleans up leftover `.tmp` files.
     */
    fun clearOldCache(activeKeys: Set<String>) {
        val activeFileNames = activeKeys.map { hashedFileName(it) }.toSet()
        val files = cacheDir.listFiles() ?: return

        var deletedCount = 0
        for (file in files) {
            if (file.name.endsWith(".tmp") || file.name !in activeFileNames) {
                file.delete()
                deletedCount++
            }
        }
        if (deletedCount > 0) {
            Log.d(TAG, "Cleared $deletedCount old cache files")
        }
        enforceCacheLimit()
    }

    /**
     * Deletes **all** cached files.
     */
    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
        downloadLocks.clear()
        Log.d(TAG, "All cache cleared")
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * Returns the on-disk filename for [key]: SHA-256 hex + original extension.
     */
    private fun hashedFileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hex = digest.digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        // Preserve the file extension so Android MediaPlayer / ExoPlayer can
        // infer the MIME type from the filename when needed.
        val ext = key.substringAfterLast('.', "").takeIf { it.length in 1..5 }
        return if (ext != null) "$hex.$ext" else hex
    }

    /**
     * Evicts the oldest files (by last-modified) until total cache size
     * is at or below [MAX_CACHE_BYTES].
     */
    private fun enforceCacheLimit() {
        val files = cacheDir.listFiles()
            ?.filter { !it.name.endsWith(".tmp") }
            ?: return

        val totalSize = files.sumOf { it.length() }
        if (totalSize <= MAX_CACHE_BYTES) return

        Log.w(TAG, "Cache ${totalSize / 1024 / 1024}MB exceeds limit, evicting oldest files")

        val sorted = files.sortedBy { it.lastModified() }
        var freed = 0L
        for (file in sorted) {
            if (totalSize - freed <= MAX_CACHE_BYTES) break
            freed += file.length()
            file.delete()
            Log.d(TAG, "Evicted: ${file.name}")
        }
    }
}
