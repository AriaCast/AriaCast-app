package com.aria.ariacast.api

import android.content.Context
import android.content.Intent

/**
 * Basic interface for AriaCast Plugins.
 * 
 * To create a plugin:
 * 1. Create a Service in your app.
 * 2. Add an <intent-filter> with action "com.aria.ariacast.PLUGIN".
 * 3. Add <meta-data> in your manifest to declare capabilities (like video).
 */
interface AriaPlugin {
    
    // Metadata
    val id: String
    val version: String
    
    /**
     * If true, the main AriaCast app will automatically show a 
     * Video/Audio toggle in the main dashboard when this plugin is enabled.
     */
    fun hasVideoSupport(): Boolean

    /**
     * Called when the main app starts a casting session.
     * Use this to initialize your streaming engine.
     */
    fun onSessionStarted(host: String, port: Int)

    /**
     * Called when the main app starts a casting session with a MediaProjection token.
     */
    fun onSessionStartedWithToken(host: String, port: Int, projectionToken: Intent)

    /**
     * Called when the session ends.
     */
    fun onSessionEnded()

    /**
     * Optional: Return an Intent if your plugin has its own configuration screen.
     */
    fun getSettingsIntent(context: Context): Intent?
    
    companion object {
        const val ACTION_PLUGIN = "com.aria.ariacast.PLUGIN"
        
        // Manifest metadata keys for easy discovery without binding
        const val META_SUPPORT_VIDEO = "com.aria.ariacast.plugin.SUPPORT_VIDEO"
        const val META_PLUGIN_ID = "com.aria.ariacast.plugin.ID"
    }
}
