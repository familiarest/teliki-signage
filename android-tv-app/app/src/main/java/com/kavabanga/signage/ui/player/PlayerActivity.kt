package com.kavabanga.signage.ui.player

import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.Target
import com.kavabanga.signage.R
import com.kavabanga.signage.data.CacheManager
import com.kavabanga.signage.data.PrefsManager
import com.kavabanga.signage.data.SignageRepository
import com.kavabanga.signage.databinding.ActivityPlayerBinding
import com.kavabanga.signage.SignageApp
import com.kavabanga.signage.model.ScheduleItem
import com.kavabanga.signage.model.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val SCHEDULE_CHECK_INTERVAL_MS = 30_000L  // Переключение контента по расписанию
        private const val UPDATE_CHECK_INTERVAL_MS = 600_000L  // Проверка обновлений каждые 10 минут
        private const val CROSSFADE_DURATION_MS = 500L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var cacheManager: CacheManager

    private lateinit var repository: SignageRepository
    private var exoPlayer: ExoPlayer? = null
    private var currentMediaUrl: String = ""
    private var currentSchedule: List<ScheduleItem> = emptyList()

    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private var mediaCheckJob: Job? = null

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // Проверка переключения контента по расписанию (каждые 30 сек)
    private val scheduleChecker = object : Runnable {
        override fun run() {
            checkAndSwitchContent()
            handler.postDelayed(this, SCHEDULE_CHECK_INTERVAL_MS)
        }
    }

    // Периодическая проверка обновлений контента (каждые 10 минут)
    private val updateChecker = object : Runnable {
        override fun run() {
            checkIfSyncNeeded()
            handler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // null — не восстанавливаем сломанное состояние после крэша
        super.onCreate(null)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager.getInstance(this)
        cacheManager = CacheManager(this)
        repository = SignageRepository(this)

        // Если был крэш — чистим ВСЁ (расписание + медиа-кэш с битыми файлами)
        val crashFile = java.io.File(filesDir, SignageApp.CRASH_FILE)
        if (crashFile.exists()) {
            Log.w(TAG, "Обнаружен предыдущий крэш — очищаем все кэши")
            val locationId = prefsManager.getLocationId()
            val slot = prefsManager.getSlotNumber()
            if (locationId != null) {
                prefsManager.saveScheduleCache(locationId, slot, "")
            }
            cacheManager.clearAll() // Удаляем битые медиа-файлы
        }

        setupFullscreen()
        acquireWakeLock()

        try {
            startPolling()
        } catch (e: Throwable) {
            Log.e(TAG, "Ошибка при запуске", e)
            showNoContent()
        }
    }

    /**
     * WakeLock prevents the TV from going to sleep.
     * FLAG_KEEP_SCREEN_ON alone is sometimes ignored by Android TV firmware.
     */
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Teliki::PlayerWakeLock"
            )
            wakeLock?.acquire()
            Log.i(TAG, "WakeLock acquired — screen will stay on")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    /**
     * Handle hardware back button (TV remote or phone) — go back to setup menu.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        goToSetup()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            goToSetup()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun goToSetup() {
        prefsManager.clear()
        val intent = Intent(this, com.kavabanga.signage.ui.setup.SetupActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }


    private fun startPolling() {
        val locationId = prefsManager.getLocationId()
        val slotNumber = prefsManager.getSlotNumber()

        if (locationId == null) {
            Log.e(TAG, "No location configured!")
            showNoContent()
            return
        }

        Log.i(TAG, "Start: location=$locationId, slot=$slotNumber")

        // 1. Сразу показываем кэшированный контент
        loadCachedSchedule(locationId, slotNumber)

        // 2. Проверяем нужна ли синхронизация
        checkIfSyncNeeded()

        // 3. Запускаем периодическую проверку обновлений
        handler.postDelayed(updateChecker, UPDATE_CHECK_INTERVAL_MS)
    }

    /**
     * Загружает расписание из кэша и сразу показывает контент.
     */
    private fun loadCachedSchedule(locationId: String, slotNumber: Int) {
        val cachedJson = prefsManager.getScheduleCache(locationId, slotNumber)
        if (cachedJson != null) {
            try {
                val screen = SignageRepository.parseScheduleJson(cachedJson)
                if (screen != null && screen.schedule.isNotEmpty()) {
                    Log.i(TAG, "Загружен кэш: ${screen.schedule.size} элементов")
                    currentSchedule = screen.schedule
                    checkAndSwitchContent()
                    handler.removeCallbacks(scheduleChecker)
                    handler.postDelayed(scheduleChecker, SCHEDULE_CHECK_INTERVAL_MS)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка чтения кэша", e)
            }
        }
        // Нет кэша — грузим с сервера сейчас
        Log.i(TAG, "Кэш пуст — синхронизация с сервером...")
        syncFromServer()
    }

    /**
     * Проверка обновлений — вызывается каждые 10 минут.
     * Запрос расписания лёгкий (~1 КБ), медиа скачивается только при изменении.
     */
    private fun checkIfSyncNeeded() {
        Log.i(TAG, "⏰ Проверка обновлений...")
        syncFromServer()
    }

    /**
     * Загружает данные с сервера и обновляет кэш.
     */
    private fun syncFromServer() {
        val locationId = prefsManager.getLocationId() ?: return
        val slotNumber = prefsManager.getSlotNumber()

        repository.fetchScreen(locationId, slotNumber) { screen ->
            // Защита: activity может быть уже уничтожена
            if (isDestroyed || isFinishing) return@fetchScreen

            try {
                if (screen == null || screen.schedule.isEmpty()) {
                    Log.w(TAG, "Сервер вернул пустые данные")
                    if (currentSchedule.isEmpty()) showNoContent()
                    return@fetchScreen
                }

                // Проверяем изменилось ли расписание
                val newUrls = screen.schedule.map { it.mediaUrl }.toSet()
                val oldUrls = currentSchedule.map { it.mediaUrl }.toSet()

                if (newUrls == oldUrls && currentSchedule.isNotEmpty()) {
                    Log.i(TAG, "✅ Контент актуален, обновление не требуется")
                    return@fetchScreen
                }

                Log.i(TAG, "🔄 Обнаружен новый контент! Обновляем...")
                prefsManager.saveLastSyncTime(System.currentTimeMillis())
                onScheduleReceived(screen)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка в sync callback", e)
            }
        }
    }

    private fun onScheduleReceived(screen: Screen) {
        if (isDestroyed || isFinishing) return
        Log.d(TAG, "Received schedule with ${screen.schedule.size} items")

        try {
            // 1. Останавливаем текущее воспроизведение
            releasePlayer()
            currentMediaUrl = ""
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка при остановке плеера", e)
        }

        currentSchedule = screen.schedule

        // 1. СНАЧАЛА показываем контент
        if (!isDestroyed && !isFinishing) {
            checkAndSwitchContent()
            handler.removeCallbacks(scheduleChecker)
            handler.postDelayed(scheduleChecker, SCHEDULE_CHECK_INTERVAL_MS)
        }

        // 2. ПОТОМ предкэшируем остальные файлы (через 5 сек — чтобы текущий точно скачался)
        val activeUrls = screen.schedule.map { it.mediaUrl }.toSet()
        if (!isDestroyed) {
            lifecycleScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(5000) // Ждём 5 сек чтобы текущий файл точно скачался
                try {
                    cacheManager.clearOldCache(activeUrls)
                    for (item in screen.schedule) {
                        if (item.mediaUrl.isNotBlank()) {
                            try {
                                cacheManager.downloadAndCache(item.mediaUrl)
                            } catch (e: Exception) {
                                Log.w(TAG, "Предкачирование не удалось: ${item.fileName}", e)
                            }
                        }
                    }
                    Log.i(TAG, "Предкачирование завершено: ${activeUrls.size} файлов")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка предкачирования", e)
                }
            }
        }
    }

    private fun determineCurrentItem(): ScheduleItem? {
        if (currentSchedule.isEmpty()) return null

        val now = LocalTime.now()

        // First: try to find an active scheduled item (one whose end_time hasn't passed)
        for (item in currentSchedule) {
            if (item.hasSchedule && item.endTime != null) {
                try {
                    val end = LocalTime.parse(item.endTime, timeFormatter)
                    if (now.isBefore(end)) {
                        return item
                    }
                    // This item's time has passed, skip to next
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse endTime: ${item.endTime}", e)
                }
            }
        }

        // Second: find the first non-scheduled (always-on) item
        val alwaysOnItem = currentSchedule.firstOrNull { !it.hasSchedule }
        if (alwaysOnItem != null) return alwaysOnItem

        // Last resort: show the last item regardless
        return currentSchedule.lastOrNull()
    }

    private fun checkAndSwitchContent() {
        // Не прерываем активную загрузку
        if (mediaCheckJob?.isActive == true) return

        val item = determineCurrentItem()

        if (item == null) {
            showNoContent()
            return
        }

        // Only skip if we successfully loaded and are currently showing this exact media.
        if (item.mediaUrl == currentMediaUrl && item.mediaUrl.isNotBlank()) {
            return
        }

        Log.i(TAG, "Switching to: ${item.fileName} (${item.mediaType})")

        mediaCheckJob?.cancel()
        mediaCheckJob = lifecycleScope.launch {
            try {
                showLoading(item.fileName)
                displayMedia(item)
                hideLoading()
                currentMediaUrl = item.mediaUrl
            } catch (e: Throwable) {
                Log.e(TAG, "Error displaying media: ${e.message}", e)
                hideLoading()
                currentMediaUrl = item.mediaUrl
                try {
                    tryShowCached(item)
                } catch (_: Throwable) {
                    showNoContent()
                }
            }
        }
    }

    private fun showLoading(fileName: String) {
        runOnUiThread {
            binding.loadingLayout.visibility = View.VISIBLE
            binding.loadingText.text = "Загрузка: $fileName"
        }
    }

    private fun hideLoading() {
        runOnUiThread {
            binding.loadingLayout.visibility = View.GONE
        }
    }

    private suspend fun displayMedia(item: ScheduleItem) {
        if (item.mediaUrl.isBlank()) {
            showNoContent()
            return
        }

        val file: File = try {
            cacheManager.downloadAndCache(item.mediaUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            cacheManager.getCachedFile(item.mediaUrl)
                ?: throw Exception("Download failed: ${e.message}")
        }

        withContext(Dispatchers.Main) {
            if (isDestroyed || isFinishing) return@withContext
            when (item.mediaType) {
                "video" -> showVideo(file)
                "image" -> showImage(file)
                else -> {
                    val url = item.mediaUrl.lowercase()
                    if (url.contains(".mp4") || url.contains(".webm") || url.contains(".mkv")) {
                        showVideo(file)
                    } else {
                        showImage(file)
                    }
                }
            }
        }
    }

    private fun showVideo(file: File) {
        Log.d(TAG, "Showing video: ${file.name} (${file.length() / 1024}KB)")

        // Проверяем что файл существует и не пустой
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "Видео файл пустой или не существует: ${file.absolutePath}")
            showNoContent()
            return
        }

        try {
            crossfadeOut(binding.imageView)
            hideNoContent()
            releasePlayer()

            val player = ExoPlayer.Builder(this).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL

                // Обработчик ошибок — не даём приложению упасть
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "ExoPlayer ошибка: ${error.message}", error)
                        runOnUiThread {
                            releasePlayer()
                            showNoContent()
                        }
                    }
                })

                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }

            exoPlayer = player
            binding.playerView.player = player
            crossfadeIn(binding.playerView)
        } catch (e: Exception) {
            Log.e(TAG, "Крэш при запуске видео: ${e.message}", e)
            releasePlayer()
            showNoContent()
        }
    }

    private fun showImage(file: File) {
        Log.d(TAG, "Showing image: ${file.name} (${file.length() / 1024}KB)")
        crossfadeOut(binding.playerView)
        hideNoContent()
        releasePlayer()

        // Получаем размер экрана — нет смысла декодировать фото больше экрана
        val display = windowManager.defaultDisplay
        val size = android.graphics.Point()
        display.getRealSize(size)
        val screenW = maxOf(size.x, 1920)
        val screenH = maxOf(size.y, 1080)

        Glide.with(applicationContext)
            .load(file)
            .override(screenW, screenH)  // Масштабируем до размера экрана (не 256MB!)
            .centerInside()
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(false)
            .error(R.color.black)
            .into(binding.imageView)

        crossfadeIn(binding.imageView)
    }

    private fun showNoContent() {
        crossfadeOut(binding.playerView)
        crossfadeOut(binding.imageView)
        releasePlayer()
        binding.noContentLayout.visibility = View.VISIBLE
    }

    private fun hideNoContent() {
        binding.noContentLayout.visibility = View.GONE
    }

    private fun tryShowCached(item: ScheduleItem) {
        val cached = cacheManager.getCachedFile(item.mediaUrl)
        if (cached != null) {
            lifecycleScope.launch(Dispatchers.Main) {
                if (isDestroyed || isFinishing) return@launch
                when (item.mediaType) {
                    "video" -> showVideo(cached)
                    else -> showImage(cached)
                }
            }
        } else {
            showNoContent()
        }
    }

    private fun crossfadeIn(view: View) {
        view.animate().cancel()
        if (view.visibility == View.VISIBLE && view.alpha == 1f) return
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(CROSSFADE_DURATION_MS).withEndAction(null).start()
    }

    private fun crossfadeOut(view: View) {
        view.animate().cancel()
        if (view.visibility == View.GONE) return
        view.animate().alpha(0f).setDuration(CROSSFADE_DURATION_MS)
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    private fun releasePlayer() {
        exoPlayer?.let { player ->
            player.stop()
            player.release()
        }
        exoPlayer = null
        binding.playerView.player = null
    }

    override fun onResume() {
        super.onResume()
        setupFullscreen()
        exoPlayer?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(scheduleChecker)
        handler.removeCallbacks(updateChecker)
        mediaCheckJob?.cancel()
        releasePlayer()
        // Release WakeLock
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupFullscreen()
        }
    }

    /**
     * Release Glide memory when system is running low.
     * Prevents OOM crashes on low-RAM TV devices.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            Glide.get(this).clearMemory()
            Log.w(TAG, "Trimmed Glide memory (level=$level)")
        }
    }
}
