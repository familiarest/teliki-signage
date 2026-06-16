package com.kavabanga.signage.ui.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kavabanga.signage.SignageApp
import com.kavabanga.signage.data.CacheManager
import com.kavabanga.signage.data.PrefsManager
import com.kavabanga.signage.data.ScheduleManager
import com.kavabanga.signage.data.YandexDiskClient
import com.kavabanga.signage.databinding.ActivityPlayerBinding
import com.kavabanga.signage.ui.setup.SetupActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Основной экран воспроизведения контента.
 *
 * Цикл работы:
 * 1. Каждые 5 минут запрашивает список файлов из Яндекс Диска
 * 2. Скачивает schedule.json (если есть)
 * 3. Определяет текущий файл по расписанию
 * 4. Скачивает и показывает (фото/видео)
 * 5. Если нет расписания — ротация всех файлов
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val YANDEX_TOKEN = "y0__wgBELGZqPQEGNDcQyCZu975FzDIisPzB5AJqrhJ_j16EutHF5ZemN-VaYjb"
        private const val SYNC_INTERVAL_MS = 5 * 60 * 1000L  // 5 минут
        private const val ROTATION_INTERVAL_MS = 15_000L      // 15 сек для фото в ротации
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var cacheManager: CacheManager
    private lateinit var diskClient: YandexDiskClient
    private lateinit var scheduleManager: ScheduleManager

    private val handler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var currentMediaPath = ""
    private var syncJob: Job? = null
    private var rotationJob: Job? = null

    // Список файлов для ротации (без расписания)
    private var allMediaFiles: List<YandexDiskClient.DiskItem> = emptyList()
    private var currentRotationIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Сообщаем системе что мы запустились (для crash loop detection)
        (application as? SignageApp)?.onPlayerStarted()

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager.getInstance(this)
        cacheManager = CacheManager(this)
        diskClient = YandexDiskClient(YANDEX_TOKEN)
        scheduleManager = ScheduleManager()

        setupFullscreen()
        setupBackHandler()
        acquireWakeLock()

        val diskPath = prefsManager.getDiskPath()
        if (diskPath.isBlank()) {
            Log.e(TAG, "No disk path configured — clearing old prefs")
            prefsManager.clear()
            goToSetup()
            return
        }

        Log.i(TAG, "🚀 Starting player for: $diskPath")

        // Показываем "Загрузка" пока грузим контент
        binding.loadingLayout.visibility = View.VISIBLE

        startContentLoop(diskPath)
    }

    /**
     * Главный цикл: синхронизация → показ → повтор.
     */
    private fun startContentLoop(diskPath: String) {
        syncJob = lifecycleScope.launch {
            while (true) {
                try {
                    syncAndDisplay(diskPath)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Sync error: ${e.message}", e)
                    // Пробуем показать из кэша
                    tryShowFromCache(diskPath)
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    /**
     * Синхронизация: загрузка списка файлов, расписания, показ контента.
     */
    private suspend fun syncAndDisplay(diskPath: String) {
        Log.d(TAG, "🔄 Syncing: $diskPath")

        // 1. Получаем список файлов из Яндекс Диска
        val items = diskClient.listFolder(diskPath)
        val mediaFiles = items.filter { it.type == "file" && it.name != "schedule.json" }
        val scheduleFile = items.find { it.name == "schedule.json" }

        Log.i(TAG, "📂 Files: ${mediaFiles.map { it.name }}")
        allMediaFiles = mediaFiles

        if (mediaFiles.isEmpty()) {
            Log.w(TAG, "No media files in folder!")
            withContext(Dispatchers.Main) { showNoContent() }
            return
        }

        // 2. Загружаем schedule.json (если есть)
        if (scheduleFile != null) {
            try {
                val scheduleJson = diskClient.downloadText(scheduleFile.path)
                scheduleManager.parse(scheduleJson)
                // Кэшируем расписание
                prefsManager.saveScheduleCache(
                    prefsManager.getLocationId() ?: "",
                    prefsManager.getSlotNumber(),
                    scheduleJson
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load schedule: ${e.message}")
                // Пробуем из кэша
                loadCachedSchedule()
            }
        } else {
            Log.d(TAG, "No schedule.json — rotation mode")
        }

        // 3. Определяем что показывать
        if (scheduleManager.hasScheduleData()) {
            showBySchedule(mediaFiles, diskPath)
        } else {
            startRotation(mediaFiles, diskPath)
        }

        // 4. Чистим старый кэш
        val activeKeys = mediaFiles.map { it.path }.toSet()
        cacheManager.clearOldCache(activeKeys)
    }

    /**
     * Показ по расписанию — находит текущий файл и показывает.
     * Запускает фоновый чекер каждые 30 сек для переключения по времени.
     */
    private suspend fun showBySchedule(
        files: List<YandexDiskClient.DiskItem>,
        diskPath: String
    ) {
        val targetName = scheduleManager.getCurrentFileName()
        if (targetName == null) {
            Log.w(TAG, "Schedule returned no file for current time")
            startRotation(files, diskPath)
            return
        }

        val targetFile = files.find { it.name == targetName }
        if (targetFile == null) {
            Log.w(TAG, "Schedule file not found: $targetName")
            startRotation(files, diskPath)
            return
        }

        Log.i(TAG, "📅 Schedule: showing $targetName (current=$currentMediaPath)")
        displayFile(targetFile)

        // Запускаем чекер ТОЛЬКО если его ещё нет
        if (rotationJob == null || rotationJob?.isActive != true) {
            rotationJob = lifecycleScope.launch {
                while (true) {
                    delay(30_000L) // Проверяем каждые 30 сек
                    val newTarget = scheduleManager.getCurrentFileName()
                    Log.d(TAG, "📅 Schedule check: current=$currentMediaPath, target=$newTarget")
                    if (newTarget != null && newTarget != currentMediaPath) {
                        val newFile = files.find { it.name == newTarget }
                        if (newFile != null) {
                            Log.i(TAG, "📅 Schedule switch: $currentMediaPath → $newTarget")
                            displayFile(newFile)
                        }
                    }
                }
            }
        }
    }

    /**
     * Ротация всех файлов (когда нет расписания).
     */
    private suspend fun startRotation(
        files: List<YandexDiskClient.DiskItem>,
        diskPath: String
    ) {
        if (files.isEmpty()) {
            withContext(Dispatchers.Main) { showNoContent() }
            return
        }

        rotationJob?.cancel()

        // Показываем первый файл
        if (currentRotationIndex >= files.size) currentRotationIndex = 0
        displayFile(files[currentRotationIndex])

        // Ротация
        rotationJob = lifecycleScope.launch {
            while (true) {
                val current = files[currentRotationIndex]
                val isVideo = isVideoFile(current.name)

                if (isVideo) {
                    // Для видео ждём окончания воспроизведения
                    delay(60_000L) // Максимум 60 сек для одного видео
                } else {
                    delay(ROTATION_INTERVAL_MS) // 15 сек для фото
                }

                currentRotationIndex = (currentRotationIndex + 1) % files.size
                displayFile(files[currentRotationIndex])
            }
        }
    }

    /**
     * Скачивает и показывает один файл.
     */
    private suspend fun displayFile(item: YandexDiskClient.DiskItem) {
        if (item.name == currentMediaPath) return // Уже показываем

        Log.d(TAG, "▶️ Displaying: ${item.name}")
        currentMediaPath = item.name

        try {
            // Проверяем кэш
            var file = cacheManager.getCachedFile(item.path)
            if (file == null) {
                // Скачиваем
                val downloadUrl = diskClient.getDownloadUrl(item.path)
                file = cacheManager.downloadAndCache(item.path, downloadUrl)
            }

            val mediaFile = file
            withContext(Dispatchers.Main) {
                if (isDestroyed || isFinishing) return@withContext
                if (isVideoFile(item.name)) {
                    showVideo(mediaFile)
                } else {
                    showImage(mediaFile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to display ${item.name}: ${e.message}", e)
        }
    }

    /**
     * Показать изображение.
     * Автоматически сжимает до размера экрана ТВ (1920×1080).
     * Файл 9 МБ (6000×4000) → загрузка как 1920×1280 → ~8 МБ RAM вместо 96 МБ.
     */
    private fun showImage(file: File) {
        Log.d(TAG, "🖼 Image: ${file.name} (${file.length() / 1024}KB)")
        hideLoading()
        releasePlayer()

        binding.playerView.visibility = View.GONE
        binding.imageView.visibility = View.VISIBLE
        binding.imageView.scaleType = ImageView.ScaleType.FIT_CENTER

        try {
            // 1. Читаем только размеры (без загрузки в память)
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
            val imgWidth = options.outWidth
            val imgHeight = options.outHeight

            if (imgWidth <= 0 || imgHeight <= 0) {
                Log.e(TAG, "Failed to read image dimensions: ${file.name}")
                file.delete()
                showNoContent()
                return
            }

            // 2. Рассчитываем масштаб под экран ТВ (1920×1080)
            val targetWidth = 1920
            val targetHeight = 1080
            var inSampleSize = 1
            if (imgWidth > targetWidth || imgHeight > targetHeight) {
                val halfW = imgWidth / 2
                val halfH = imgHeight / 2
                while (halfW / inSampleSize >= targetWidth && halfH / inSampleSize >= targetHeight) {
                    inSampleSize *= 2
                }
            }

            // 3. Загружаем с уменьшением
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)

            if (bitmap != null) {
                Log.d(TAG, "Image loaded: ${bitmap.width}×${bitmap.height} (sample=$inSampleSize, original=${imgWidth}×${imgHeight})")
                binding.imageView.setImageBitmap(bitmap)
                hideNoContent()
            } else {
                Log.e(TAG, "Failed to decode image: ${file.name}")
                file.delete()
                showNoContent()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image error: ${e.message}", e)
            file.delete()
            showNoContent()
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM loading image: ${file.name}", e)
            showNoContent()
        }
    }

    /**
     * Показать видео через ExoPlayer.
     */
    private fun showVideo(file: File) {
        Log.d(TAG, "🎬 Video: ${file.name} (${file.length() / 1024}KB)")
        hideLoading()

        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "Video file empty or missing")
            file.delete()
            showNoContent()
            return
        }

        try {
            binding.imageView.visibility = View.GONE
            hideNoContent()
            releasePlayer()

            binding.playerView.visibility = View.VISIBLE
            binding.playerView.alpha = 1f

            val videoFile = file
            var videoStarted = false

            val player = ExoPlayer.Builder(this).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                        videoFile.delete()
                        runOnUiThread {
                            releasePlayer()
                            showNoContent()
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        videoStarted = true
                        Log.i(TAG, "✅ Video playing: ${videoFile.name}")
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.d(TAG, "ExoPlayer: ${
                            when (playbackState) {
                                Player.STATE_IDLE -> "IDLE"
                                Player.STATE_BUFFERING -> "BUFFERING"
                                Player.STATE_READY -> "READY"
                                Player.STATE_ENDED -> "ENDED"
                                else -> "UNKNOWN"
                            }
                        }")
                    }
                })

                val mediaItem = MediaItem.fromUri(Uri.fromFile(videoFile))
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }

            exoPlayer = player
            binding.playerView.player = player

            // Таймаут 20 секунд
            handler.postDelayed({
                if (!videoStarted && exoPlayer == player && !isDestroyed) {
                    Log.e(TAG, "⏱ Video timeout — no frame in 20s")
                    videoFile.delete()
                    releasePlayer()
                    currentMediaPath = ""
                    showNoContent()
                }
            }, 20_000L)

        } catch (e: Exception) {
            Log.e(TAG, "Video crash: ${e.message}", e)
            file.delete()
            releasePlayer()
            showNoContent()
        }
    }

    // ───────── Кэш/оффлайн ─────────

    private fun tryShowFromCache(diskPath: String) {
        Log.w(TAG, "Trying cached content...")
        loadCachedSchedule()
        // Если есть кэшированные файлы — показываем первый найденный
        val cacheDir = cacheManager.cacheDirectory()
        val cached = cacheDir.listFiles()?.filter { it.length() > 0 } ?: emptyList()
        if (cached.isNotEmpty()) {
            val file = cached.first()
            runOnUiThread {
                if (isVideoFile(file.name)) showVideo(file)
                else showImage(file)
            }
        } else {
            runOnUiThread { showNoContent() }
        }
    }

    private fun loadCachedSchedule() {
        val json = prefsManager.getScheduleCache(
            prefsManager.getLocationId() ?: "",
            prefsManager.getSlotNumber()
        )
        if (json != null) {
            scheduleManager.parse(json)
        }
    }

    // ───────── UI Helpers ─────────

    private fun showNoContent() {
        binding.noContentLayout.visibility = View.VISIBLE
    }

    private fun hideNoContent() {
        binding.noContentLayout.visibility = View.GONE
    }

    private fun hideLoading() {
        binding.loadingLayout.visibility = View.GONE
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    // ───────── Fullscreen & WakeLock ─────────

    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val isTv = packageManager.hasSystemFeature("android.software.leanback")

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                if (isTv) {
                    // ТВ: скрываем всё (пульт для навигации)
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    // Телефон: только статусбар, навигация остаётся для жеста «Назад»
                    controller.hide(WindowInsets.Type.statusBars())
                }
            }
        }

        @Suppress("DEPRECATION")
        if (isTv) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        } else {
            // Телефон: только скрываем статусбар
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
                "Teliki::PlayerWakeLock"
            )
            wakeLock?.acquire()
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock failed: ${e.message}")
        }

        try {
            android.provider.Settings.System.putInt(
                contentResolver,
                android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                Int.MAX_VALUE
            )
        } catch (_: Exception) {}
    }

    // ───────── Navigation ─────────

    /**
     * Регистрируем обработчик «Назад» через OnBackPressedDispatcher.
     * Работает и на телефоне (жесты), и на ТВ (пульт).
     */
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Log.i(TAG, "🔙 Back (dispatcher) — going to setup")
                    goToSetup()
                }
            }
        )
    }

    /**
     * Запасной перехват для пультов ТВ (на случай если dispatcher не сработал).
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            event.action == android.view.KeyEvent.ACTION_UP) {
            Log.i(TAG, "🔙 Back (keyEvent) — going to setup")
            goToSetup()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun goToSetup() {
        releasePlayer()
        syncJob?.cancel()
        rotationJob?.cancel()
        startActivity(Intent(this, SetupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        syncJob?.cancel()
        rotationJob?.cancel()
        try { wakeLock?.release() } catch (_: Exception) {}
    }

    // ───────── Utils ─────────

    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".webm") ||
               lower.endsWith(".mov") || lower.endsWith(".mkv") ||
               lower.endsWith(".avi")
    }
}
