package com.aria.ariacast.companion

import android.media.AudioAttributes.USAGE_ALARM
import android.media.AudioAttributes.USAGE_GAME
import android.media.AudioAttributes.USAGE_MEDIA
import android.media.AudioAttributes.USAGE_NOTIFICATION
import android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE
import android.media.AudioAttributes.USAGE_UNKNOWN
import android.media.AudioManager.RINGER_MODE_NORMAL
import android.media.AudioManager.RINGER_MODE_VIBRATE
import android.media.AudioManager.STREAM_ALARM
import android.media.AudioManager.STREAM_MUSIC
import android.media.AudioManager.STREAM_NOTIFICATION
import android.media.AudioManager.STREAM_RING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaOnlyGateTest {
    private class Env(
        val volumes: Map<Int, Int> = mapOf(STREAM_MUSIC to 10, STREAM_NOTIFICATION to 5, STREAM_RING to 5, STREAM_ALARM to 5),
        override val ringerMode: Int = RINGER_MODE_NORMAL,
        override val companionDeviceId: Int? = 42,
    ) : AudioEnv {
        override fun streamVolume(stream: Int) = volumes[stream] ?: 0
    }

    private fun p(usage: Int, stream: Int, device: Int? = null) = PlayerInfo(usage, stream, device)

    @Test fun mediaAndUnknownPass() {
        assertNull(MediaOnlyRules.blockingPlayer(listOf(p(USAGE_MEDIA, STREAM_MUSIC), p(USAGE_UNKNOWN, STREAM_MUSIC)), Env(), false))
    }

    @Test fun audibleNotificationBlocks() {
        val b = MediaOnlyRules.blockingPlayer(listOf(p(USAGE_MEDIA, STREAM_MUSIC), p(USAGE_NOTIFICATION, STREAM_NOTIFICATION)), Env(), false)
        assertNotNull(b)
        assertEquals("notification sound", MediaOnlyRules.describe(b!!.usage))
    }

    @Test fun notificationAtVolumeZeroDoesNotBlock() {
        val env = Env(volumes = mapOf(STREAM_MUSIC to 10, STREAM_NOTIFICATION to 0))
        assertNull(MediaOnlyRules.blockingPlayer(listOf(p(USAGE_NOTIFICATION, STREAM_NOTIFICATION)), env, false))
    }

    @Test fun silentOrVibrateModeIgnoresNotificationsAndRingtones() {
        val env = Env(ringerMode = RINGER_MODE_VIBRATE)
        assertNull(MediaOnlyRules.blockingPlayer(listOf(p(USAGE_NOTIFICATION, STREAM_NOTIFICATION), p(USAGE_NOTIFICATION_RINGTONE, STREAM_RING)), env, false))
        // ...but alarms still block in vibrate mode
        assertNotNull(MediaOnlyRules.blockingPlayer(listOf(p(USAGE_ALARM, STREAM_ALARM)), env, false))
    }

    @Test fun soundsRoutedElsewhereDoNotBlock() {
        assertNull(MediaOnlyRules.blockingPlayer(listOf(p(USAGE_ALARM, STREAM_ALARM, device = 7)), Env(companionDeviceId = 42), false))
        assertNotNull(MediaOnlyRules.blockingPlayer(listOf(p(USAGE_ALARM, STREAM_ALARM, device = 42)), Env(companionDeviceId = 42), false))
    }

    @Test fun gamesFollowTheSetting() {
        assertNotNull(MediaOnlyRules.blockingPlayer(listOf(p(USAGE_GAME, STREAM_MUSIC)), Env(), includeGames = false))
        assertNull(MediaOnlyRules.blockingPlayer(listOf(p(USAGE_GAME, STREAM_MUSIC)), Env(), includeGames = true))
    }

    private fun pcm(v: Short, frames: Int) = ByteArray(frames * 4).also { b ->
        for (i in 0 until frames * 2) { b[2 * i] = v.toByte(); b[2 * i + 1] = (v.toInt() shr 8).toByte() }
    }
    private fun sample(b: ByteArray, i: Int) = ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort()

    @Test fun envelopeFadesOutAtOnceAndWaitsBeforeReopening() {
        val e = GateEnvelope(delayMs = 100, fadeMs = 20, holdMs = 200)
        val frame = 960 // 20 ms at 48 kHz
        e.setBlocked(true, 0)
        val first = pcm(10000, frame); e.process(first, 0)
        assertTrue("fade starts at full level", sample(first, 0) > 9000)
        assertEquals(0, sample(first, frame * 2 - 1).toInt()) // faded out by the end of 20 ms
        val muted = pcm(10000, frame); e.process(muted, 20)
        assertTrue(muted.all { it == 0.toByte() })

        e.setBlocked(false, 1000)
        val stillMuted = pcm(10000, frame); e.process(stillMuted, 1200) // < 1000 + 100 + 200
        assertTrue(stillMuted.all { it == 0.toByte() })
        val reopened = pcm(10000, frame); e.process(reopened, 1300)
        assertEquals(10000, sample(reopened, frame * 2 - 1).toInt())
    }

    @Test fun envelopeIsTransparentWhenOpen() {
        val e = GateEnvelope()
        val b = pcm(1234, 960); val copy = b.copyOf()
        e.process(b, 0)
        assertTrue(b.contentEquals(copy))
    }
}
