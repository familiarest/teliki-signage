package com.kavabanga.signage.ui.setup

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.kavabanga.signage.R
import com.kavabanga.signage.data.FirestoreRestClient
import com.kavabanga.signage.data.PrefsManager
import com.kavabanga.signage.databinding.ActivitySetupBinding
import com.kavabanga.signage.model.Location
import com.kavabanga.signage.ui.player.PlayerActivity

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupActivity"

        /**
         * Hardcoded locations — fallback when Firestore is unreachable.
         * These are the 6 coffee shops from the database.
         * If a new location is added via admin panel, add it here too.
         */
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

    private var locations: List<Location> = emptyList()
    private var selectedLocation: Location? = null
    private var selectedSlot: Int = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefsManager = PrefsManager.getInstance(this)

        if (prefsManager.isConfigured()) {
            launchPlayer()
            return
        }

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSlotSpinner()
        loadLocations()
        setupSaveButton()
    }

    private fun setupSlotSpinner() {
        val slots = (1..5).map { "Экран $it" }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, slots)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerSlot.adapter = adapter
        binding.spinnerSlot.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedSlot = pos + 1 }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun loadLocations() {
        binding.progressBar.visibility = View.VISIBLE
        binding.contentLayout.visibility = View.GONE

        // Try fetching from server first
        restClient.getCollection("locations",
            onSuccess = { docs ->
                if (docs.isNotEmpty()) {
                    Log.i(TAG, "Server: ${docs.size} locations")
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
                // Server returned empty — use fallback
                Log.w(TAG, "Server empty, using fallback")
                showLocations(FALLBACK_LOCATIONS)
            },
            onError = { e ->
                // Server unreachable — use fallback
                Log.w(TAG, "Server error: ${e.message}, using fallback")
                showLocations(FALLBACK_LOCATIONS)
            }
        )
    }

    private fun showLocations(list: List<Location>) {
        locations = list
        val adapter = ArrayAdapter(this, R.layout.spinner_item, locations.map { it.name })
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerLocation.adapter = adapter
        selectedLocation = locations[0]

        binding.spinnerLocation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { selectedLocation = locations[pos] }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.progressBar.visibility = View.GONE
        binding.contentLayout.visibility = View.VISIBLE
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val loc = selectedLocation ?: run {
                Toast.makeText(this, R.string.select_location, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefsManager.save(locationId = loc.id, locationName = loc.name, slotNumber = selectedSlot)
            Log.i(TAG, "Saved: ${loc.name} (${loc.id}), slot=$selectedSlot")
            launchPlayer()
        }
    }

    private fun launchPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
        finish()
    }
}
