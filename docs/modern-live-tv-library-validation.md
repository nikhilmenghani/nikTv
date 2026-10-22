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

## Modern-only navigation and mobile glass controls

- Mobile headers show a profile initial and an explicit profile chooser. Opening/canceling the chooser preserves the current session; switching delegates to existing profile loading.
- Phone controls use squarish geometry and the existing bounded cached-artwork glass texture, with translucent fallback under resource pressure. Continuous screen blur and new rendering dependencies are not used.
- Mobile Live TV omits its redundant title/options row; the existing Live TV options dialog is available under the header Options menu.
- Options keeps contextual customization, Offline and Settings. Duplicate destination links, original-UI switch, display toggle and numbered trial labels have been removed. Movie/series root callbacks now resolve to modern discovery, while existing category, search, download, playback and settings owners remain available.
- Stored old-layout opt-outs no longer select a retired experience. Added regression checks for restored preferences and modern catalog root routing.
- Validation: 46 targeted tests passed across discovery routing/feed/layout, catalog navigation/back behavior and profile-store dispatch; debug APK builds passed. Phone UI inspection covered the initial profile button, simplified menu and relocated Live TV options; final profile-dialog recheck follows installation. Fire TV has not been operated or updated for this change.
- Follow-up validation: Samsung profile chooser opened with Current marker; Close retained the active profile. Glass controls were corrected to a single measured outline, equal 48dp header controls, and matched category button sizes. Final blur mapping uses shared un-clipped hero root coordinates, preserves portrait aspect ratio, mirrors fit/crop/alignment, and has no per-button whole-artwork fallback. Five artwork tests (including three new mapping/aspect regressions) plus 13 navigation/layout/routing tests passed; debug build and installs on Samsung/Poco passed. Home and My List were visually rechecked on Samsung.

## Trailer loading and initial controls

- Trailer lookup uses a compact cancellable progress card; the fullscreen host uses a 160dp indeterminate progress bar until Playing rather than a full-width loading text strip. Startup timeout now covers an API-ready player that never starts playback.
- Native controls remain hidden until deliberate touch or fresh remote input. Repeated launch-key events are consumed without revealing controls or pausing. WebView taps are handled by native chrome instead of being forwarded into iframe controls. Error fallback retains Open with.
- Captions default off, with a native CC toggle and module-state reapplication after YouTube API changes. The implementation does not alter captions burned into video pixels.
- Validation: `:app:testDebugUnitTest --tests '*TrailerPlayerActivityTest' --tests '*PlaybackSeekTargetTest' :app:assembleDebug` passed (6 tests). `node tools/test-trailer-player.cjs` passed against the actual embedded script, covering initial/late caption modules, opt-in/out, no module callback loops and preserved playback/seek dispatch.
- Updated build installed on Samsung and Poco. Poco launched a Moana trailer through Watch trailer and reached Playing with native controls hidden; screenshot captured. Captions toggle verified in deterministic tests; the touch-toggle device capture exceeded the auto-hide interval, so it is not claimed as verified on device. The test trailer was closed and music volume restored to its original 8/25. Fire TV unchanged.

## Stable phone discovery loading layout

- Reproduced Poco Series first-entry movement: the rank and carousel were added after loading, while a premature unavailable message was removed, shifting actions and the first rail.
- Rank/progress, title/logo, metadata and carousel now reserve fixed slots from the first frame. Neutral title/metadata placeholders replace content in place; controls remain disabled until their content is available. Ranked poster skeletons reserve the same number gutter and poster footprint as loaded tiles.
- Unavailable messaging waits for a completed feed/error result instead of showing during initial binding.
- Validation: debug APK plus 14 focused phone layout/navigation/poster-geometry tests passed. Installed on Poco and Samsung. Captured early/loaded Home, Movies and Series on Poco. Loaded Play bounds were identical `[361,1140][720,1264]`; category bounds were identical `[161,1295][530,1418]` and `[551,1295][920,1418]` on all three destinations. Cached transitions were visually stable; this was a layout check, not a frame-time benchmark. No user data/cache was cleared. Fire TV unchanged.


### Tablet hero loading geometry (2026-10-22)

- Tested Home → Movies → Series on Xiaomi Pad 5 (`6889f3db`) without clearing personal data. Cached transitions did not reproduce a button shift: Play `[68,850][237,952]`, IPTV categories `[504,850][765,952]`, and TMDB categories `[786,850][1066,952]` remained identical across the captured early/loaded states. These are cached transitions, not a controlled slow-network test.
- Inspection found the separate tablet/TV hero still conditionally inserted loading, rank, metadata, synopsis and carousel layout. Landing heroes now reserve rank, two-line metadata/synopsis and carousel space from first composition; loading placeholders replace content in place. Empty carousel buttons remain disabled until usable. Full detail synopsis remains variable length.
- Fire TV was left untouched. Tablet reinstall and post-install validation results follow below.
- Focused verification: `:app:testDebugUnitTest --tests '*DiscoveryPhoneLayoutTest' --tests '*DiscoveryNavigationLayoutTest' --tests '*DiscoveryPosterGeometryTest' :app:assembleDebug --no-daemon --max-workers=1` passed, 14 tests / 0 failures. Final build successful in 50 seconds. `git diff --check` passed; source credential scan reported 0 findings. No full lint or performance benchmark was run for this layout-only change.
- Installed using `adb -s 6889f3db install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (Success), retaining data. Rechecked all three tab transitions after installation. Initial pass confirmed unchanged button bounds across all six early/loaded captures; visual review prompted additional two-line synopsis height allowance for font metrics.


### My List landscape hero (2026-10-22)

- Tablet/TV My List now reuses Home's landscape hero and the same profile-isolated favorites hero controller as phones: saved movies/series, eight-second idle rotation, cache-only public metadata, guarded sensitive-content exclusion, manual carousel, Open / In My List / Info. Existing collection rows, filters, local search, sort and View all remain below. Phone rendering remains unchanged.
- Cached landscape artwork is preferred; a saved item's poster can provide the background if no backdrop is cached. No provider matching or media stream is started by rotation. Lifecycle, scrolling, focus, touch and remove-confirmation states pause rotation.
- On Xiaomi Pad 5, verified the Friends hero and manually navigating between the two eligible saved series. D-pad entry stayed on My List; Down entered Open, another Down reached the saved row, Up returned to the selected top tab from the hero. Testing found Up from the first saved row could target an uncomposed hero; changed that path to reveal hero item 0 with the existing cancellable scroll coordinator before requesting Open. Final validation follows below.
- Fire TV was not touched or installed during this change. TV shares the landscape implementation but still needs actual Fire TV validation.
- Final focused checks: `:app:testDebugUnitTest --tests '*DiscoveryLibraryFeedTest' --tests '*DiscoveryLibraryRoutingTest' --tests '*DiscoveryNavigationLayoutTest' --tests '*DiscoveryPhoneLayoutTest' :app:assembleDebug --no-daemon --max-workers=1` passed (21 tests, 0 failures; build successful in 1m 7s). `git diff --check` passed and source credential scan had 0 findings. No full lint/frame benchmark was run for this presentation change.
- Reinstalled on tablet with `adb -s 6889f3db install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (Success). Evidence: `D:/workspace/niktv-playback-tests/tablet-mylist-wide-hero.png`. All edits remain uncommitted.
- Final tablet D-pad verification passed: My List tab → Down → Open → Down → first saved tile → Up → Open. Manual Next changed Friends to Taarak Mehta; no media playback was started and no favorites were removed.
