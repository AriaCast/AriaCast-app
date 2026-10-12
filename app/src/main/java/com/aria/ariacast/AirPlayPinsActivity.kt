package com.aria.ariacast

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AirPlayPinsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var adapter: PinAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val accentColor = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        setTheme(ThemeUtils.getThemeForAccent(accentColor))

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_airplay_pins)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.pinRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)

        adapter = PinAdapter(
            onEdit = { entry -> showEditDialog(entry) },
            onDelete = { entry -> deletePin(entry) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadPins()
    }

    /** One stored AirPlay secret: a receiver password, or a pairing made with an onscreen code. */
    data class Entry(val key: String, val title: String, val subtitle: String, val editable: Boolean)

    private fun loadPins() {
        val prefs = getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        // PINs are one-time codes: pairing now stores long-term keys instead, so any
        // PIN saved by older versions is useless - drop it.
        prefs.all.keys.filter { it.startsWith("airplay2_pin_") }.takeIf { it.isNotEmpty() }?.let { stale ->
            prefs.edit().apply { stale.forEach { remove(it) } }.apply()
        }
        val entries = prefs.all.mapNotNull { (key, value) ->
            when {
                key.startsWith(AudioCastService.KEY_AIRPLAY_PASSWORD_PREFIX) ->
                    Entry(key, key.removePrefix(AudioCastService.KEY_AIRPLAY_PASSWORD_PREFIX), "••••••", editable = true)
                key.startsWith(AudioCastService.KEY_HAP_PAIRING_PREFIX) ->
                    Entry(key, key.removePrefix(AudioCastService.KEY_HAP_PAIRING_PREFIX), "Paired (AirPlay 2)", editable = false)
                key.startsWith(AudioCastService.KEY_RAOP_LEGACY_PREFIX) && value == true ->
                    Entry(key, key.removePrefix(AudioCastService.KEY_RAOP_LEGACY_PREFIX), "Paired (AirPlay)", editable = false)
                else -> null
            }
        }.sortedBy { it.title }

        adapter.submitList(entries)
        emptyStateText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEditDialog(entry: Entry) {
        if (!entry.editable) return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val container = android.widget.FrameLayout(this).apply {
            val params = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(64, 32, 64, 32)
            layoutParams = params
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.airplay_password_title))
            .setMessage(entry.title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val newSecret = input.text.toString()
                if (newSecret.isNotEmpty()) {
                    val prefs = getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(entry.key, newSecret).apply()
                    loadPins()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deletePin(entry: Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_pin))
            .setMessage(entry.title)
            .setPositiveButton(R.string.delete_pin) { _, _ ->
                val prefs = getSharedPreferences(AudioCastService.SECURE_PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove(entry.key).apply()
                loadPins()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class PinAdapter(
        private val onEdit: (Entry) -> Unit,
        private val onDelete: (Entry) -> Unit
    ) : RecyclerView.Adapter<PinAdapter.ViewHolder>() {

        private var items = emptyList<Entry>()

        fun submitList(newItems: List<Entry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_airplay_pin, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.hostText.text = entry.title
            holder.pinText.text = entry.subtitle
            holder.editButton.visibility = if (entry.editable) View.VISIBLE else View.GONE
            holder.editButton.setOnClickListener { onEdit(entry) }
            holder.deleteButton.setOnClickListener { onDelete(entry) }
        }

        override fun getItemCount() = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val hostText: TextView = view.findViewById(R.id.hostText)
            val pinText: TextView = view.findViewById(R.id.pinText)
            val editButton: ImageButton = view.findViewById(R.id.editButton)
            val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
        }
    }
}
