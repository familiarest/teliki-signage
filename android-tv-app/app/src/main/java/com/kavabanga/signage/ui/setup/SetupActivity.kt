package com.kavabanga.signage.ui.setup

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.kavabanga.signage.R
import com.kavabanga.signage.data.CacheManager
import com.kavabanga.signage.data.PrefsManager
import com.kavabanga.signage.data.YandexDiskClient
import com.kavabanga.signage.databinding.ActivitySetupBinding
import com.kavabanga.signage.SignageApp
import com.kavabanga.signage.ui.player.PlayerActivity
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"
        private const val YANDEX_TOKEN = "y0__wgBELGZqPQEGNDcQyCZu975FzDIisPzB5AJqrhJ_j16EutHF5ZemN-VaYjb"
        private const val ROOT_FOLDER = "disk:/Teliki"
    }

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var cacheManager: CacheManager
    private val diskClient = YandexDiskClient(YANDEX_TOKEN)

    // Список локаций (подпапки disk:/Teliki)
    private var locationNames: List<String> = emptyList()
    private var selectedLocationName: String? = null
    private var selectedSlot: Int = -1
    private val debugLog = StringBuilder()

    // Данные экранов для текущей локации
    private data class ScreenPreview(
        val slot: Int,
        val screenName: String,     // "Экран 1"
        val diskPath: String,       // "disk:/Teliki/Ак-Мечеть/Экран 1"
        val previewUrl: String,     // прямая ссылка на первое изображение
        val fileName: String,       // имя файла для отображения
        val isVideo: Boolean
    )
    private var screenPreviews: List<ScreenPreview> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            prefsManager = PrefsManager.getInstance(this)
            cacheManager = CacheManager(this)

            // Всегда показываем экран выбора — НЕ автозапускаем плеер
            binding = ActivitySetupBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Сбрасываем старые настройки без diskPath (от v1)
            if (prefsManager.isConfigured() && prefsManager.getDiskPath().isBlank()) {
                prefsManager.clear()
                log("🧹 Очищены старые настройки v1")
            }

            // Показываем crash лог если был
            val crashFile = java.io.File(filesDir, SignageApp.CRASH_FILE)
            if (crashFile.exists() && crashFile.length() > 0) {
                val crashText = try {
                    crashFile.readText().take(2000)
                } catch (_: Exception) { "Не удалось прочитать crash лог" }
                crashFile.delete()

                android.app.AlertDialog.Builder(this)
                    .setTitle("💥 Последний крэш")
                    .setMessage(crashText)
                    .setPositiveButton("OK", null)
                    .setCancelable(true)
                    .show()

                log("⚠️ Был крэш — см. диалог")
            }

            log("🚀 Запуск. Загрузка локаций с Яндекс Диска...")
            loadLocations()
            setupSaveButton()
        } catch (e: Exception) {
            Log.e(TAG, "FATAL onCreate: ${e.message}", e)
            // Показываем хоть что-то
            try {
                setContentView(android.widget.TextView(this).apply {
                    text = "Ошибка запуска:\n${e.message}\n\nПереустановите приложение"
                    textSize = 18f
                    setPadding(40, 40, 40, 40)
                })
            } catch (_: Exception) {}
        }
    }

    // ── Логирование на экран ─────────────────────────────

    private fun log(msg: String) {
        Log.d(TAG, msg)
        debugLog.appendLine(msg)
        val lines = debugLog.lines().takeLast(12).joinToString("\n")
        runOnUiThread {
            try { binding.debugLog.text = lines } catch (_: Exception) {}
        }
    }

    // ── Загрузка локаций (подпапки disk:/Teliki) ─────────

    private fun loadLocations() {
        binding.progressBar.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE

        lifecycleScope.launch {
            try {
                log("📡 GET $ROOT_FOLDER")
                val items = diskClient.listFolder(ROOT_FOLDER)
                val folders = items.filter { it.type == "dir" }

                if (folders.isEmpty()) {
                    log("⚠️ Нет подпапок в $ROOT_FOLDER")
                    showLocations(emptyList())
                    return@launch
                }

                log("✅ Получено ${folders.size} локаций")
                showLocations(folders.map { it.name })
            } catch (e: Exception) {
                log("❌ Ошибка загрузки локаций: ${e.message}")
                showLocations(emptyList())
            }
        }
    }

    private fun showLocations(list: List<String>) {
        locationNames = list
        log("📍 Локации: $list")

        if (list.isEmpty()) {
            binding.progressBar.visibility = View.GONE
            binding.contentLayout.visibility = View.VISIBLE
            log("⚠️ Список локаций пуст")
            return
        }

        val adapter = ArrayAdapter(this, R.layout.spinner_item, locationNames)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerLocation.adapter = adapter

        binding.spinnerLocation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedLocationName = locationNames[pos]
                selectedSlot = -1
                log("📍 Выбрана: ${locationNames[pos]}")
                loadScreens(locationNames[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.progressBar.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE
    }

    // ── Загрузка экранов для локации ─────────────────────

    private fun loadScreens(locationName: String) {
        binding.screensLabel.visibility = View.GONE
        binding.screensGrid.visibility = View.GONE
        binding.screensProgress.visibility = View.VISIBLE
        binding.btnSave.visibility = View.GONE

        val locationPath = "$ROOT_FOLDER/$locationName"
        log("📡 Загрузка экранов: $locationPath")

        lifecycleScope.launch {
            try {
                val items = diskClient.listFolder(locationPath)
                val screenFolders = items.filter { it.type == "dir" }.sortedBy { it.name }

                log("✅ Получено ${screenFolders.size} экранов")

                val previews = mutableListOf<ScreenPreview>()
                for (folder in screenFolders) {
                    val slotNumber = extractSlotNumber(folder.name)
                    val screenPath = folder.path

                    // Пытаемся загрузить превью первого файла
                    var previewUrl = ""
                    var fileName = ""
                    var isVideo = false

                    try {
                        val screenItems = diskClient.listFolder(screenPath)
                        val firstFile = screenItems.firstOrNull { it.type == "file" }
                        if (firstFile != null) {
                            fileName = firstFile.name
                            isVideo = firstFile.mimeType?.startsWith("video") == true

                            if (!isVideo) {
                                // Для изображений получаем прямую ссылку
                                previewUrl = diskClient.getDownloadUrl(firstFile.path)
                                log("  🖼️ Превью: $fileName")
                            } else {
                                log("  🎬 Видео: $fileName")
                            }
                        } else {
                            log("  📭 ${folder.name}: пусто")
                        }
                    } catch (e: Exception) {
                        log("  ⚠️ Ошибка превью ${folder.name}: ${e.message}")
                    }

                    previews.add(
                        ScreenPreview(
                            slot = slotNumber,
                            screenName = folder.name,
                            diskPath = screenPath,
                            previewUrl = previewUrl,
                            fileName = fileName,
                            isVideo = isVideo
                        )
                    )
                }

                screenPreviews = previews.sortedBy { it.slot }
                showScreenCards()
            } catch (e: Exception) {
                log("❌ Ошибка загрузки экранов: ${e.message}")
                screenPreviews = emptyList()
                showScreenCards()
            }
        }
    }

    /**
     * Извлекает номер экрана из имени папки.
     * "Экран 1" → 1, "Экран 2" → 2, и т.д.
     * Если не удалось — возвращает 0.
     */
    private fun extractSlotNumber(folderName: String): Int {
        val regex = Regex("""(\d+)""")
        return regex.find(folderName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // ── Отображение карточек экранов ────────────────────

    private fun showScreenCards() {
        val grid = binding.screensGrid
        grid.removeAllViews()

        binding.screensProgress.visibility = View.GONE
        binding.screensLabel.visibility = View.VISIBLE
        binding.screensGrid.visibility = View.VISIBLE

        if (screenPreviews.isEmpty()) {
            log("📭 Нет экранов для выбранной локации")
        }

        for (preview in screenPreviews) {
            val card = LayoutInflater.from(this).inflate(R.layout.item_screen_card, grid, false)

            val thumb = card.findViewById<ImageView>(R.id.screenThumb)
            val empty = card.findViewById<TextView>(R.id.screenEmpty)
            val label = card.findViewById<TextView>(R.id.screenLabel)
            val info = card.findViewById<TextView>(R.id.screenInfo)
            val border = card.findViewById<View>(R.id.screenSelected)

            label.text = preview.screenName

            if (preview.previewUrl.isNotBlank()) {
                // Есть изображение — показываем миниатюру
                empty.visibility = View.GONE
                thumb.visibility = View.VISIBLE
                info.text = preview.fileName

                Glide.with(this)
                    .load(preview.previewUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .error(R.color.black)
                    .into(thumb)
            } else if (preview.isVideo) {
                // Видео — показываем иконку
                thumb.visibility = View.GONE
                empty.visibility = View.VISIBLE
                empty.text = "🎬"
                info.text = "Видео: ${preview.fileName}"
            } else if (preview.fileName.isNotBlank()) {
                // Файл есть, но не удалось получить превью
                thumb.visibility = View.GONE
                empty.visibility = View.VISIBLE
                empty.text = "📄"
                info.text = preview.fileName
            } else {
                // Пусто
                thumb.visibility = View.GONE
                empty.visibility = View.VISIBLE
                empty.text = "📭"
                info.text = "Нет контента"
            }

            // Клик — выбор экрана
            card.setOnClickListener {
                selectedSlot = preview.slot
                log("✅ Выбран: ${preview.screenName} (слот ${preview.slot})")
                // Обновляем рамки
                for (i in 0 until grid.childCount) {
                    grid.getChildAt(i).findViewById<View>(R.id.screenSelected).visibility = View.GONE
                }
                border.visibility = View.VISIBLE
                binding.btnSave.visibility = View.VISIBLE
            }

            // Фокус для пульта ТВ
            card.setOnFocusChangeListener { _, hasFocus ->
                card.scaleX = if (hasFocus) 1.05f else 1f
                card.scaleY = if (hasFocus) 1.05f else 1f
            }

            grid.addView(card)
        }
    }

    // ── Сохранение и запуск ────────────────────────────

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val locName = selectedLocationName ?: run {
                Toast.makeText(this, "Выберите кофейню", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedSlot < 1) {
                Toast.makeText(this, "Выберите экран", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Находим выбранный экран для получения полного пути
            val selectedScreen = screenPreviews.firstOrNull { it.slot == selectedSlot }
            val diskPath = selectedScreen?.diskPath ?: "$ROOT_FOLDER/$locName/Экран $selectedSlot"

            prefsManager.save(
                locationId = locName,
                locationName = locName,
                slotNumber = selectedSlot,
                diskPath = diskPath
            )
            log("💾 Сохранено: $locName, экран $selectedSlot")
            log("💾 Путь: $diskPath")
            Log.i(TAG, "Saved: $locName, slot=$selectedSlot, path=$diskPath")
            launchPlayer()
        }
    }

    private fun launchPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
        finish()
    }
}
