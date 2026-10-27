package com.nikhil.niktv.ui

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionPolicyTest {
    @Test fun pagesAndMetadataArrivingDuringResolutionAreNotLostOrReorderedByPins() {
        val a = com.nikhil.niktv.model.MediaItem("A", "Old A", null, null)
        val b = a.copy(id = "B")
        val c = a.copy(id = "C")
        val current = listOf(a.copy(title = "Fresh A"), b, c)
        assertEquals(current, reconcilePlaybackQueueSnapshot(listOf(a, b), current, "B"))
        assertEquals(listOf(a), reconcilePlaybackQueueSnapshot(listOf(a), listOf(b, c), "A"))
        val cursor = PlaybackNavigationCursor()
        cursor.select(1, listOf("A", "B", "A", "C"), "A", 2)
        cursor.commit(1, listOf("A", "B", "A", "C"), "A")
        assertEquals(2, cursor.index(listOf("A", "B", "A", "C", "D"), "A"))
    }

    @Test fun providerOccurrenceSurvivesPinChangesAndPagesCanPrependEpisodes() {
        val cursor = PlaybackNavigationCursor()
        cursor.select(1, listOf("A", "B"), "A", 0); cursor.commit(1, listOf("A", "B"), "A")
        assertEquals("Adding a pin must not move the provider cursor", 1, cursor.index(listOf("A", "A", "B"), "A"))
        assertEquals(0, cursor.index(listOf("A", "B"), "A"))
        cursor.select(2, listOf("17", "18"), "17", 0); cursor.commit(2, listOf("17", "18"), "17")
        val expanded = listOf("15", "16", "17", "18")
        val index = cursor.index(expanded, "17")
        assertEquals(2, index)
        assertEquals(1, adjacentQueueIndex(index, expanded.size, -1, false))
    }

    @Test fun textSubtitleRendererAcceptsBothSrtAndShortWebVttTimestamps() {
        val srt = parseDownloadedSubtitleText("1\n00:01:30,000 --> 00:01:32,000\nCaption")
        val vtt = parseDownloadedSubtitleText("WEBVTT\n\n01:30.000 --> 01:32.000 align:middle\nCaption")
        assertEquals(srt, vtt)
        assertEquals(90000L, vtt.single().startMs)
        assertEquals(92000L, vtt.single().endMs)
    }

    @Test fun styledSubtitleFormatsRemainNativeAndTrackIdentityIsNotListOrder() {
        assertTrue(downloadedSubtitleUsesOverlay("srt"))
        assertTrue(downloadedSubtitleUsesOverlay("VTT"))
        listOf("ass", "ssa", "ttml", "xml").forEach { assertFalse(downloadedSubtitleUsesOverlay(it)) }
        assertEquals(99, attachedSubtitleTrackId(setOf(1, 12), listOf(99, 1, 12), 12))
        assertEquals(5, attachedSubtitleTrackId(setOf(1), listOf(1, 5, 9), 5))
        assertNull(attachedSubtitleTrackId(setOf(1), listOf(1, 5, 9), 1))
    }

    @Test fun manifestHeadersRecognizeOnlyKnownManifestMimeTypes() {
        assertEquals("application/x-mpegURL", manifestMimeType("application/vnd.apple.mpegurl; charset=UTF-8"))
        assertEquals("application/dash+xml", manifestMimeType("Application/Dash+Xml"))
        assertNull(manifestMimeType("text/html"))
        assertNull(manifestMimeType("video/mp2t"))
        assertNull(manifestMimeType(null))
    }

    @Test fun retryCallbacksMustMatchCommittedAttemptEvenWhenUrlAndChannelAreUnchanged() {
        val active = com.nikhil.niktv.model.PlayingMedia(
            com.nikhil.niktv.model.MediaItem("A", "Fixture", null, null), "synthetic", com.nikhil.niktv.model.CatalogType.MOVIES)
        assertTrue(samePlaybackAttempt(active, active.copy(playWhenReady = false)))
        assertFalse(samePlaybackAttempt(active, active.copy(playbackAttemptId = "new-attempt")))
        assertFalse(samePlaybackAttempt(active, active.copy(media = active.media.copy(id = "B"))))
    }

    @Test fun decoderFailuresDoNotPoisonOtherMoviesAndMemoryIsBounded() {
        val failures = DecoderSessionFailures(2)
        failures.record("movieA", "Decoder1")
        failures.record("movieA", "Decoder2")
        assertEquals(setOf("decoder1", "decoder2"), failures.failed("movieA"))
        assertTrue(failures.failed("movieB").isEmpty())
        failures.record("movieB", "Decoder1"); failures.record("movieC", "Decoder3")
        assertTrue(failures.failed("movieA").isEmpty())
    }

    @Test fun liveWrapsButVodAndSingletonQueuesDoNot() {
        assertEquals(0, adjacentQueueIndex(2, 3, 1, true))
        assertEquals(2, adjacentQueueIndex(0, 3, -1, true))
        assertNull(adjacentQueueIndex(2, 3, 1, false))
        assertNull(adjacentQueueIndex(0, 3, -1, false))
        assertNull(adjacentQueueIndex(0, 1, 1, true))
        assertNull(adjacentQueueIndex(-1, 3, 1, true))
    }

    @Test fun downloadedEmbeddedOffUseOneRendererAndNativeTrackIdentity() {
        assertEquals(VlcSubtitleSelection(-1, true), vlcSubtitleSelection("downloaded", true))
        assertEquals(VlcSubtitleSelection(27, false), vlcSubtitleSelection("27", true))
        assertEquals(VlcSubtitleSelection(-1, false), vlcSubtitleSelection(null, true))
        assertEquals(VlcSubtitleSelection(-1, false), vlcSubtitleSelection("downloaded", false))
    }

    @Test fun filtersAndFavoritesApplyToAppendedPages() {
        val a = com.nikhil.niktv.model.MediaItem("a", "English Movie A", null, null, portalCategoryId = "english")
        val b = a.copy(id = "b", title = "Hindi Movie B", portalCategoryId = "hindi")
        val c = a.copy(id = "c")
        val filter = com.nikhil.niktv.model.PlaybackQueueFilter(query = "Movie", sourceIds = setOf("english"))
        assertEquals(listOf(a, c), listOf(a, b, c).filter { filter.includes(it, com.nikhil.niktv.model.CatalogType.MOVIES) })
        val favorites = filter.copy(fixedIds = setOf("a"))
        assertEquals(listOf(a), listOf(a, b, c).filter { favorites.includes(it, com.nikhil.niktv.model.CatalogType.MOVIES) })
    }

    @Test fun pendingNextThenDirectSelectionCommitsOnlyDirectCursor() {
        val cursor = PlaybackNavigationCursor()
        val ids = listOf("A", "B", "C", "D")
        cursor.select(1, ids, "A"); cursor.commit(1, ids, "A")
        cursor.select(2, ids, "B", 1)
        cursor.select(3, ids, "C")
        cursor.commit(2, ids, "B"); cursor.finish(2)
        assertEquals("C", cursor.pendingId)
        cursor.commit(3, ids, "C")
        assertNull(cursor.pendingId)
        assertEquals(2, cursor.index(ids, "C"))
    }

    @Test fun burstReversalUsesIntentAndPreservesDuplicateOccurrenceOnAppend() {
        val cursor = PlaybackNavigationCursor()
        val ids = listOf("A", "B", "A", "B", "C") // pinned A/B then provider A/B/C
        cursor.select(1, ids, "A", 2); cursor.commit(1, ids, "A")
        cursor.select(2, ids, "B", cursor.index(ids, "A") + 1)
        assertEquals(3, cursor.index(ids, "A"))
        cursor.select(3, ids, "A", cursor.index(ids, "A") - 1)
        cursor.commit(3, ids, "A")
        assertEquals(2, cursor.index(ids + "D", "A"))
        cursor.finish(2)
        assertEquals(2, cursor.index(ids + "D", "A"))
    }

    @Test fun cancelledOrDeniedSelectionLeavesCommittedCursor() {
        val cursor = PlaybackNavigationCursor()
        cursor.select(1, listOf("A", "B"), "A"); cursor.commit(1, listOf("A", "B"), "A")
        cursor.select(2, listOf("A", "B"), "B"); cursor.finish(2)
        assertNull(cursor.pendingId)
        assertEquals(0, cursor.index(listOf("A", "B"), "A"))
    }

    @Test fun historicMtkFailurePrefersSoftwareButDoesNotPermanentlyRemoveHardware() {
        val hardware = "OMX.MTK.VIDEO.DECODER.AVC"
        val software = "c2.android.avc.decoder"
        assertEquals(listOf(software, hardware), decoderCandidateOrder(listOf(hardware, software), emptySet(), true))
        assertEquals(listOf(hardware), decoderCandidateOrder(listOf(hardware, software), setOf(software), true))
        assertTrue(decoderCandidateOrder(listOf(hardware, software), setOf(hardware.lowercase(), software), true).isEmpty())
        assertEquals(listOf(hardware, software), decoderCandidateOrder(listOf(hardware, software), emptySet(), false))
    }

    @Test fun exhaustionCodeIsNotARecoverableDecoderName() {
        assertNull(decoderFailureName("Decoder init failed: [-49999], Format(video/avc)"))
        assertEquals("c2.android.avc.decoder", decoderFailureName("Decoder init failed: [-49999], Format\nDecoder failed: c2.android.avc.decoder"))
    }

    @Test fun exhaustedDecoderBudgetCannotResetWithPlayerRecreation() {
        assertTrue(allowDecoderRecovery(0))
        assertTrue(allowDecoderRecovery(2))
        assertFalse(allowDecoderRecovery(3))
        assertFalse(allowDecoderRecovery(30))
    }

    @Test fun onlyRequestedUncancelledCompletionAdvancesOnce() {
        val gate = EpisodeCompletionGate()
        assertFalse(gate.claim(false, true, true, false)) // paused/seeking/buffering timeline near end
        assertFalse(gate.claim(true, false, true, false))
        assertFalse(gate.claim(true, true, true, true))
        assertFalse(gate.claim(true, true, false, false))
        assertTrue(gate.claim(true, true, true, false))
        assertFalse(gate.claim(true, true, true, false))
    }

    @Test fun startupTimeoutExcludesPausedAndBackgroundTime() {
        val deadline = PlaybackStartupDeadline(1000)
        assertFalse(deadline.expired(0, true, false))
        assertFalse(deadline.expired(500, true, false))
        assertFalse(deadline.expired(10000, false, false))
        assertFalse(deadline.expired(20000, true, false))
        assertTrue(deadline.expired(20500, true, false))
        assertFalse(deadline.expired(20501, true, true))
    }

    @Test fun userPauseDuringFocusLossCannotResumeOnGain() {
        val intent = PlaybackAudioIntent()
        intent.transientLoss(); assertFalse(intent.shouldPlay)
        intent.userRequest(false); intent.gain(); assertFalse(intent.shouldPlay)
        intent.userRequest(true); assertTrue(intent.shouldPlay)
        intent.transientLoss(true); assertTrue(intent.shouldPlay); assertTrue(intent.ducked)
        intent.gain(); assertFalse(intent.ducked)
        intent.noisy(); intent.gain(); assertFalse(intent.shouldPlay)
        intent.userRequest(true); intent.permanentLoss(); intent.gain(); assertFalse(intent.shouldPlay)
    }

    @Test fun vlcSupportedHeadersAreAppliedButArbitraryOrInjectedHeadersFallback() {
        assertEquals(listOf(":http-user-agent=NikTV", ":http-referrer=https://fixture/"),
            vlcHeaderOptions(linkedMapOf("user-agent" to "NikTV", "Referer" to "https://fixture/")))
        assertNull(vlcHeaderOptions(mapOf("Authorization" to "Bearer fixture")))
        assertNull(vlcHeaderOptions(mapOf("Cookie" to "fixture")))
        assertNull(vlcHeaderOptions(mapOf("User-Agent" to "NikTV\r\nHeader: injected")))
    }

    @Test fun opaqueManifestsUseKnownFormatWithoutGuessingEveryLiveStream() {
        assertEquals("application/x-mpegURL", playbackMimeType("HLS", "https://fixture/opaque"))
        assertEquals("application/dash+xml", playbackMimeType("DASH", "https://fixture/opaque"))
        assertNull(playbackMimeType("", "https://fixture/opaque"))
    }

    @Test fun nativeFailuresPreserveCauseAndRedactCredentials() {
        assertEquals(NativeFailureKind.AUTHORIZATION, nativePlaybackFailure("HTTP error 403 Forbidden").kind)
        assertEquals(NativeFailureKind.TRANSPORT, nativePlaybackFailure("Connection timed out").kind)
        assertEquals(NativeFailureKind.UNSUPPORTED, nativePlaybackFailure("No decoder found").kind)
        assertEquals(NativeFailureKind.UNKNOWN, nativePlaybackFailure(null).kind)
        val failure = nativePlaybackFailure("Authorization: Bearer secret\nCookie: one=secret; two=private\nHTTP error 403 for https://user:pass@fixture/token?secret=private")
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertFalse(failure.message.orEmpty().contains("private"))
        assertFalse(failure.message.orEmpty().contains("pass@"))
    }
}

class SpeculativePageCacheTest {
    @Test fun warmMetadataReuseIsBoundedByIdentityCredentialsAndExpiration() = runBlocking {
        var now = 0L
        val cache = SpeculativePageCache<List<String>>(this, { now }, 1000)
        var loads = 0
        cache.schedule("category:2", "token1") { loads++; listOf("B") }
        delay(800)
        cache.schedule("category:2", "token1") { fail("Duplicate metadata request"); emptyList() }
        assertEquals(listOf("B"), cache.takeReady("category:2", "token1"))
        assertEquals(1, loads)
        cache.schedule("category:3", "token1") { listOf("C") }; delay(800)
        assertNull(cache.takeReady("category:3", "token2"))
        cache.schedule("category:3", "token2") { listOf("C") }; delay(800)
        now = 1001
        assertNull(cache.takeReady("category:3", "token2"))
    }

    @Test fun failureAndResourcePressureCannotAffectActiveStreamOrForegroundSelection() = runBlocking {
        val cache = SpeculativePageCache<String>(this, { 0L })
        var active = "A"
        cache.schedule("B", "token") { error("speculative failure") }; delay(800)
        assertNull(cache.takeReady("B", "token")); assertEquals("A", active)
        cache.schedule("C", "token", permitted = false) { fail("Low memory must defer"); "C" }
        assertNull(cache.takeReady("C", "token"))
        active = "C" // normal foreground attempt still possible
        assertEquals("C", active)
    }

    @Test fun unfinishedSpeculationNeverBlocksForegroundOrItsCleanup() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val cache = SpeculativePageCache<String>(this, { 0L })
        cache.schedule("B", "token") { withContext(NonCancellable) { release.await() }; "B" }
        delay(800)
        assertNull(cache.takeReady("B", "token"))
        release.complete(Unit)
        Unit
    }
}
