package com.aria.ariacast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class PluginsActivity : AppCompatActivity() {

    private lateinit var pluginManager: PluginManager
    private lateinit var adapter: PluginAdapter

    private val openDocumentTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            
            getSharedPreferences("plugins_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("plugin_folder_uri", it.toString())
                .apply()
            
            pluginManager = PluginManager(this)
            syncAndRefresh()
            Toast.makeText(this, getString(R.string.plugin_folder_updated), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val accentColor = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        setTheme(ThemeUtils.getThemeForAccent(accentColor))
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugins)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val pluginPrefs = getSharedPreferences("plugins_prefs", Context.MODE_PRIVATE)
        val hasConsent = pluginPrefs.getBoolean("plugin_consent_given", false)

        if (!hasConsent) {
            showConsentDialog()
        } else {
            initializePlugins()
        }

        findViewById<FloatingActionButton>(R.id.addPluginFab).setOnClickListener {
            syncAndRefresh()
        }
    }

    private fun showConsentDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plugin_consent_title))
            .setMessage(getString(R.string.plugin_consent_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.accept)) { _, _ ->
                getSharedPreferences("plugins_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("plugin_consent_given", true)
                    .apply()
                initializePlugins()
            }
            .setNegativeButton(getString(R.string.decline)) { _, _ ->
                finish()
            }
            .show()
    }

    private fun initializePlugins() {
        pluginManager = PluginManager(this)
        
        val recyclerView = findViewById<RecyclerView>(R.id.pluginsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        val pluginPrefs = getSharedPreferences("plugins_prefs", Context.MODE_PRIVATE)
        val folderUri = pluginPrefs.getString("plugin_folder_uri", null)
        
        if (folderUri == null) {
            showFolderSelectionDialog()
        } else {
            syncAndRefresh()
        }
    }

    private fun showFolderSelectionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plugin_folder_title))
            .setMessage(getString(R.string.plugin_folder_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.select_folder)) { _, _ ->
                openDocumentTree.launch(null)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                finish()
            }
            .show()
    }

    private fun syncAndRefresh() {
        lifecycleScope.launch {
            Toast.makeText(this@PluginsActivity, getString(R.string.syncing_plugins), Toast.LENGTH_SHORT).show()
            val success = pluginManager.syncPluginsFromGitHub()
            if (success) {
                Toast.makeText(this@PluginsActivity, getString(R.string.plugins_synced), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@PluginsActivity, getString(R.string.sync_failed), Toast.LENGTH_SHORT).show()
            }
            refreshPlugins()
        }
    }

    private fun refreshPlugins() {
        adapter = PluginAdapter(pluginManager.getPlugins()) { plugin, enabled ->
            pluginManager.setPluginEnabled(plugin.id, enabled)
        }
        findViewById<RecyclerView>(R.id.pluginsRecyclerView).adapter = adapter
    }

    inner class PluginAdapter(
        private val plugins: List<Plugin>,
        private val onEnabledChanged: (Plugin, Boolean) -> Unit
    ) : RecyclerView.Adapter<PluginAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_plugin, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val plugin = plugins[position]
            holder.name.text = plugin.name
            holder.author.text = getString(R.string.plugin_author_format, plugin.author, plugin.version)
            holder.switch.isChecked = plugin.isEnabled
            
            holder.switch.setOnCheckedChangeListener { _, isChecked ->
                onEnabledChanged(plugin, isChecked)
            }

            holder.itemView.setOnClickListener {
                showPluginDetails(plugin)
            }
        }

        private fun showPluginDetails(plugin: Plugin) {
            MaterialAlertDialogBuilder(this@PluginsActivity)
                .setTitle(plugin.name)
                .setMessage(getString(R.string.plugin_details_format, plugin.description, plugin.version, plugin.author))
                .setPositiveButton(getString(R.string.close), null)
                .setNeutralButton(getString(R.string.configure)) { _, _ ->
                    pluginManager.requestPluginConfig(plugin.id, this@PluginsActivity)
                }
                .show()
        }

        override fun getItemCount() = plugins.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.pluginName)
            val author: TextView = view.findViewById(R.id.pluginAuthor)
            val switch: MaterialSwitch = view.findViewById(R.id.pluginSwitch)
        }
    }
}
