package com.aria.ariacast

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Starts the real app on the JVM, as a user who finished the setup, and fails if anything crashes:
 * the main thread or any background thread. Unit tests of the parts cannot see a crash that only
 * happens when the parts run together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36])
class StartupSmokeTest {
    private val crashes = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Before fun catchCrashes() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
    }

    @After fun restore() = Thread.setDefaultUncaughtExceptionHandler(previous)

    private fun idle() = repeat(10) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        Thread.sleep(100) // let background work (the list download, discovery) run too
    }

    private fun bindRealService(context: Context) {
        // Robolectric binds services with no component name unless told which one answers; a
        // phone always passes it. Give it the real service, created the way Android would.
        val service = Robolectric.buildService(AudioCastService::class.java).create().get()
        shadowOf(context as Application).setComponentNameAndServiceForBindService(
            ComponentName(context, AudioCastService::class.java), service.onBind(Intent()),
        )
    }

    private fun assertNoCrash() =
        assertEquals(crashes.joinToString("\n\n") { it.stackTraceToString().take(4000) }, 0, crashes.size)

    @Test fun afterTheSetupTheHomeScreenComesUpWithoutCrashing() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(MainActivity.KEY_ONBOARDED, true).commit()
        bindRealService(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            idle()
            scenario.onActivity { }
        }
        assertNoCrash()
    }

    @Test fun finishingTheSetupWithSpeakersOnTheNetworkDoesNotCrash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        bindRealService(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            idle()
            scenario.onActivity { it.finishOnboarding() }
            idle()
            // One speaker per protocol, as discovery would list them, so every row is drawn.
            scenario.onActivity { a ->
                listOf("AirPlay", "AirPlay2", "DLNA", "Google Cast", "AriaCast", "Snapcast", "VBAN", "Sendspin", "Manual")
                    .forEachIndexed { i, p -> a.addManualServer("192.168.1.${10 + i}", 7000 + i, "Speaker $p", p, "model=WiiM Pro;manufacturer=Linkplay") }
            }
            idle()
        }
        assertNoCrash()
    }
}
