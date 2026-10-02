package com.nikhil.niktv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.nikhil.niktv.model.LiveProgramme
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.currentLiveProgramme
import kotlinx.coroutines.delay

/** Update only when the displayed programme changes; never fetch network data during composition. */
@Composable
internal fun rememberCurrentLiveProgramme(item: MediaItem): State<LiveProgramme?> =
    produceState(item.currentLiveProgramme(System.currentTimeMillis()), item.id, item.liveProgramme, item.liveSchedule) {
        if (item.liveProgramme == null && item.liveSchedule.isEmpty()) {
            value = null
            return@produceState
        }
        while (true) {
            value = item.currentLiveProgramme(System.currentTimeMillis())
            delay(1_000L)
        }
    }
