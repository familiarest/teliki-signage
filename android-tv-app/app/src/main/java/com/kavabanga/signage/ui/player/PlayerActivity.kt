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
        private const val SCHEDULE_CHECK_INTERVAL_MS = 30_000L
        private const val SCREEN_POLL_INTERVAL_MS = 60_000L
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

    // Periodic schedule check — switches content based on time
    private val scheduleChecker = object : Runnable {
        override fun run() {
            checkAndSwitchContent()
            handler.postDelayed(this, SCHEDULE_CHECK_INTERVAL_MS)
        }
    }

    // Periodic screen data poll — fetches updates from server via REST
    private val screenPoller = object : Runnable {
        override fun run() {
            pollScreen()
            handler.postDelayed(this, SCREEN_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager.getInstance(this)
        cacheManager = CacheManager(this)
        repository = SignageRepository(this)

        setupFullscreen()
        acquireWakeLock()
        startPolling()
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

        Log.i(TAG, "Starting REST polling: location=$locationId, slot=$slotNumber")
        pollScreen()
        handler.postDelayed(screenPoller, SCREEN_POLL_INTERVAL_MS)
    }

    private fun pollScreen() {
        val locationId = prefsManager.getLocationId() ?: return
        val slotNumber = prefsManager.getSlotNumber()

        repository.fetchScreen(locationId, slotNumber) { screen ->
            if (screen == null || screen.schedule.isEmpty()) {
                Log.w(TAG, "No screen data or empty schedule")
                if (currentSchedule.isEmpty()) showNoContent()
                return@fetchScreen
            }
            onScheduleReceived(screen)
        }
    }

    private fun onScheduleReceived(screen: Screen) {
        Log.d(TAG, "Received schedule with ${screen.schedule.size} items")
        currentSchedule = screen.schedule

        // Предкачиваем ВСЕ медиа в фоне для оффлайн-режима
        val activeUrls = screen.schedule.map { it.mediaUrl }.toSet()
        lifecycleScope.launch(Dispatchers.IO) {
            cacheManager.clearOldCache(activeUrls)
            // Скачиваем все файлы заранее
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
        }

        // Display current content immediately
        checkAndSwitchContent()

        // Start schedule checker for time-based switching
        handler.removeCallbacks(scheduleChecker)
        handler.postDelayed(scheduleChecker, SCHEDULE_CHECK_INTERVAL_MS)
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
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying media: ${e.message}", e)
                hideLoading()
                tryShowCached(item)
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
        Log.d(TAG, "Showing video: ${file.name}")
        crossfadeOut(binding.imageView)
        hideNoContent()
        releasePlayer()

        val player = ExoPlayer.Builder(this).build().apply {
            repeatMode = Player.REPEAT_MODE_ALL
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }

        exoPlayer = player
        binding.playerView.player = player
        crossfadeIn(binding.playerView)
    }

    private fun showImage(file: File) {
        Log.d(TAG, "Showing image: ${file.name} (${file.length() / 1024}KB)")
        crossfadeOut(binding.playerView)
        hideNoContent()
        releasePlayer()

        Glide.with(applicationContext)
            .load(file)
            .override(Target.SIZE_ORIGINAL)  // Full quality, no downsampling
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
        handler.removeCallbacks(screenPoller)
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
