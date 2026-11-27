package com.aria.ariacast.ui

import com.aria.ariacast.AudioCastService
import com.aria.ariacast.CastGroup
import com.aria.ariacast.DiscoveryState
import com.aria.ariacast.Server
import com.aria.ariacast.pipeline.SourceKind
import kotlinx.coroutines.flow.StateFlow

/**
 * Screens the app can show. Kept as a simple state, no navigation library needed.
 * HOME, RECEIVE and SETTINGS are the three navigation destinations; the rest are pushed
 * over them and return to the destination they were opened from.
 */
enum class Screen {
    HOME, RECEIVE, SETTINGS,
    NOW_PLAYING, RECEIVER_DISPLAY, DOCTOR, KNOWN_SPEAKERS, ONBOARDING;

    val isDestination: Boolean get() = this == HOME || this == RECEIVE || this == SETTINGS
}

/** Everything the Compose UI needs from MainActivity. */
interface AppController {
    val servers: StateFlow<List<Server>>
    val discoveryState: StateFlow<DiscoveryState>
    val service: StateFlow<AudioCastService?>
    val groups: StateFlow<List<CastGroup>>
    val notificationAccess: StateFlow<Boolean>
    val multiroomEnabled: StateFlow<Boolean>
    val screen: StateFlow<Screen>
    val isTv: Boolean

    fun navigate(screen: Screen)
    fun castTo(servers: List<Server>, source: SourceKind = SourceKind.SYSTEM_CAPTURE, inputDeviceId: Int? = null)
    fun stopCasting()
    fun refreshDiscovery()
    /** [platform]: "AriaCast", "AirPlay2", "AirPlay", "Google Cast" or "VBAN"; [extra]: protocol settings ([com.aria.ariacast.ExtraFields]). */
    fun addManualServer(host: String, port: Int, name: String, platform: String, extra: String? = null): Boolean
    fun removeServer(server: Server)
    fun saveGroup(name: String, hosts: List<String>)
    fun deleteGroup(group: CastGroup)
    fun requestNotificationAccess()
    fun setMultiroomEnabled(enabled: Boolean)
    fun openSettings()
    fun openActivity(cls: Class<*>)
    fun finishOnboarding()
}
