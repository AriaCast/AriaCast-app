package com.aria.ariacast

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aria.ariacast.raop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow

class AirPlayService : Service() {
    private val TAG = "AirPlayService"
    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "AirPlayChannel"
    
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var raopClient: RaopClient? = null
    private val resampler = AudioResampler()
    
    private val audioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 500)
    
    private val binder = AirPlayBinder()

    inner class AirPlayBinder : Binder() {
        fun getService(): AirPlayService = this@AirPlayService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val device = RaopDevice(
                    name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Unknown",
                    host = intent.getStringExtra(EXTRA_DEVICE_HOST) ?: "",
                    port = intent.getIntExtra(EXTRA_DEVICE_PORT, 5000),
                    encryptionType = intent.getIntExtra(EXTRA_DEVICE_ET, 1)
                )
                val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_TOKEN, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_TOKEN)
                }
                
                if (token != null && device.host.isNotEmpty()) {
                    startCasting(token, device)
                }
            }
            ACTION_STOP -> stopCasting()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCasting(token: Intent, device: RaopDevice) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(Activity.RESULT_OK, token)
        
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection failed")
            stopSelf()
            return
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val sampleRate = 48000 // Android capture standard
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2
        
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build())
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            stopCasting()
            return
        }

        startForeground(NOTIFICATION_ID, createNotification(device.name), 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)

        scope.launch {
            val localIp = RaopUtils.getLocalIpAddress()?.hostAddress ?: "127.0.0.1"
            val deviceId = RaopUtils.generateDeviceId()
            raopClient = RaopClient(device, localIp, deviceId)
            
            if (raopClient!!.connect()) {
                raopClient!!.startStreaming(audioFlow)
                captureAudio()
            } else {
                Log.e(TAG, "RAOP connection failed")
                stopCasting()
            }
        }
    }

    private fun captureAudio() {
        audioRecord?.startRecording()
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(3840) // 20ms at 48kHz stereo 16bit
            while (isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val resampled = resampler.resample(buffer.copyOf(read))
                    audioFlow.emit(resampled)
                }
            }
        }
    }

    private fun stopCasting() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        raopClient?.stop()
        raopClient = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(deviceName: String): Notification {
        val stopIntent = Intent(this, AirPlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AriaCast AirPlay")
            .setContentText("Casting to $deviceName")
            .setSmallIcon(R.drawable.ic_tile_icon)
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AirPlay Casting", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    companion object {
        const val ACTION_START = "com.aria.ariacast.ACTION_AIRPLAY_START"
        const val ACTION_STOP = "com.aria.ariacast.ACTION_AIRPLAY_STOP"
        const val EXTRA_PROJECTION_TOKEN = "extra_token"
        const val EXTRA_DEVICE_NAME = "extra_name"
        const val EXTRA_DEVICE_HOST = "extra_host"
        const val EXTRA_DEVICE_PORT = "extra_port"
        const val EXTRA_DEVICE_ET = "extra_et"
    }
}
