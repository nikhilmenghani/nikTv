package com.nikhil.niktv.ui

import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.MainActivity
import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.PortalType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in: uses the tablet's configured provider and viewing profile. */
class ExpiredEpisodeSessionDeviceTest {
    @Test fun nextEpisodeRecoversFromAnExpiredSessionUsingTheLoadedEpisode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("realProviderPlayback") == "true")
        assumeTrue(instrumentation.targetContext.packageName == "com.nikhil.niktv.debug")
        (instrumentation.targetContext.getSystemService(AudioManager::class.java))
            .setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun await(label: String, condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < until) {
                if (condition()) return
                SystemClock.sleep(100)
            }
            fail("Timed out waiting for $label")
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            lateinit var vm: NikTvViewModel
            main { vm = ViewModelProvider(activity)[NikTvViewModel::class.java] }
            await("configured Stalker history") {
                vm.state.value.session?.profile?.portalType == PortalType.STALKER &&
                    vm.state.value.recentlyPlayed.any { it.kind == FavoriteKind.SERIES && it.lastPlayed != null }
            }
            val recent = vm.state.value.recentlyPlayed.first { it.kind == FavoriteKind.SERIES && it.lastPlayed != null }
            main { vm.openRecent(recent) }
            await("initial episode") { vm.state.value.nowPlaying != null }
            SystemClock.sleep(2_000)
            val playing = requireNotNull(vm.state.value.nowPlaying)
            val next = requireNotNull(playing.nextEpisode) { "Choose Continue Watching history with a loaded next episode" }
            @Suppress("UNCHECKED_CAST")
            val state = NikTvViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<NikTvState>
            val originalAuthenticate = vm.authenticatePortal
            val authentications = AtomicInteger()
            vm.authenticatePortal = { profile ->
                authentications.incrementAndGet()
                originalAuthenticate(profile)
            }
            val started = SystemClock.elapsedRealtime()
            try {
                main {
                    // Only this app's in-memory token is invalidated. No remote
                    // credentials, server configuration or other device is changed.
                    state.value = state.value.copy(session = requireNotNull(state.value.session)
                        .copy(token = "expired-device-regression"))
                    vm.playNextEpisode()
                }
                await("next episode after authentication") {
                    vm.state.value.nowPlaying?.let {
                        it.playbackAttemptId != playing.playbackAttemptId &&
                            it.media.seasonNumber == next.seasonNumber && it.media.episodeNumber == next.episodeNumber
                    } == true
                }
                val elapsed = SystemClock.elapsedRealtime() - started
                assertEquals(1, authentications.get())
                assertNotEquals("expired-device-regression", vm.state.value.session?.token)
                assertNull(vm.state.value.error)
                SystemClock.sleep(2_000)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "expired-next recoveryMs=$elapsed authentications=${authentications.get()}\n")
                })
            } finally {
                main { vm.closePlayer(); vm.authenticatePortal = originalAuthenticate }
            }
        } finally { main { activity.finish() } }
    }
}
