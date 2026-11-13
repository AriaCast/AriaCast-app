package com.aria.ariacast.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Keeps calls on the phone: reports [onCallChanged] = true as soon as the phone rings or a
 * call is active (cellular, or a VoIP app holding MODE_IN_COMMUNICATION for over a second),
 * and false once everything has been idle for 2 s.
 *
 * Needs READ_PHONE_STATE for cellular calls. Without it only VoIP is detected here; the
 * media-only gate still keeps ringtones and call audio out of the house.
 */
class CallGuard(private val context: Context, private val onCallChanged: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val tm = context.getSystemService(TelephonyManager::class.java)
    private val am = context.getSystemService(AudioManager::class.java)
    private var telephonyCallback: Any? = null
    private var inCall = false
    private var cellularBusy = false
    private var running = false

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private val idleRelease = Runnable { setInCall(false) }

    private val voipPoll = object : Runnable {
        private var commSince = 0L
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL) {
                if (commSince == 0L) commSince = now
                if (now - commSince > 1000) update(true)
            } else {
                commSince = 0L
                if (!cellularBusy) update(false)
            }
            main.postDelayed(this, 500)
        }
    }

    fun start() {
        if (running) return
        running = true
        if (hasPermission) registerTelephony()
        main.post(voipPoll)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        unregisterTelephony()
        if (inCall) setInCall(false)
    }

    private fun onCellularState(state: Int) {
        cellularBusy = state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK
        update(cellularBusy)
    }

    private fun update(busy: Boolean) {
        if (busy) {
            main.removeCallbacks(idleRelease)
            setInCall(true)
        } else if (inCall) {
            main.removeCallbacks(idleRelease)
            main.postDelayed(idleRelease, 2000)
        }
    }

    private fun setInCall(value: Boolean) {
        if (inCall == value) return
        inCall = value
        onCallChanged(value)
    }

    @Suppress("DEPRECATION")
    private fun registerTelephony() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onCellularState(state)
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
                telephonyCallback = cb
            } else {
                val l = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onCellularState(state)
                }
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                telephonyCallback = l
            }
        } catch (_: SecurityException) {
            telephonyCallback = null
        }
    }

    @Suppress("DEPRECATION")
    private fun unregisterTelephony() {
        when (val cb = telephonyCallback) {
            null -> {}
            is PhoneStateListener -> tm.listen(cb, PhoneStateListener.LISTEN_NONE)
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tm.unregisterTelephonyCallback(cb as TelephonyCallback)
        }
        telephonyCallback = null
    }
}
