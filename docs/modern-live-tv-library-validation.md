# Modern Live TV, My List and live playback validation

Date: 2026-10-21. Work remains uncommitted; earlier pending series/title-matching changes are preserved.

## Navigation and presentation

- Discovery now hosts Live TV (category hub and channel collection) and My List inside its own adaptive top navigation. The original sidebar is retained when the user explicitly chooses the original experience.
- Existing provider catalogue, pagination, local programme search, filters, pins, parental authorization, guide scheduler, refresh actions and player queue remain the owners of their functionality. Browsing does not create a new media stream.
- The selected Live TV/My List navigation action has a directional route into content. Phone labels scroll horizontally rather than squeezing five labels into a fixed row.
- My List uses the same stable focus viewport as discovery rows, so focusing an already-visible tile does not shift the list toward the TV pivot.
- My List retains IPTV/TMDB item dispatch, removal confirmation, saved scroll/focus state, and Downloads/Storage access. Saved channels use the compact Live TV presentation and already-cached guide data; manual guide refresh stays in the long-press menu.
- Header Back follows the same category/library return policy as remote Back. Selecting an already active library root does not restart loading.

## Playback findings and fixes

- Fire TV baseline mpv: media clock froze near 0.192 seconds while cached input grew from about 39 to 99 seconds. Picture and burned-in programme countdown remained unchanged. This was not input starvation in that run.
- Changing AAudio to AudioTrack did not help (clock froze near 0.3 seconds on two channels). The experimental output change was reverted; audio/buffer defaults remain unchanged.
- The shared Media3/mpv watchdog had been given the synthetic on-screen elapsed clock. It now receives the engine's real media position and respects audio-focus suppression. Sliding Media3 window offsets are included so manifest-window movement cannot invent progress or cause false stalls. The on-screen elapsed display keeps its existing semantics. See [Media3 timeline coordinate documentation](https://developer.android.com/reference/androidx/media3/common/Timeline.Window#getPositionInFirstPeriodMs()).
- VLC recovery now checks its audio-focus owner as well, so transient focus loss does not look like a stalled stream or cause a reconnect.
- A bounded reconnect that also freezes in READY now exposes Retry/Choose player, rather than remaining frozen indefinitely. User pauses/backgrounding still reset observation, and healthy playback rearms the existing budget.
- A bounded local mpv hardware-to-software decoder experiment also failed to restore progress on this Fire TV. It was removed before delivery, rather than imposing an unproven runtime reconfiguration or extra polling. The existing decoder, audio, buffer and fifteen-second health-sampling defaults are preserved.
- Auto mode may hand off a failed live Media3 decoder to VLC when headers and source configuration are compatible. Explicit player choices, VOD/offline media, authorization/network failures, user pauses, and auxiliary YouTube sources do not trigger this handoff.
- mpv native commands/teardown now use their own single ordered worker. A stalled mpv destructor cannot block VLC construction or preparation on its separate worker. This adds one bounded executor, not per-channel threads or speculative decoders. Retiring-stream stop/detach/destruction stages are logged without URLs.
- ExoPlayer/Flow state logs identify the engine; low-frequency counters report real position, buffered duration, rendered/dropped frames and suppression without logging URLs or credentials.

## Device evidence

Fire TV AFTKRT, Wio, one active stream at a time:

- Mission Impossible resolved in 91 ms in the baseline and 135 ms in the candidate. Spider Man navigation resolved in 139 ms. These are URL-resolution timings, not end-to-end first-frame timings.
- VLC native creation: 204 ms; native cleanup observed at 160 ms. Seven fifteen-second playback samples showed approximately 371–381 frames per sample (about 25 fps), zero lost frames, no reported buffering, and an advancing media clock.
- ExoPlayer fetched ten-second HLS segments in roughly 0.3–2.2 seconds and accumulated about 29.5 seconds of media, but reached decoder/no-progress failures. A native Android software AVC failure reported capability limits for 1080p. Flow reproduced the same class of failure on Spider Man. Buffer enlargement is not justified by these runs.
- Candidate memory sample while VLC played after engine changes: about 245 MiB PSS / 285 MiB RSS. This single sample is not a leak or soak test.
- Original and modern Live TV screens were captured; the modern header persists through the channel collection. My List and D-pad entry were exercised. No app data was cleared.

Final tablet acceptance: Live TV and its collection kept the discovery header; long-press retained Force fetch programme / My List / Pin actions; My List header Back returned to the exact originating collection. Tablet D-pad category navigation was exercised. Phone navigation was reviewed in code, but a physical phone test was not performed in this run.

Clean-process Auto acceptance: a Media3 timeout switched to VLC at 17:44:39; subsequent samples showed 373, 377 and 378 frames per fifteen seconds, zero lost frames and an advancing media clock. This recovery took roughly eighteen seconds from initial resolution; choosing VLC directly avoids that failed-decoder startup cost. The original MPV preference was restored after testing.

Final worker-isolation acceptance: mpv reported its retiring stream stopped at 18:05:09 and then hung detaching video output. VLC still constructed on its own worker in 27 ms at 18:05:16 and rendered the live stream, without restarting NikTV. This reproduced escape path was blocked before isolation.

The final mpv failure path displayed its actionable Retry/Choose player dialog after one bounded reconnect instead of silently remaining frozen. The decoder experiment did not restore the native clock and was removed.

## Verification

JDK: C:/Program Files/Java/jdk-17. Gradle options used: `-PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 -Dorg.gradle.jvmargs="-Xmx5g -Dfile.encoding=UTF-8"`.

Focused unit suites: DiscoveryLibraryRoutingTest, LivePlaybackPolicyTest, LiveStreamRecoveryTest, VlcPlaybackPolicyTest, MpvPlaybackPolicyTest, FlowInspiredPlaybackTest, LatestPlaybackRequestTest, PlaybackSessionPolicyTest, PlayerTransportPolicyTest, ReusablePlaybackEnginePoolTest, LiveChannelGuideUpdateTest, TileFocusViewportTest and NativePlaybackWorkerTest.

`assembleDebug` passed, including vital lint. Final focused regression result: 86 tests passed, zero failures/errors. Full `:app:lintDebug` failed with 26 existing errors (WrongConstant, NonObservableLocale, RememberInComposition, RestrictedApi and UnsafeOptInUsageError); it is not a pass. No lint checks were suppressed. Captures, device logs and APKs remain outside source control.

## Remaining limits

A short device test cannot establish interruption-free playback across all codecs, channels, network conditions and firmware versions. Native decoder failures remain possible; explicit unsupported engine selections expose recovery choices. Long-duration soak, resource-pressure and multi-profile provider coverage are still required before a production release. Provider/network stalls cannot be eliminated by UI changes or larger buffers.

### Reproduction commands

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
.\gradlew.bat :app:testDebugUnitTest --tests '*DiscoveryLibraryRoutingTest' --tests '*LivePlaybackPolicyTest' --tests '*LiveStreamRecoveryTest' --tests '*VlcPlaybackPolicyTest' --tests '*MpvPlaybackPolicyTest' --tests '*FlowInspiredPlaybackTest' --tests '*LatestPlaybackRequestTest' --tests '*PlaybackSessionPolicyTest' --tests '*PlayerTransportPolicyTest' --tests '*ReusablePlaybackEnginePoolTest' --tests '*LiveChannelGuideUpdateTest' --tests '*TileFocusViewportTest' --tests '*NativePlaybackWorkerTest' :app:assembleDebug -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx5g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:lintDebug -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx5g -Dfile.encoding=UTF-8'
```

## Delivery

Verified builds installed on Fire TV (armeabi-v7a) and Xiaomi Pad 5 (arm64-v8a), preserving data. Final Fire TV My List focus check kept its heading and first tile visible. Playback was stopped; original MPV engine selection and audio mute/volume settings were restored. Samsung was not interrupted or updated in this task. No commit, push or release was made.

Changed implementation files for this task: discovery/DiscoveryLibraryScreen.kt, discovery/DiscoveryTopNavigation.kt, ui/CatalogScreen.kt, ui/FavoritesScreen.kt, ui/LivePlaybackPolicy.kt, ui/LiveStreamRecovery.kt, ui/PlayerScreen.kt, ui/VlcPlayerScreen.kt, ui/NativePlaybackWorker.kt and ui/MpvPlayerAdapter.kt (under app/src/main/java/com/nikhil/niktv). New/updated regression tests cover route selection, window-normalized real progress, exhausted recovery, explicit-vs-Auto engine policy, and a blocked native worker. Earlier pending series/title matching edits are preserved separately.


## Follow-up: library menu and collection search (2026-10-21)

- Replaced the local menu function reference with a state-aware lambda. Tablet reproduction showed a menu tab could focus but cease activating its destination after earlier navigation. Repeated Live TV → My List → Live TV now changes destinations correctly by touch and D-pad.
- Opening a catalogue clears secondary navigation flags in one state transition, including when the selected catalogue type already matches the underlying Live TV page. Added `CatalogNavigationTest` for My List → already-selected Live TV and preservation of saved/recent media when closing overlays.
- Modern library entry focuses the selected menu tab; legacy My List and Live TV hub auto-entry effects no longer take initial focus into the first item. Original UI entry behavior remains enabled.
- IPTV full collections now have a compact search icon beside Refresh. Only activation expands the fixed-height editor and opens the keyboard. Expansion animates horizontally; Back or Close collapses it and clears the filter. Search activation claims collection entry focus so delayed initial results cannot steal editing focus. The provider category presentation reuses this control.
- Xiaomi Pad 5 (`6889f3db`): installed with `adb install -r`, retained data. Verified touch Live TV/My List transitions; D-pad tab selection and retention on both destinations; Search/All opens collapsed without IME; focusing search via D-pad keeps `mInputShown=false`; OK and touch open editing with `mInputShown=true`; local `Diana` search returns one matching title; Back restores the search icon focus and all loaded results; Close works by touch. Inspected expanded search screenshot for alignment and fixed-height layout.
- Final checks: `:app:testDebugUnitTest --tests '*DiscoveryLibraryRoutingTest' --tests '*CatalogNavigationTest' :app:assembleDebug -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx5g -Dfile.encoding=UTF-8'`: **BUILD SUCCESSFUL**, 4 focused tests, zero failures/errors, vital lint passed. Full lint and the complete unit suite were not rerun for this focused UI follow-up. Log: `D:/workspace/niktv-playback-tests/tablet-nav-verified-build.log`.
- Fire TV was neither operated nor updated during this follow-up. No playback-engine changes or media playback tests were required. Changes remain uncommitted.


## Compact full collections and focus clipping follow-up

- Fire TV screenshot reproduced top-edge clipping of a scaled first-row poster in the full IPTV collection. Scrolling can consume outer grid padding, so focus expansion now has reserved vertical space inside each focusable poster cell. The poster itself remains 2:3 with unchanged outline/progress treatment. Cell spacing compensates for the added inset.
- Full collections reuse the landing rails' `discoveryPosterWidth` calculation (and phone poster sizing), using fixed-size grid cells rather than stretching six posters across Fire TV. At 960×540 dp the shared width is 105.33 dp, fitting eight complete posters in the collection. Existing landing layout calculation is preserved through the shared helper.
- Grid D-pad movement uses the existing single cancellable `DiscoveryScrollCoordinator`, with the landing screen's 240 ms FastOutSlowIn curve. Composed destinations focus immediately; obsolete moves/focus transfers are canceled on new input. Horizontal moves retain vertical alignment unless an edge needs revealing. Touch remains native scrolling, and reduced-motion settings disable extended scroll/scale animation. Pagination, local filtering, popup actions and playback callbacks are retained.
- Targeted command: `:app:testDebugUnitTest --tests '*DiscoveryPosterGeometryTest' --tests '*DiscoveryMotionTest' --tests '*DiscoveryLibraryRoutingTest' --tests '*CatalogNavigationTest' :app:assembleDebug -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx5g -Dfile.encoding=UTF-8'`: **BUILD SUCCESSFUL in 2m14s**, 12 tests, zero failures/errors; vital lint passed. Full lint/suite not rerun. Log: `D:/workspace/niktv-playback-tests/compact-grid-build.log`. No timed frame-performance claim is made for this follow-up.

- Final compact-grid APKs installed successfully with `adb install -r` on Fire TV (`192.168.2.19:5555`, armeabi-v7a) and Samsung (`R3GL10CE4LE`, arm64-v8a). App data retained.

- Final Fire TV visual acceptance: opened Wio → Movies → HINDI | OLD COLLECTION → Search/All. Verified eight full posters across, the complete outline on a focused first-row middle poster, then Down/Down/Up/Up/Left bursts back to the first poster with top and left outline intact. A further Down aligned the second row beneath the header with its entire focus border visible. Auto-pagination increased 42 → 56 titles during navigation. Evidence: `fire-compact-grid-top-border.png`, `fire-compact-grid-return-edge.png`, `fire-compact-grid-scrolled-border.png` under `D:/workspace/niktv-playback-tests`. Samsung installation confirmed; its post-install touch/portrait UI was not revalidated in this follow-up. No playback was started.

## Mobile Live TV layout follow-up

- Phones use the established Live TV category/channel browser inside the modern navigation shell, with the modern bottom bar retained.
- Suppress the browser's duplicate phone header. Tablet/TV retain the modern Live TV feed; My List is unchanged.
- Existing category configuration, channel grid/list, programme enrichment, pagination and playback callbacks remain owned by the original browser.
- Mobile My List also retains its established grouped favorites grid/card design inside modern chrome, including existing open/remove actions and the downloads/storage switcher. Tablet/TV keep discovery cards and feeds.

## Mobile favorites hero and shared header

- Mobile Live TV/My List reuse the Home phone header (logo, search, account and options); Live TV content is unchanged.
- My List keeps its existing grouped grid below a Home-style hero, selecting only current-profile movie/series favorites. Sensitive titles, channels and episodes are excluded from ambient artwork.
- Presentation reads the existing discovery metadata cache off the UI thread and falls back to saved artwork; no background provider search or stream preparation.
- Eight-second rotation pauses for scrolling/offscreen hero, backgrounding, focus, touch, popups and reduced-motion preference. Manual previous/next and swiping reuse Home controls.
- Follow-up: mobile favorites hero now spans the screen with top-aligned artwork and header overlay, explicit top/bottom fades, left-aligned title/actions, no redundant My List subtitle, and compact category-style saved-group headings/counts.

- Final mobile navigation order: Home, Movies, Series, Live TV, My List. Primary hero action bounds verified identical on Samsung: Home Play and My List Open both [458,1620][983,1800]. Saved hero checkmark opens themed Keep/Remove confirmation; Keep tested without changing favorites. Artwork uses fitted portrait posters with four-edge fades and a small supporting title.
