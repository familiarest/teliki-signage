package com.kavabanga.signage.ui.setup

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.kavabanga.signage.R
import com.kavabanga.signage.data.CacheManager
import com.kavabanga.signage.data.FirestoreRestClient
import com.kavabanga.signage.data.PrefsManager
import com.kavabanga.signage.databinding.ActivitySetupBinding
import com.kavabanga.signage.model.Location
import com.kavabanga.signage.ui.player.PlayerActivity

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"

        private val FALLBACK_LOCATIONS = listOf(
            Location(id = "M200wBGhHOGJxfhiHx2v", name = "Ак-Мечеть", createdAt = 1),
            Location(id = "XNwvh8GUxvdMJTdCD1l0", name = "Франко", createdAt = 2),
            Location(id = "Nc2gPJKAtoNS8Etg7ibz", name = "Пушкина", createdAt = 3),
            Location(id = "8IZ9AfGntTfg2j2SpFFx", name = "Евпатория", createdAt = 4),
            Location(id = "nsNLtfKI1XPnRwxdu6eF", name = "Саки", createdAt = 5),
            Location(id = "Q9bg7x55gLEkG4FaHxBy", name = "Бахчисарай", createdAt = 6),
        )
    }

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefsManager: PrefsManager
    private val restClient = FirestoreRestClient()
    private lateinit var cacheManager: CacheManager

    private var locations: List<Location> = emptyList()
    private var selectedLocation: Location? = null
    private var selectedSlot: Int = -1
    private val debugLog = StringBuilder()

    // Данные экранов для текущей локации: slot_number -> (mediaUrl, fileName, mediaType)
    private data class ScreenPreview(
        val slot: Int,
        val mediaUrl: String,
        val fileName: String,
        val mediaType: String
    )
    private var screenPreviews: List<ScreenPreview> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefsManager = PrefsManager.getInstance(this)
        cacheManager = CacheManager(this)

        if (prefsManager.isConfigured()) {
            launchPlayer()
            return
        }

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Показываем последний крэш если есть
        val crashPrefs = getSharedPreferences("crash", MODE_PRIVATE)
        val lastCrash = crashPrefs.getString("last_crash", null)
        if (lastCrash != null) {
            log("⚠️ ПОСЛЕДНИЙ КРЭШ:")
            // Показываем только первые 5 строк стектрейса
            lastCrash.lines().take(5).forEach { log(it) }
            crashPrefs.edit().remove("last_crash").apply()
        }

        // Показываем логи REST-клиента на экране
        restClient.logListener = { msg -> log(msg) }

        log("🚀 Запуск. Загрузка локаций...")
        loadLocations()
        setupSaveButton()
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

    // ── Загрузка локаций ─────────────────────────────────

    private fun loadLocations() {
        binding.progressBar.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE

        restClient.getCollection("locations",
            onSuccess = { docs ->
                log("✅ Получено ${docs.size} локаций с сервера")
                if (docs.isNotEmpty()) {
                    val parsed = docs.mapNotNull { doc ->
                        val id = doc["__id__"] as? String ?: return@mapNotNull null
                        val name = doc["name"] as? String ?: return@mapNotNull null
                        Location(id = id, name = name, createdAt = 0L)
                    }
                    if (parsed.isNotEmpty()) {
                        showLocations(parsed)
                        return@getCollection
                    }
                }
                log("⚠️ Сервер вернул пусто, используем fallback")
                showLocations(FALLBACK_LOCATIONS)
            },
            onError = { e ->
                log("❌ Ошибка сети: ${e.message}")
                log("⚠️ Используем fallback локации")
                showLocations(FALLBACK_LOCATIONS)
            }
        )
    }

    private fun showLocations(list: List<Location>) {
        locations = list
        log("📍 Локации: ${list.map { it.name }}")

        val adapter = ArrayAdapter(this, R.layout.spinner_item, locations.map { it.name })
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerLocation.adapter = adapter

        binding.spinnerLocation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedLocation = locations[pos]
                selectedSlot = -1
                log("📍 Выбрана: ${locations[pos].name}")
                loadScreens(locations[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.progressBar.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE
    }

    // ── Загрузка экранов для локации ─────────────────────

    private fun loadScreens(location: Location) {
        binding.screensLabel.visibility = View.GONE
        binding.screensGrid.visibility = View.GONE
        binding.screensProgress.visibility = View.VISIBLE
        binding.btnSave.visibility = View.GONE

        log("📡 Загрузка экранов для ${location.name}...")

        restClient.getSubcollection("locations", location.id, "screens",
            onSuccess = { docs ->
                log("✅ Получено ${docs.size} экранов")
                val previews = mutableListOf<ScreenPreview>()

                for (doc in docs) {
                    val slot = when (val s = doc["slot_number"]) {
                        is Long -> s.toInt()
                        is Number -> s.toInt()
                        else -> continue
                    }
                    @Suppress("UNCHECKED_CAST")
                    val schedule = doc["schedule"] as? List<Map<String, Any?>> ?: emptyList()
                    val firstItem = schedule.firstOrNull()

                    if (firstItem != null) {
                        val mediaUrl = firstItem["media_url"] as? String ?: ""
                        val fileName = firstItem["file_name"] as? String ?: ""
                        val mediaType = firstItem["media_type"] as? String ?: ""
                        previews.add(ScreenPreview(slot, mediaUrl, fileName, mediaType))
                        log("  📺 Экран $slot: $fileName ($mediaType)")
                    } else {
                        previews.add(ScreenPreview(slot, "", "", ""))
                        log("  📺 Экран $slot: пусто")
                    }
                }

                // Добавляем недостающие слоты (до 5)
                for (s in 1..5) {
                    if (previews.none { it.slot == s }) {
                        previews.add(ScreenPreview(s, "", "", ""))
                    }
                }

                screenPreviews = previews.sortedBy { it.slot }
                showScreenCards()
            },
            onError = { e ->
                log("❌ Ошибка загрузки экранов: ${e.message}")
                // Показываем 5 пустых экранов
                screenPreviews = (1..5).map { ScreenPreview(it, "", "", "") }
                showScreenCards()
            }
        )
    }

    // ── Отображение карточек экранов ────────────────────

    private fun showScreenCards() {
        val grid = binding.screensGrid
        grid.removeAllViews()

        binding.screensProgress.visibility = View.GONE
        binding.screensLabel.visibility = View.VISIBLE
        binding.screensGrid.visibility = View.VISIBLE

        for (preview in screenPreviews) {
            val card = LayoutInflater.from(this).inflate(R.layout.item_screen_card, grid, false)

            val thumb = card.findViewById<ImageView>(R.id.screenThumb)
            val empty = card.findViewById<TextView>(R.id.screenEmpty)
            val label = card.findViewById<TextView>(R.id.screenLabel)
            val info = card.findViewById<TextView>(R.id.screenInfo)
            val border = card.findViewById<View>(R.id.screenSelected)

            label.text = "Экран ${preview.slot}"

            if (preview.mediaUrl.isNotBlank()) {
                // Есть контент — показываем миниатюру
                empty.visibility = View.GONE
                thumb.visibility = View.VISIBLE
                info.text = preview.fileName

                // Для изображений загружаем через прокси (обход блокировки)
                val imageUrl = if (preview.mediaUrl.contains("firebasestorage.googleapis.com")) {
                    val encoded = java.net.URLEncoder.encode(preview.mediaUrl, "UTF-8")
                    "https://teliki-signage.vercel.app/api?media=$encoded"
                } else {
                    preview.mediaUrl
                }

                if (preview.mediaType == "video") {
                    // Для видео показываем иконку 🎬
                    thumb.visibility = View.GONE
                    empty.visibility = View.VISIBLE
                    empty.text = "🎬"
                    info.text = "Видео: ${preview.fileName}"
                } else {
                    Glide.with(this)
                        .load(imageUrl)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .centerCrop()
                        .error(R.color.black)
                        .into(thumb)
                }
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
                log("✅ Выбран экран ${preview.slot}")
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
            val loc = selectedLocation ?: run {
                Toast.makeText(this, "Выберите кофейню", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedSlot < 1) {
                Toast.makeText(this, "Выберите экран", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefsManager.save(locationId = loc.id, locationName = loc.name, slotNumber = selectedSlot)
            log("💾 Сохранено: ${loc.name}, экран $selectedSlot")
            Log.i(TAG, "Saved: ${loc.name} (${loc.id}), slot=$selectedSlot")
            launchPlayer()
        }
    }

    private fun launchPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
        finish()
    }
}
