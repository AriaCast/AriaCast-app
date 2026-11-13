package com.aria.ariacast.ui

import com.aria.ariacast.AudioCastService
import com.aria.ariacast.CastGroup
import com.aria.ariacast.DiscoveryState
import com.aria.ariacast.Server
import com.aria.ariacast.pipeline.SourceKind
import kotlinx.coroutines.flow.StateFlow

/** Screens the app can show. Kept as a simple state, no navigation library needed. */
enum class Screen { HOME, NOW_PLAYING, RECEIVE, DOCTOR, ONBOARDING }

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
    fun addManualServer(host: String, port: Int, name: String): Boolean
    fun removeServer(server: Server)
    fun saveGroup(name: String, hosts: List<String>)
    fun deleteGroup(group: CastGroup)
    fun requestNotificationAccess()
    fun openSettings()
    fun openActivity(cls: Class<*>)
    fun finishOnboarding()
}
