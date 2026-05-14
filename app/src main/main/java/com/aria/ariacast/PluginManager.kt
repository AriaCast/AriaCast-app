package com.aria.ariacast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.aria.ariacast.api.AriaPlugin

class PluginManager(private val context: Context) {

    private var connection: ServiceConnection? = null
    private var pluginInstance: AriaPlugin? = null

    fun getEnabledPlugins(): List<PluginInfo> {
        val intent = Intent(AriaPlugin.ACTION_PLUGIN)
        val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            context.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        }

        val sharedPreferences = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        return resolveInfos.map {
            val serviceInfo = it.serviceInfo
            val packageName = serviceInfo.packageName
            // Force enable the video pro plugin for debugging
            val isEnabled = sharedPreferences.getBoolean("plugin_enabled_$packageName", false) || packageName.contains("video")
            val hasVideo = serviceInfo.metaData?.getBoolean(AriaPlugin.META_SUPPORT_VIDEO, false) ?: false
            
            PluginInfo(
                name = serviceInfo.loadLabel(context.packageManager).toString(),
                packageName = packageName,
                icon = serviceInfo.loadIcon(context.packageManager),
                isEnabled = isEnabled,
                hasVideo = hasVideo,
                className = serviceInfo.name
            )
        }.filter { it.isEnabled }
    }

    fun bindToPlugin(packageName: String, className: String, onConnected: (IBinder) -> Unit) {
        val intent = Intent().apply {
            component = ComponentName(packageName, className)
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) {
                    onConnected(service)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                pluginInstance = null
            }
        }

        context.bindService(intent, connection!!, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        connection?.let {
            context.unbindService(it)
            connection = null
            pluginInstance = null
        }
    }

    data class PluginInfo(
        val name: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable,
        val isEnabled: Boolean,
        val hasVideo: Boolean,
        val className: String
    )
}
