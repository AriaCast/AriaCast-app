package com.aria.ariacast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

data class PluginInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isEnabled: Boolean
)

class PluginLoaderActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PluginAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val accentColor = sharedPreferences.getInt(SettingsActivity.KEY_ACCENT_COLOR, R.color.accent_blue)
        setTheme(ThemeUtils.getThemeForAccent(accentColor))

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugin_loader)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recyclerView = findViewById(R.id.pluginRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadPlugins()
    }

    private fun loadPlugins() {
        val plugins = mutableListOf<PluginInfo>()
        val packageManager = packageManager
        val intent = Intent("com.aria.ariacast.PLUGIN")
        
        val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            packageManager.queryIntentServices(intent, 0)
        }

        val sharedPreferences = getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)

        for (resolveInfo in resolveInfos) {
            val serviceInfo = resolveInfo.serviceInfo
            val name = serviceInfo.loadLabel(packageManager).toString()
            val packageName = serviceInfo.packageName
            val icon = serviceInfo.loadIcon(packageManager)
            val isEnabled = sharedPreferences.getBoolean("plugin_enabled_$packageName", false)

            plugins.add(PluginInfo(name, packageName, icon, isEnabled))
        }

        adapter = PluginAdapter(plugins) { plugin, enabled ->
            sharedPreferences.edit().putBoolean("plugin_enabled_${plugin.packageName}", enabled).apply()
        }
        recyclerView.adapter = adapter
    }
}

class PluginAdapter(
    private val plugins: List<PluginInfo>,
    private val onPluginToggled: (PluginInfo, Boolean) -> Unit
) : RecyclerView.Adapter<PluginAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.pluginIcon)
        val name: TextView = view.findViewById(R.id.pluginName)
        val pkg: TextView = view.findViewById(R.id.pluginPackage)
        val toggle: SwitchMaterial = view.findViewById(R.id.pluginSwitch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_plugin, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val plugin = plugins[position]
        holder.icon.setImageDrawable(plugin.icon)
        holder.name.text = plugin.name
        holder.pkg.text = plugin.packageName
        holder.toggle.isChecked = plugin.isEnabled

        holder.toggle.setOnCheckedChangeListener { _, isChecked ->
            plugin.isEnabled = isChecked
            onPluginToggled(plugin, isChecked)
        }
    }

    override fun getItemCount() = plugins.size
}
