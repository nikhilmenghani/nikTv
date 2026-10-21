# NikTV 2 trial verification

Date: 2026-10-19. Implementation is an additive trial, not a replacement for classic browsing.

## Source changes

- `discovery/DiscoveryModels.kt`: public presentation records, strict released-date policy, hero policy, profile-safe progress and unique season/episode matching.
- `discovery/DiscoveryRepository.kt`, `DiscoveryCache.kt`, `DiscoveryHttp.kt`, `DiscoveryDataSource.kt`: cancellable metadata-only requests, shared request limits, bounded stale-first disk cache and injectable fixtures.
- `discovery/DiscoveryViewModel.kt`: isolated trial preferences, immutable state, cancellation and profile/detail/season generation guards.
- `discovery/DiscoveryUi.kt`, `DiscoveryMotion.kt`, `DiscoveryArtwork.kt`: responsive hero, compact rows, full details, input/focus restoration, cancellable scrolling and bounded shared-loader prefetch.
- `CatalogScreen.kt`, `LiveTvBrowseComponents.kt`, `ModernTileBrowseScreen.kt`, `NikTvApp.kt`: additive destinations, saved-state ownership and existing action callbacks.
- `NikTvViewModel.kt`: explicit episode resolution and unresolved TMDB My List dispatch; existing parental checks and playback paths remain in use.
- `TmdbClient.kt`, `AppStorage.kt`: optional compatible metadata, asynchronous discovery transport and existing temporary-cache reporting/clearing.

No playback engine, stream buffer, screensaver or background IPTV-matching implementation was added.

## Commands

Run in the repository root. Common Gradle flags:

```powershell
$flags = @('-PperformanceBuild=true', '--no-daemon', '-PskipLocalGithubToken=true', '--max-workers=1', '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8')
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug @flags
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true @flags
$adb = 'C:/platform-tools/adb.exe'
foreach ($target in @(@('6889f3db','arm64-v8a'), @('192.168.2.19:5555','armeabi-v7a'))) {
    & $adb -s $target[0] install -r "app/build-regression/outputs/apk/debug/app-$($target[1])-debug.apk"
    & $adb -s $target[0] install -r app/build-regression/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    & $adb -s $target[0] shell input keyevent 224
    & $adb -s $target[0] shell am instrument -w -r -e class com.nikhil.niktv.ui.DiscoveryNavigationDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
}
# Produce normal APKs last: switching the Gradle build directory can clean prior outputs.
.\gradlew.bat :app:assembleDebug @flags
```

Build preparation was inspected before execution. Integrated Flow preparation writes its embedded integration/splash resources; SmartTube preparation builds embedded outputs. No integrated vendor-source changes are part of this trial.

## Results and limits

Raw artifacts are kept outside Git in `D:\workspace\niktv-playback-tests`; captures and personal device data are not committed.

- Baseline commit: `165d3460`. Only pre-existing work and the planning document were committed. No push; discovery implementation remains uncommitted.
- Final complete app unit suite: **492 tests, zero failures/errors/skips**, including **33 discovery tests**. `discovery-render-delivery.log` records the command above: unit testing and `:app:assembleDebug` succeeded; the overall command failed at full lint.
- Final standalone normal APK assembly passed in 2m25s (`discovery-final-apk-build.log`). Standard ARM64, ARM32 and universal APKs are present under `app/build/outputs/apk/debug/`. The normal build with all final app changes was installed on tablet, Fire TV and Samsung; Samsung disconnected afterward. Later test-harness-only edits do not change the installed app code.
- Required full lint: **25 errors outside the new discovery package**, zero new discovery-package errors. The same error locations were checked against baseline `165d3460`. Existing categories: 16 `RememberInComposition`, four `NonObservableLocale`, three `RestrictedApi`, one `UnsafeOptInUsageError`, one `WrongConstant`. No lint baseline/suppression was added. The last refresh is `discovery-final-lint.log`; report: `app/build/reports/lint-results-debug.xml`.
- Isolated fixture APK and Android-test APK builds passed. Final fixture packaging logs: `discovery-fixture-final-package.log`, `discovery-fixture-order-build.log`.
- Final source credential scans: zero tracked and untracked findings. `git diff --check` passed. No generated captures, secrets or vendor-source edits are included.

### Device coverage

| Device | Final coverage | Result |
| --- | --- | --- |
| Xiaomi Pad 5, `6889f3db` | D-pad header → Play → Info/Back, row selection/restoration, season/episode identity, touch carousel/swipe, accidental-action guards, visible-poster timing | **3/3 fixtures pass**, `discovery-tablet-verified-instrumented.log` |
| Fire TV, `192.168.2.19:5555` | Same remote paths, episode identity and warm visible-poster timing; touchscreen-only assertions are inapplicable on this device | **3/3 fixtures pass**, `discovery-firetv-verified-instrumented.log` |
| Samsung | Reconnected physical SM-S948W: touch carousel/swipe, remote navigation, season/episode identity and focus timing | **3/3 fixtures pass**, `discovery-samsung-reconnected-instrumented.log`; phone-specific layout follow-up below |

Real tablet checks also opened Home 2, Movies 2, Series 2 and full movie information, returned with Back, and verified native touch carousel advancement from `1 / 10` to `2 / 10`. Artwork, genres, synopsis and available runtime/certification loaded from TMDB without an explicit provider action. Initial testing found and fixed a lazy cast-section Back crash and Fire TV date API incompatibility.

Tablet portrait at 130% text and reduced motion was captured after rotation settled (`discovery-tablet-portrait-stable.png`). A phone-width portrait layout was additionally emulated with a temporary display density (`discovery-phone-width-emulation.png`); this is **not** a physical Samsung validation. All original density, rotation, font and animation settings were restored in `finally` blocks. Missing artwork is exercised by every synthetic fixture. Release/cache/failure/cancellation/profile/season races use deterministic local fakes, including ignored cancellation and delayed responses.

The touch harness uses Android's native `input tap/swipe` events; assertions still verify visible effects and prohibit accidental playback/favorite changes. Earlier direct `UiAutomation` motion injection was inconsistent. Full-screen entry runs before the explicitly warm tile-focus microbenchmark; the initial cold fixture-window startup was unreliable on Fire TV. A prior warm timing run had a 55 ms outlier, so final warmed timings do not characterize every cold-start condition.

### Measurements

Non-debuggable debug APKs (`-PperformanceBuild=true`), no recording during timing. Fire TV display: 1920×1080 override, 59.94 Hz. Tablet: 2560×1600, 120 Hz. Frame summaries below consistently use the first app window's reported statistics. The script is `tools/measure-discovery-navigation.ps1`.

| Fire TV run | Frames | Reported janky | Median | p95 | p99 | Missed deadlines |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Classic Home baseline | 191 | 27.75% | 11 ms | 23 ms | 48 ms | 0 (legacy reporting) |
| Initial trial, warm, before rendering fix | 141 | 97.16% | 44 ms | 57 ms | 93 ms | 90 |
| Final trial first scroll | 165 | 30.30% | 11 ms | 31 ms | 69 ms | 8 |
| Final trial warm repeat | 181 | 7.73% | 9 ms | 26 ms | 65 ms | 8 |
| Final trial third cycle | 184 | 4.35% | 8 ms | 13 ms | 44 ms | 7 |
| Final trial remote sequence | 342 | 8.19% | 8 ms | 23 ms | 38 ms | 20 |

The real regression was full-display gradient overdraw plus shadows on unfocused posters. Static gradients are now baked once on a background dispatcher into an opaque, shared-loader-cached backdrop. Only the focused poster has a small shadow. The prepared-artwork crossfade remains. The three final scroll cycles used the same four-swipe input sequence as the baseline; classic/trial layout and artwork differ, and warming differs, so these are **not** controlled proof of a speedup over classic Home. They do demonstrate the large improvement over the initial trial rendering on the same device.

Final synthetic visible-item focus, 20 moves each: Fire TV p50 **5 ms**, p95/max **8 ms** (two frames ≈33.37 ms); tablet p50 **6 ms**, p95/max **8 ms** (two frames ≈16.67 ms). This measures input submission to the focus callback with missing-artwork fixtures, not physical remote latency or first artwork readiness. Final real tablet touch scroll: 228 frames, median **10 ms**, p95 **18 ms**, p99 **30 ms**, reported jank **9.21%**.

Fire TV total PSS over the final three scroll cycles: **251,569 → 249,295 → 239,139 KiB**. Classic baseline: 179,598 KiB. The trial uses more memory for hero/artwork textures and caches; this short run shows no monotonic growth, not proof of long-term stability. Metadata disk storage remains independently capped at 10 MiB.

The retained frame-stat samples include one **62.38 ms** main-thread interval in the second warm scroll run; third-cycle maximum was 37.62 ms, remote maximum 38.09 ms, tablet maximum 12.24 ms. These intervals are `SyncQueued − HandleInputStart`, limited by Android's retained frame buffer. Without a stack trace they cannot be attributed specifically to animation. The >50 ms goal is **not established for every run**. GPU percentile reporting in the initial trial contained invalid 4950 ms values and is not used as a reliable absolute GPU duration.

Separate nine-second qualitative recordings: `discovery-firetv-classic.mp4` and `discovery-firetv-trial.mp4`. Captures were taken outside timing runs. Useful screenshots: `discovery-firetv-shaded-current.png`, `discovery-tablet-final-shaded.png`, portrait/emulated-phone captures above. Artwork/title may change between sequential screenshot and XML capture because idle rotation remains enabled.

### Remaining validation limits

- Samsung's revised phone header is installed and destination visibility/touch dispatch were verified at 130% text in both orientations. A repeated portrait run exposed intermittent D-pad focus loss between content rows; this remains unresolved. Final portrait 2/3 and landscape 3/3 are recorded below, rather than presented as a clean combined run.
- Isolated fixture packages were removed from tablet, Fire TV and Samsung after validation; normal NikTV and personal data were preserved.
- Real private-provider Play/download, parental PIN entry, unresolved My List playback, variants and actual episode/resume behavior were not newly exercised against provider streams in this UI task. Existing callbacks and unit coverage are retained; fixtures verify intent/identity and absence of accidental actions.
- Slow/failing metadata and stale cache behavior are deterministic fake/cache tests, not a device-wide network-shaping experiment. Cold artwork readiness is not separately timed.
- Long sessions, constrained-memory devices and all larger-font combinations need broader acceptance testing. Full lint remains failed until its unrelated baseline errors are fixed.

The isolated fixtures use synthetic metadata and playback callbacks. They do not authenticate to a private provider, open a stream, change personal favorites or download media. Real private-provider playback/download and parental authentication are not claimed as newly device-tested by these fixtures.

Frame statistics compare different classic/trial layouts and artwork sets. They are useful observations, not proof that a particular animation caused or eliminated a missed frame. Screen recording is disabled during timed runs. A short test cannot establish long-term memory behavior or promise zero interruptions.

## Samsung reconnection follow-up

The paired Samsung wireless endpoint was rediscovered through ADB mDNS and reconnected on 2026-10-19. Initial reconnected fixtures passed **3/3**, with 20 focus samples: p50 **3 ms**, p95/max **12 ms**, 120 Hz. Actual Home 2, Movies 2, Series 2 and full movie details/Back were checked without starting a provider stream.

Two physical-phone layout issues were found and fixed in `DiscoveryUi.kt`: the navigation row now reveals the active tab after surface, width or font-scale changes; short landscape screens use a compact hero with carousel controls beside the action row. The fixes were installed on Samsung. Display preferences were restored after each orientation/font test. Other devices were not reinstalled or interrupted during this follow-up.

Follow-up checks: **38 focused tests passed** (`*Discovery*Test`, including existing episode discovery coverage). APK assembly succeeded within `discovery-samsung-layout-fixes.log`; the command subsequently failed at the same **25 unrelated lint errors**, zero discovery-package errors. The device fixture adds a visible-label-width/search-boundary assertion and omits other-app content if a call or notification takes foreground focus. Isolated fixture build: `discovery-samsung-regression-build.log`.

Phone interaction was stopped when an incoming call and then notification-shade interaction interrupted the enlarged-font recheck. Captures containing those notifications were deleted. The temporary Samsung fixture packages were removed in the background, leaving normal NikTV intact. Built fixture APKs are retained outside Git for the requested short recheck when the phone is free. The subsequent uninterrupted enlarged-font run (`discovery-samsung-large-font-final.log`) passed 2/3: series-tab visibility and focus timing passed, while the carousel touch-advance assertion failed. No failed or interrupted run is counted as a pass.

Final follow-up assembly succeeded (`discovery-samsung-final-build.log`, normal ARM64/ARM32/universal APKs under the standard output directory). Its overall command failed at full lint: the same 25 unrelated errors, zero discovery-package errors. Samsung already has the fixes installed; subsequent edits affected only tests/documentation. No implementation commit or push was made.

## Phone navigation and direct Live TV follow-up

The user requested a distinct phone UI with all destinations visible and direct Live TV access. `DiscoveryUi.kt` now uses smallest device width under 600 dp (excluding TV) for the phone header, including when a phone rotates. A separate utility row contains Back, NikTV 2, Search and Browse. The full-width destination row distributes Home, Movies, Series and Live TV evenly, with minimum 48 dp touch targets and compact horizontal padding. Tablet/TV retain their compact single header and gain a direct Live TV action. Existing `openCatalogType(LIVE_TV)` dispatch is reused; browsing discovery still performs no provider matching and the Live TV collection/player design is preserved.

The isolated fixture now checks all four phone labels without horizontal scrolling, touch switches Home/Movies/Series, and verifies Live TV callback dispatch without opening a private stream. Tab visibility checks use actual rendered labels and account for the phone's separate utility row. Touch surface changes check destination identity rather than assuming remote focus behavior after a touch.

Focused unit tests and isolated APK assembly passed: **38 tests, zero failures/errors**, `discovery-phone-navigation-regression-build.log` (**BUILD SUCCESSFUL in 3m 5s**). Exact command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*Discovery*Test' :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Samsung went offline before either updated fixture APK could install. No display settings were changed during that unsuccessful attempt. Archived fixture APKs and a guarded `verify-phone-navigation.ps1` are outside Git under `D:/workspace/niktv-playback-tests`; the script rejects an unavailable device before any mutation and restores original settings/removes test packages in `finally`. Normal APK assembly succeeded and the current ARM64 APK was archived outside Git as `discovery-phone-navigation-normal-arm64.apk`. The combined build/lint command below failed at full lint after 4m 11s: the same **25 existing errors**, **zero discovery-package errors** (16 RememberInComposition, 4 NonObservableLocale, 3 RestrictedApi, 1 UnsafeOptInUsageError, 1 WrongConstant). Log: `discovery-phone-navigation-normal-build.log`.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Samsung was unavailable at the end of that build attempt; the subsequent installation and device results are recorded below. `git diff --check` passed. No implementation commit or push was made.

## Reconnected Samsung and requested Fire TV installation (2026-10-19)

Samsung reappeared through its paired ADB mDNS identity. The current normal ARM64 APK (`discovery-phone-navigation-normal-arm64.apk`) installed successfully on Samsung. At the user's explicit request, the matching normal ARM32 APK installed successfully on Fire TV (`192.168.2.19:5555`); its package update time was confirmed. Both installs used `adb install -r`, preserving personal data. No provider stream or playback action was opened. The normal Samsung app launches to its intact profile picker. Tablet was not touched during this follow-up.

The fixture needed two corrections: await the fixture window before initial focus assertions, and derive horizontal swipe coordinates from the actual header/hero bounds rather than a fixed display-height percentage. The separate phone header occupies that percentage on short landscape screens. The series assertion now checks loaded season metadata independently of viewport visibility, then presses Down and still requires Season 2 focus, newest episode focus and exact season/episode playback intent. These are test-harness corrections; no additional production-code changes were made during this installation follow-up. Fixture builds passed (`discovery-phone-fixture-geometry-build.log`, 1m 32s; `discovery-phone-season-fixture-build.log`, 32s), using:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest -PregressionBuild=true -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

At 130% text, the geometry-corrected run passed **3/3 portrait**; its landscape run passed navigation/touch and timing but initially failed the offscreen-season assertion. After correcting that assertion, the final logs are:

- `discovery-phone-navigation-rotation-0.log`: **2/3**, an intermittent `Down enters the series row` failure with no focused node after the row transition. Selected-series navigation/episode identity and timing passed. This is an **unresolved focus reliability issue**, not dismissed as another harness error.
- `discovery-phone-navigation-rotation-1.log`: **3/3**, including all four fully visible destinations, Home/Movies/Series touch switching, Live TV callback dispatch, hero controls/swipe, Info/Back restoration, row navigation, series season/episode identity and timing.

Earlier failed diagnostics were preserved as `discovery-phone-navigation-initial-rotation-{0,1}.log` and `discovery-phone-season-viewport-initial.log`. Do not treat a successful repeat as proof that the intermittent portrait focus loss is fixed. There is no claim of a clean 6/6 combined final run. Navigation tests do not establish actual provider stream playback or artwork readiness.

The guarded verification script restored Samsung's original font scale **1.0**, accelerometer rotation **0**, and user rotation **0**. Both temporary regression packages were uninstalled; the normal app remains installed. No implementation commit or push was made.

## Fire TV horizontal-navigation movement

The user reproduced vertical screen movement while navigating Left/Right on the current Home 2 discovery rows. On the normal Fire TV app, four alternating Left/Right inputs repeatedly moved the Trending Series Today heading between **y=350 and y=120 physical pixels**: a **230 px vertical displacement** without changing rows. The before/right/step XML captures are outside Git under `discovery-firetv-horizontal-*.xml`.

The new discovery screen was inheriting Android TV's automatic focus-pivot scrolling while also calling its own vertical spring alignment on every poster focus. `DiscoveryUi.kt` now uses the existing shared `StableTileFocusViewport` minimum-reveal policy for its scrollable hierarchy. Explicit spring alignment happens only when focus enters a different vertical row, including the season selector. Horizontal focus changes still update selection/artwork and scroll their LazyRow to reveal offscreen items; they do not restart the vertical alignment animation. Hero and Up/Down navigation retain their explicit paths. No provider, playback or cache policy changes were made.

The device fixture now traverses eight items right and eight left, then alternates directions four more times, asserting each selected identity and a fixed vertical row heading throughout each move. This covers both lazy-row edges and reversals rather than only adjacent already-visible items. Focused discovery/shared-viewport unit results: **41 tests, zero failures/errors**. Build/device results follow below.

## Fire TV vertical motion and tile popup follow-up (2026-10-19)

Vertical row changes now use one cancellable `DiscoveryScrollCoordinator` for reveal, alignment and focus. Previously explicit navigation and the focus callback could start competing scrolling jobs; the callback now recognizes its active destination. Rapid reversals cancel the old destination and its pending focus work. Row identity, rather than an unstable index, gates focus-entry alignment. This is a targeted coordination fix, not a measured claim of zero stutter.

Discovery posters use the existing shared remote long-press handler, which consumes the held center-key release before opening the menu. The anchored popup now reuses `ModernTileActionsMenu` and the legacy callbacks: related titles, trailers, explicit IPTV match refresh, description, My List, series navigation and activity-specific clear/watched actions where applicable. New Episode favorites retain the episode and parent-series identities. IPTV matching remains explicitly requested, with profile-scoped counts; browsing does not search the provider.

Verification artifacts remain outside Git in `D:/workspace/niktv-playback-tests`:

- `discovery-horizontal-firetv-instrumented.log`: 3/3 passed after correcting a test that tried to traverse beyond a ranked row capped at ten titles. Twenty Left/Right inputs covered both lazy-row edges and reversals without vertical heading movement.
- `discovery-popup-parity-final-build.log`: isolated APK/test assembly and 46 focused unit tests passed. Coverage includes cancellation, same-destination focus callbacks, cache/profile identity and shared focus policy.
- `discovery-popup-final-firetv.log`: 3/4 passed; the repeated favorite-menu hold failed. The test was sending a new hold before the closing native popup window released input, despite restored Compose focus. The harness now waits for the host window to regain focus before sending the next hold.
- `discovery-popup-window-focus-test-build.log`: updated instrumentation APK built successfully.
- `discovery-popup-window-focus-firetv.log`: remaining menu/navigation case passed (1/1, 24.746 seconds), including rapid vertical reversal, remembered row item, release safety, menu parity, explicit match refresh, description return, add/remove favorite and deliberate playback. Together with the unchanged three passing cases, all four cases have passed against the final production source; these are separate runs, not one clean final four-case run.
- `discovery-popup-parity-final-normal-build.log`: normal APK assembly completed; the combined command failed lint with 25 existing errors, 316 warnings and 3 hints. Lint is not a pass. No check was suppressed.

The following commands reproduce these checks using the existing inspected build preparation configuration:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*Discovery*" --tests "*TileFocusViewportTest" --tests "*TmdbAvailabilityCacheTest" :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:assembleDebugAndroidTest -PregressionBuild=true -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

The unit results include NewEpisodeDiscoveryTest and the discovery, shared viewport and availability-cache suites (46 tests total). No new timed frame/memory comparison or provider playback test was performed in this follow-up. Samsung/tablet were not revalidated; the previously documented intermittent Samsung portrait focus issue remains unconfirmed. No implementation commit or push was made.


## Early vertical focus transfer (2026-10-19)

Follow-up: the previous coordinator intentionally deferred a partially visible row's focus until its entire scroll spring completed. `DiscoveryMotion.kt` now starts a destination-scoped child coroutine that observes layout each frame and requests focus as soon as the row fits the viewport, concurrently with the same alignment animation. Fully visible targets still focus immediately. Cancellation propagates to the focus waiter; the same-destination callback does not restart scrolling. Oversized rows retain the post-alignment fallback.

`DiscoveryMotionTest` now supplies a frame clock for the concurrent focus waiter. Two focused cancellation tests passed. New device regression `partiallyVisibleRowReceivesFocusBeforeScrollSettles` constructs a 300 dp viewport with 180 dp rows and verifies focus is accepted while scrolling remains active. On Fire TV this transferred in **191 ms** in one run. This measures the deterministic fixture, not all rows or provider loading, and no matched old/new timing distribution was collected.

Commands/results:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DiscoveryMotionTest' :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
adb -s 192.168.2.19:5555 shell am instrument -w -r -e class com.nikhil.niktv.ui.DiscoveryNavigationDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
```

`discovery-focus-during-reveal-build.log`: build/tests successful (2m7s). `discovery-focus-reveal-normal-build.log`: normal APK assembly successful (2m). `discovery-focus-reveal-firetv.log`: **5/5 passed in one run (41.418s)**, including early focus, rapid reversals, stationary horizontal navigation, activity/series actions, popup release safety and return focus. Logs/APKs remain outside Git under `D:/workspace/niktv-playback-tests`. No full unit suite or lint rerun for this focused follow-up; the previous 25 lint errors remain unresolved. No commit/push, provider stream testing, Samsung or tablet validation in this follow-up.

## Tablet visual Up/Down investigation (2026-10-19)

User still observed an unpolished Up/Down transition on Fire TV. Fire TV was left untouched during this follow-up. Xiaomi Pad 5 (`6889f3db`) was updated to the preceding normal build before reproduction. Actual Wio Home 2 artwork/activity rows were recorded; the recording showed a small reverse displacement during a single downward transition. The previous minimum-reveal policy still permitted implicit vertical relocation to compete with the coordinator. Frame samples were taken separately with recording disabled.

Correction under test: the vertical LazyColumn uses a discovery-specific bring-into-view policy that returns zero implicit movement only while its coordinator owns an active transition. Nested horizontal rows explicitly retain the existing minimum-reveal policy. A composed target receives focus immediately, without waiting for full reveal; an uncomposed destination receives focus when it appears. Cancellation still scopes both alignment and focus transfer to the latest destination. Touch scrolling and ordinary focus reveal outside an explicit transition retain their existing paths.

The targeted device test now verifies immediate composed-row focus and monotonic downward movement, starting with the previous row focused; the discovery integration case checks rapid reversals and horizontal edge navigation. Results follow after device validation.

Tablet results: isolated motion test passed with **12 ms** composed-row focus and monotonic downward positions. The existing combined integration test passed its navigation/rapid-reversal/horizontal-edge checks but failed later at `Manual refresh is explicit and returns to its tile`: a description dialog appeared instead. The failure repeated, including after test-only menu input synchronization; synchronization was reverted because it did not explain the result. Its cause is **unresolved** and this combined case is not a pass. Logs: `discovery-single-scroll-tablet.log`, `discovery-single-scroll-tablet-menu-recheck.log`, `discovery-single-scroll-tablet-menu-synchronized.log`. A separate `remoteRowNavigationAndReversals` entry now reuses the navigation portion without silently weakening the complete menu test.

Actual Home 2 tablet recordings were inspected as frame sheets. The corrected warmed recording no longer showed the small reverse displacement seen before. Separately, with recording disabled, the initial before sample recorded **23/354 missed frames (6.50%), p95 32 ms**. The first after sample was worse (**30/217, 13.82%, p95 40 ms**) and was taken during page reopening from an unverified header focus. A warmed after sample from verified hero-action focus recorded **19/392 (4.85%), p95 22 ms**. These are single UI samples with varying artwork/state, not a controlled statistical performance comparison; they do not establish zero stutter. Raw artifacts are `tablet-updown-{before,after,after-warm}-frames.txt` and `tablet-updown-{before-full,after-full,after-warm}.mp4` outside Git. One recording pull failed transiently; the existing file was subsequently recovered over ADB.

Normal APK assembly succeeded (`discovery-single-scroll-normal-build.log`, 1m58s); regression build and two coordinator unit tests succeeded (`discovery-tablet-single-scroll-build.log`, 3m11s). Only tablet was installed/operated. Fire TV was neither installed nor navigated. Full suite/lint were not rerun for this correction; previously documented lint failures remain. Motion-only device results follow below.

Final targeted device run: `discovery-tablet-motion-only-final.log` **2/2 passed in 12.535s** (immediate focus/no rebound and remote row navigation/reversals). Test APK build succeeded (`discovery-tablet-motion-only-test-build.log`, 46s). Command:

```powershell
adb -s 6889f3db shell am instrument -w -r -e class 'com.nikhil.niktv.ui.DiscoveryNavigationDeviceTest#partiallyVisibleRowFocusIsImmediateAndRevealDoesNotRebound,com.nikhil.niktv.ui.DiscoveryNavigationDeviceTest#remoteRowNavigationAndReversals' com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
```

These passes do not replace the recorded failure of the separate complete popup-action case. Corrected normal APK installed on tablet; temporary regression packages removed and normal NikTV reopened. No Fire TV operations or commit/push.

## Featured carousel controls beside Info

Landscape discovery hero layout now places Previous / current-count / Next immediately after Info in the action row. The previous separate row was bypassed by the action row's intentional Down-to-content handler; compact layouts also had a weighted spacer pushing the controls to the far edge. Portrait retains its separate carousel row to avoid squeezing the actions. This is a layout-only change in `DiscoveryUi.kt`, using the existing focusable actions and carousel callbacks. Suggested manual checks are horizontal reachability, count updates, return to Info, and Down-to-content. The user will perform device testing after installation. No playback/provider behavior changed and no broad test suite is needed for this change.


Normal APK build succeeded: discovery-adjacent-carousel-build.log (1m29s), using :app:assembleDebug and the existing normal-build flags. No device input tests, unit suite or lint rerun for this layout-only change. No commit/push.

## Compact discovery poster text overlay

New UI only: `DiscoveryPosterTile` retains its existing poster widths/aspect ratios, focus treatment and 3 dp red progress bar. Title/subtitle are now inside the bottom of the artwork over a static dark gradient, with typography/padding scaled for the compact posters rather than copied at the original UI's larger physical size. Both text fields wrap instead of using the previous two-line title/one-line subtitle ellipsis. The red bar remains the last overlay and text is inset above it. Missing-artwork cards retain their icon and use the same overlay text. Original UI components were not changed. Device testing is left to the user as requested; build/install result follows.

Normal APK assembly succeeded in 40s (discovery-compact-overlay-build.log). Existing normal assembleDebug command/flags; no unit suite, lint or device navigation tests rerun for this reversible styling change. No commit/push.

## Discovery menu cleanup

Removed redundant Info from discovery card long-press menus (opening the card already opens full details). Replaced generic extra actions with an optional typed Play callback rendered with the standard PlayArrow icon; discovery cards, Continue Watching and New Episodes use it, while legacy callers retain the null default. Updated the existing menu expectation to omit Info and assert its absence. Normal APK build passed in 56s (`discovery-menu-play-icon-build.log`); no instrumentation/unit suite or lint rerun for this small menu presentation change. Device testing left to the user, and Fire TV update installed. No commit/push in this follow-up.

## Cast navigation and actor discovery

New detail pages now retain TMDB person IDs and present cast cards as themed focusable actions. Explicit row navigation includes cast and skips non-focusable supplemental/loading sections; changing rail indexes re-registers their requesters. D-pad Left/Right uses the cast row's normal minimum-reveal policy. Selecting a person opens a themed movie/series grid, with ordinary detail-page actions for each result. Back can return from a selected title to the actor grid, then to the originating cast card. Missing/invalid IDs do not dispatch ambiguous name-based lookups.

Actor data uses TMDB combined cast credits on explicit selection only. It excludes unknown/future dates, sensitive titles and crew-only credits, deduplicates by movie/series identity and bounds results to 60. Public data uses the existing 24-hour metadata TTL, shared two-request concurrency and 10 MiB disk cache. Detail cache keys move to v2 so existing details acquire person IDs; old serialized cast entries remain compatible. No provider search/stream opens from cast browsing. Modal selection/route changes cancel lookups and reject canceled results; existing detail/playback callbacks remain in use.

Focused verification command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DiscoveryActorCreditsTest' --tests '*DiscoveryMotionTest' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Final source build succeeded in 1m5s (`discovery-cast-navigation-final-build.log`), with **6/6 unit tests passed**: four actor parsing/navigation/bounds/serialization cases and two scroll cancellation cases. No instrumentation suite or lint rerun; runtime cast navigation remains for user device testing. The existing unrelated popup-action test failure remains unresolved. Fire TV update installed; tablet was only captured on request and remains in Brody's TV. Screenshot stored outside Git (`tablet-current-cast-request.png`). No commit/push for this follow-up.

## NikTV-themed floating navigation

The trial's tablet/TV header now uses the reference's floating pill composition with NikTV branding, Search, Home/Movies/Series, Live TV, My List and the existing Browse menu. A separate rounded clock/profile control uses the active profile initial and opens profile switching. NikTV red selected actions, white D-pad focus and rounded popup items remain the app's own components. Trial labels display Home/Movies/Series but retain Home 2/Movies 2/Series 2 semantic identities and routes; original destinations remain available through Browse. The active tab keeps its Down-to-Play focus path. A horizontally scrollable destination row accommodates narrow tablets/larger labels. Phone navigation retains its separate two-row layout.

Clock updates once per minute in its own composable; no feed reload or provider request is attached. This header change does not alter playback or discovery matching. Build/install result follows. Device navigation checks are left to the user; tablet remains in the reference app.

Normal APK assembly passed in 59s (discovery-pill-navigation-build.log), using the existing :app:assembleDebug command/flags. No additional test suite or lint run for this header presentation change; prior six actor/motion unit tests remain the latest focused test results. Fire TV installation completed for user validation. No commit/push.

## Logo-only header and squarer trial controls

Removed the redundant NikTV wordmark text beside the header logo (including the phone title text), using a 36 dp logo with an accessibility label. Trial actions now use the original settings UI's 10 dp rounded-rectangle corner radius instead of 22 dp pills. The tablet/TV navigation container and clock/profile control use 10 dp corners, with an 8 dp profile initial square. Existing red selection, white focus, button sizes, callbacks and poster/progress-bar styling remain. Original UI components were not edited. Samsung is the preview target; no navigation tests are run at the user's request.

Normal assembleDebug build passed in 51s (discovery-square-controls-build.log). No unit suite or lint rerun for this reversible styling change. Samsung preview build installed; no Fire TV/tablet installation or commit/push in this follow-up.

## IPTV movie/series details and provider categories

User approved applying the trial design to IPTV titles and replacing the existing series presentation. IPTV movies now open a dedicated full-screen detail page from catalog, search, recent and provider-favorite selections; Play retains the selected provider item, profile, visible queue/filter and duplicate occurrence context. Player-side channel/episode navigation still uses the existing direct playback callbacks. The movie detail selection remains in ViewModel state for return from playback and is cleared/hidden across profile changes. Parental gates apply before opening/playing and delayed PIN callbacks are selection/profile guarded.

IPTV series now use the shared discovery hero, metadata, cast and recommendations presentation above the existing provider episode controls. Initial entry targets the header action; playback return retains the existing episode restoration path. Provider seasons/episode IDs, newest/oldest ordering, episode search, pagination, downloads/removal, description, resume, notification subscription, provider/TMDB episode-details toggle and refresh remain connected to their existing callbacks. The redundant old hero and extra primary Play control were removed. Loading episodes disables the primary action rather than exposing a no-op.

Within the trial, IPTV movie/series categories use compact discovery posters, the new adaptive header, local loaded-title search, contextual menus and existing catalog pagination/cache. Empty queries auto-load near the boundary; filtered searches expose an explicit Load more control. Poster selection opens details; explicit Play remains a direct action. Live TV and player UI are not redesigned. Original category destinations remain accessible through Browse.

Provider metadata matching uses existing conservative TMDB matching or a trusted provider TMDB ID. Cached presentation hints (including no-match results) expire after six hours; public detail metadata remains cached for 24 hours in the shared 10 MiB discovery cache. IDs/types/years separate cache lookups; stale data survives refresh failure. Only public TMDB records are serialized: provider IDs, commands and potentially credential-bearing artwork URLs stay out of the shared metadata cache. Raw provider artwork/title/description remain an in-memory fallback, with Play available when TMDB metadata cannot be matched or fetched. Actor and related-title browsing uses the existing discovery callbacks; no automatic IPTV matching is introduced while browsing metadata.

Focused verification command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ProviderMetadataTest' --tests '*SeriesEpisodeEntryFocusTest' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

`provider-details-queue-verification.log`: build passed in 44s; **13 tests passed** (seven metadata cache/privacy/queue cases and six existing episode-return-focus cases). Earlier compilation caught two missing imports, corrected before passing builds (`provider-new-details-final-build.log` failed; retry passed). `provider-details-final-verification.log` also passed the preceding 11-test set. A final presentation-only adjustment preserves the actor grid behind a selected related detail page and adds a no-match explanation; APK assembly is logged in `provider-details-install-build.log`. No full suite, lint or private-provider playback tests run for this follow-up. Device behavior/performance is left for user testing; tests verify cache semantics and navigation policies, not end-to-end playback or measured latency.

Installation scope is explicitly **Samsung and Xiaomi tablet only**. Fire TV must remain untouched until separately requested. No commit/push.

Final presentation APK assembly passed in 38s (provider-details-install-build.log). Verified package updates: Samsung 2026-10-19 23:31:33 and Xiaomi tablet 2026-10-19 23:31:34. Both installs used -r and preserved app data; apps were not launched or navigated after installation. Fire TV received no install or input commands for this work.
## Modern Home default — 2026-10-20

Profile authentication and cached-session switching now select `DashboardSurface.HOME`
and clear destination overlays. The profile chooser and explicit classic destinations
remain available. README and the working plan reflect the new default.

Targeted verification:
`./gradlew.bat :app:testDebugUnitTest --tests '*DiscoveryViewModelTest' --tests '*CatalogBackNavigationTest' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'`
passed in 1m 5s: 18 tests, zero failures/errors, APK assembly successful.
These existing tests cover discovery state/profile isolation and back-navigation
policy; the two profile-entry assignments were reviewed directly. No new device
interaction or full-suite/lint pass is claimed for this small routing change.
Log: `D:/workspace/niktv-playback-tests/modern-home-default-verification.log`.
Samsung update succeeded using `adb install -r`. Tablet installation was not completed: the wireless transport disappeared, reconnected, then went offline again during streamed installation; the non-streaming retry also reported device offline. Fire TV was left unchanged. No apps were launched or navigation/playback tests performed after installation.

## Dedicated phone discovery shell and original-UI fallback — 2026-10-20

Implementation details and the Brodys comparison are in `tv-browsing-motion-plan.md`
under the Samsung audit and first phone implementation sections. Application changes
remain uncommitted. No new dependencies, Room usage, playback-engine modifications,
background IPTV matching or provider benchmarking were introduced.

Verification commands (PowerShell, from the repository):

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DiscoveryPhoneLayoutTest' --tests '*DiscoveryViewModelTest' --tests '*DiscoveryArtworkTest' --tests '*CatalogBackNavigationTest' --tests '*SearchResultNavigationTest' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:testDebugUnitTest --tests '*DiscoveryViewModelTest' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

- Initial compilation caught missing menu arguments in the compact phone-header call;
  corrected before device installation. `phone-discovery-compile.log` records that
  unsuccessful first compile; it is not counted as a pass.
- The first command passed in 1m 16s (`phone-discovery-final-verification.log`):
  PhoneLayout 4, ViewModel 12, Artwork 1, CatalogBack 10, SearchResultNavigation 4.
- The second command reran the changed ViewModel plus a new feed-refresh race case:
  13/13 passed, build successful in 50s (`phone-discovery-hero-race-final.log`). Across
  those suites there are 32 distinct targeted tests, zero failures/errors.
- Final phone D-pad/rotation-pause input refinements were followed by successful APK
  assembly in 1m 25s (`phone-discovery-input-final-build.log`). The final APK was
  installed successfully on Samsung using `adb install -r`, retaining app data.
- `git diff --check` passed. Full unit suite and lint were not rerun for this focused
  presentation change. The previously documented lint errors are not claimed fixed.

Samsung SM-S948W manual checks used normal app navigation, existing profile/cache
content and public TMDB metadata. No NikTV stream was started, favorite/watch-state
items removed, or app data cleared during these implementation checks.

1. Portrait hero artwork, separate title logo, metadata and Add/Play/Info were visible.
   Device findings prompted backdrop-first artwork, equal caption slots and adjustment
   for transparent padding in the existing NikTV logo asset.
2. Home → Live TV → Search → My List → Home remained accessible. Global Search entered
   from Live TV showed all type choices rather than locking to Live TV. Existing Friends
   search returned 42 results and three equal poster columns with retained result actions.
3. Wide Continue Watching cards had identical vertical bounds after correction:
   first and second cards `[60,488][958,1275]` and `[996,488][1440,1275]` in the sampled
   hierarchy. New Episodes used the same wide card composition.
4. Hero swipe left changed the featured selection without launching playback. Info
   opened details without the bottom bar; Android Back restored the landing page.
5. Long-press on a resume card showed Play, Show Related, Watch trailer, View description,
   Add to My List and Clear from this list. Dismissing did not activate Play.
6. Use original UI restored the old Home and old navigation. After the subsequent APK
   update/reopening, selecting the profile still opened old Home. The Settings switch
   was visibly off; enabling it restored modern Home. User customization was retained.
7. On the final APK, three D-pad Down presses focused All → Play → first resume card.
   Right moved to the second card; both sampled focused bounds retained Y=292…1079,
   with no vertical displacement in the settled captures. This is a functional check,
   not a frame-time or animation-smoothness measurement.

Screenshots/hierarchies: `D:/workspace/niktv-playback-tests/niktv-phone-ui-2026-10-20/`.
Samsung was left at the top of modern Home for the user. Tablet was unavailable and
Fire TV was not operated or updated. Larger-font rendering, phone landscape, sustained
memory/frame profiling and physical tablet/TV regression checks remain unverified in
this pass. Playback behavior was deliberately outside these UI checks.


## Series details UX refinement — 2026-10-20

- Separated provider detail presentation into hero and supplementary slots. Series now renders hero/short synopsis, a sticky episode toolbar, episode rows, then cast/related. Provider identity, playback, downloads, notification subscriptions and context menus remain on their existing callbacks.
- Added system-bar insets, a shorter phone hero, a labelled My List control, a continuous dark content background and expandable three-line synopsis. Tablet/TV retain landscape hero geometry and season chips; phones use a bounded season dropdown.
- Labelled latest/earliest sorting, latest/last-watched jumps and Cast/Related shortcuts; jumps account for sticky-header height. Sorting retains the selected season. Remote entry and playback-return episode indexing remain hero=0, toolbar=1, episodes=2 onward.
- Automatic pagination is restricted to the loaded episode boundary, one automatic attempt per page; filtering/loading/empty lists and supplementary-section jumps do not initiate it. A compact manual retry/load-more fallback remains. Counts distinguish loaded pages and local filtered matches.
- Corrected progress lookup to use playback's profile/series/season/episode/title identity rather than substring-matching provider IDs. Supports exact legacy keys and the storage key length bound. Rows show real progress/remaining time or watched status, with smaller repeated-artwork fallbacks and consistent title slots.
- Cast names and roles use equal-height slots. Related posters place text below artwork. Provider-related recommendations prefer the source's original language, then shared genres using the existing cached response; no extra provider matching or recommendation search was added. Incompatible recommendations are omitted. Original language is optional in serialized discovery records; metadata cache versions were advanced for the added field.

Verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.nikhil.niktv.ui.SeriesDetailPolicyTest' --tests 'com.nikhil.niktv.discovery.DiscoveryRelatedRelevanceTest' --tests 'com.nikhil.niktv.ui.SeriesEpisodeEntryFocusTest' --tests 'com.nikhil.niktv.SeriesEpisodeSortTest' --tests 'com.nikhil.niktv.discovery.ProviderMetadataTest' --tests 'com.nikhil.niktv.discovery.DiscoveryCacheTest' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

- Final targeted run: 37 tests, zero failures/errors/skips; build successful in 1m 8s. Log: `D:/workspace/niktv-playback-tests/series-ux-final-build-test.log` (outside Git). Initial targeted build also passed before phone compactness refinements.
- Samsung SM-S948W: first installed iteration visually verified clear status-bar separation, collapsed synopsis, earlier episode access, a sticky toolbar, and the existing resume episode showing 16 min left. D-pad Down moved Resume → first episode, positioned below the toolbar. No playback was started and no app data/history was cleared.
- Final APK installed successfully on Samsung, preserving data. The phone was being used again after install; further automated interaction stopped for user testing. The compact season dropdown and final thumbnail refinements remain for the user's visual confirmation.
- Tablet and Fire TV were not installed or operated for this change. Their layout branches were retained/adapted in code; physical-device validation remains outstanding. Full lint/full app suite and frame-performance measurements were not rerun for this focused UX change. No claim of measured animation performance.


## Series details follow-up controls and season index — 2026-10-20

- Removed Latest and Last watched shortcuts. Search/Cast/Related now use wrapping controls. Phone season picker is always visible, including while only a cached current season is known, with an explicit Refresh seasons entry.
- Resume and My List share the compact detail action row. Phone provider top actions now render over the same prepared artwork; portrait and landscape artwork have both top and bottom dark fades, with bumped transformation cache keys.
- Remaining-time/watched badges moved from the thumbnail to a wrapping episode metadata row beside air date. Progress bars remain on the thumbnail and the resume row retains its highlight.
- Fixed loss of the full season index in both episode-page and player-queue page append paths by merging known seasons. Added a backward-compatible validated-index marker and background repair for legacy multi-page caches with a collapsed index; cached content is shown while repairing.
- Optional provider add/upload timestamp parsed separately from air date, retained through enrichment/cache refresh, and formatted in local date/time. Only explicit upload/add fields and unambiguous Unix/offset timestamps are accepted; missing/date-only/ambiguous values remain hidden. No extra timestamp-specific requests.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.nikhil.niktv.ui.SeriesDetailPolicyTest' --tests 'com.nikhil.niktv.data.ProviderEpisodeTimeTest' --tests 'com.nikhil.niktv.ui.EpisodeMetadataTest' --tests 'com.nikhil.niktv.data.PortalEpisodeClassificationTest' --tests 'com.nikhil.niktv.ui.SeriesEpisodeEntryFocusTest' --tests 'com.nikhil.niktv.discovery.DiscoveryPhoneLayoutTest' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

26 focused tests passed (zero failures/errors/skips), assembleDebug succeeded in 2m 40s; external log `D:/workspace/niktv-playback-tests/series-ux-followup-build-test.log`. `git diff --check` passed. Installed arm64 APK successfully on Samsung without clearing data. Samsung was on Home and then changed to the season picker during the final inspection; the picker reported multiple seasons. Further automated input stopped for user testing. Actual upload-time availability on Wio and final phone/tablet/TV visual confirmation remain unverified. No tablet/Fire TV installation, full suite, lint or performance benchmark in this follow-up. No commit/push.


## Inline Cast/Related and consistent metadata controls — 2026-10-20

Both provider-series panels start hidden and toggle beneath their controls, replacing the previous scroll-to-footer behavior. One is open at a time, with a 180ms top-aligned animated height plus a short fade (zero duration for reduced motion). D-pad entry/return is tied to the corresponding button; actor/detail actions keep their existing callbacks. Movie details retain their static sections. The permanent inline slot is index 2 and episodes begin at index 3, whether a panel is open or closed; return-focus and paging share these offsets.

Episode number/date/remaining time use fixed columns and equal-height badges. Missing statuses reserve their column. Phone thumbnails have equal width so captions align across rows. The season selector/sort buttons share one row, with the episode count below them.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.nikhil.niktv.ui.SeriesDetailPolicyTest' --tests 'com.nikhil.niktv.ui.SeriesEpisodeEntryFocusTest' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Final combined build: 15 focused tests passed, zero failures/errors/skips; assembleDebug succeeded in 53s. External log: `D:/workspace/niktv-playback-tests/series-inline-related-build-test.log`. Samsung installation succeeded, retaining data. `git diff --check` passed.

On the preceding Cast/alignment build, Samsung's Wio screen confirmed season/sort text at identical y=1128–1188, and episode/date/remaining-time labels at identical y=2335–2395 for the resume row, with matching episode/date alignment on other rows. The screen changed during automated inspection, so subsequent inputs stopped; final Related toggle/D-pad interaction and tablet/TV device checks remain for validation. No playback started, user data cleared, Fire TV/tablet installation, full lint/suite or frame-performance measurements. Changes remain uncommitted.


## Always-visible Related and genre matching across languages — 2026-10-20

Provider series details now always supplies the Related control, including before metadata is loaded or when there are no eligible cards. Its focusable empty panel distinguishes loading, fetch failure and no relevant results; D-pad Up returns to the originating button and Down enters episodes. Original language is now only a ranking preference; titles in other languages are eligible when source/candidate genres overlap. Release/adult/source/duplicate exclusions remain. Movies keep their existing static-section behavior.

Read-only public-cache verification on Samsung: Taarak Mehta had 20 cached released recommendations with overlapping genres, all previously excluded because they were English. The revised policy accepts those cached titles without another IPTV request.

Verification command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.DiscoveryRelatedRelevanceTest' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Four focused recommendation tests passed with zero failures/errors/skips; build succeeded in 1m 16s. External log: `D:/workspace/niktv-playback-tests/related-visible-genre-build-test.log`. Samsung arm64 APK installation returned Success, retaining data. Final visual empty-state/D-pad checks remain for device validation; no provider requests, tablet/Fire TV installation, full suite, lint or performance measurement was performed for this small follow-up. No commit/push.


## Inline Search and phone backdrop framing — 2026-10-20

Episode Search now shares the inline Search/Cast/Related panel. Search button or Back closes it. TV Down enters the field and Select enables input; touch enters input by tapping the field. A keyboard-visibility observer closes the panel after IME Back; explicit keyboard Search/Up/Down actions suppress that close so filtered results remain available. Closing/switching panels restores the full list, retaining the typed query for reopening. The floating search overlay is removed, keyboard insets are accounted for, and the persistent inline item keeps paging/return-focus indices stable.

Compact portrait provider-detail backdrops use FillWidth aligned TopCenter, avoiding side cropping without stretching. Poster fallbacks/discovery heroes/tablet/TV artwork retain existing sizing.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.nikhil.niktv.ui.SeriesDetailPolicyTest' --tests 'com.nikhil.niktv.ui.SeriesEpisodeEntryFocusTest' --tests 'com.nikhil.niktv.discovery.DiscoveryPhoneLayoutTest' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

19 focused tests passed, zero failures/errors/skips; initial build succeeded in 1m 12s. Final build after explicit keyboard-action refinements succeeded in 52s. External logs: `D:/workspace/niktv-playback-tests/series-inline-search-artwork-build-test.log` and `series-inline-search-artwork-final-build.log`. Whitespace check passed.

Samsung wireless endpoint changed; rediscovered through ADB mDNS and reconnected without disturbing other devices. Arm64 APK install returned Success, retaining app data. Screenshot 58 in the external QA folder confirms full-width Taarak Mehta backdrop framing, including the rightmost actor. During the subsequent Search-button attempt the page changed and an episode opened; stopped further input and left playback running. Search/Back/IME interaction remains unverified on-device. No tablet/Fire TV installation, full lint/suite or performance measurements. No commit/push.

### 2026-10-20 — IPTV collections and mobile in-tile metadata

Implementation: `ProviderDiscoveryViewModel.kt`, `ProviderDiscoveryCollection.kt`, discovery rail/phone-card integration, and additive existing ViewModel detail/category callbacks. The collection cache uses `catalogues-v1`, already included in temporary-cache storage reporting and clearing.

Focused verification command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nikhil.niktv.discovery.ProviderDiscoveryTest --tests com.nikhil.niktv.discovery.DiscoveryPhoneLayoutTest :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Coverage: selected category membership/sensitive exclusions, stable deduplication, punctuation/accent/word-order local search, expiry boundaries, cache-first loading without provider calls, retained stale data on failure, two-request batch bounds, page append versus refresh, obsolete-profile results, retained metadata bounds, private disk profile/type isolation, artwork preservation, and unchanged phone card footprints including larger fonts.

The first compile passed. The initial focused run found a test-only Robolectric `getApplication` generic argument error, which was corrected. The next run passed 15 tests and built the APK; final follow-up adds direct disk-cache isolation/artwork coverage and reruns the same focused command. Final results and Samsung installation are recorded below when completed. No full lint/suite or Fire TV device test is claimed for this change.

Tablet screenshot requested during implementation was captured without navigation or installation: `D:/workspace/niktv-playback-tests/tablet-current-2026-10-20.png` (Movies screen, Continue Watching / Trending / Top Rated). It shows the previously installed build, not the new collection implementation.

Final targeted results: **16 tests passed** (11 `ProviderDiscoveryTest`, 5 `DiscoveryPhoneLayoutTest`), zero failures/errors. Final combined test/build log: `D:/workspace/niktv-playback-tests/provider-discovery-samsung-verified.log`, BUILD SUCCESSFUL in 55s. The follow-up APK build includes Play/Download routing through the existing guarded provider actions; log: `provider-discovery-install-build.log`.

Additional checks: expired completed categories stay eligible beyond the first refresh batch; nested TMDB detail routes have independent collection state; leaving a route cancels requests and releases private catalogue objects from its ViewModel. No recording/benchmark or new tablet/TV install was performed, so responsive layout policy is implemented but its new collection interaction still needs those device checks. `git diff --check` passed (line-ending notices only).

### 2026-10-20 — tablet glass-header follow-up and final installation

Targeted command added `--tests com.nikhil.niktv.discovery.DiscoveryArtworkTest --tests com.nikhil.niktv.discovery.DiscoveryNavigationLayoutTest` to the collection/phone command above. **19 tests passed**, zero failures/errors; build succeeded in 1m37s. Log: `D:/workspace/niktv-playback-tests/provider-discovery-glass-header.log`. Blur tests verify softened edges, unchanged source pixels and a bounded output bitmap; the sizing policy covers constrained widths/font scales.

The first tablet visual check exposed a clipped My List label caused by estimated widths not including actual button touch/layout bounds. Replaced that estimate with a self-sizing button Row and a `fill = false` scrolling middle section. A subsequent `:app:assembleDebug` succeeded in 57s (`glass-header-measured-layout-build.log`). No data/repository changes followed the passing unit run.

Final APK installed successfully with `adb install -r` on Xiaomi Pad 5 (`6889f3db`) and Samsung SM-S948W (`adb-R3GL10CE4LE-9DLvKe._adb-tls-connect._tcp`), preserving app data. Opened the tablet's existing Wio profile and confirmed larger leading logo, complete Home/Movies/Series/Live TV/My List labels, a panel ending after Browse, and equal-height menu/profile panels. Final screenshot: `D:/workspace/niktv-playback-tests/niktv-phone-ui-2026-10-20/67-tablet-glass-final.png`. A short D-pad right sequence reached Live TV (focused bounds `[899,96][1044,198]`) without a vertical header/layout shift; no media was opened. This is a targeted smoke check, not full remote/portrait/performance regression coverage.

Samsung installation/opening reached the profile chooser; subsequent capture was discarded when the foreground changed, and further Samsung input was stopped. The final APK update installed successfully. Phone card geometry is covered by focused tests; final mobile in-profile visual acceptance remains for the user. Fire TV was not connected, installed or operated. No lint/full unit suite or frame-time measurements were run for this follow-up. Changes remain uncommitted.

Collection cache-hit refinement: once its selected-category snapshot exists, reuse it rather than parsing the full browse/search files again; skip disk writes when no provider page changed. Completed-but-expired categories remain eligible for demand refresh beyond the first batch. Collection search remains explicitly scoped to loaded titles, with pagination and the visible 12,000-record/type bound.
### 2026-10-20 — IPTV category rails and scrolling header

Supersedes the combined IPTV preview/category-chip presentation above. Movies and Series now show individual selected/default category poster rails and per-category Search / All collections. Tablet/TV navigation is part of the scrolling feed, with header/hero/row focus links; phones retain their own navigation.

Verification command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nikhil.niktv.discovery.ProviderDiscoveryTest --tests com.nikhil.niktv.discovery.DiscoveryNavigationLayoutTest --tests com.nikhil.niktv.discovery.DiscoveryMotionTest :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Final log: `D:/workspace/niktv-playback-tests/iptv-rails-priority-final.log`. BUILD SUCCESSFUL in 1m49s; **23 tests passed**, zero failures/errors (18 provider discovery, 3 navigation layout, 2 motion). Additional coverage includes default category selection, separate preview/full caches, category cache retention, clipped preview pagination, scoped refresh, shared loader priority and cancellation releasing a blocked provider gate. Whitespace check passed with line-ending notices only.

Tablet Wio checks: D-pad Down hid the scrolling menu, Up restored its selected tab; movie rows loaded actual provider artwork/titles for Amazon, All Time Favorites and Collection Movies. Series rows loaded Hindi TV Shows, Web Series and Dubbed Series. Screenshots in `D:/workspace/niktv-playback-tests/niktv-phone-ui-2026-10-20/`: `72-header-down-again.png`, `73-header-restored-up.png`, `76-shared-loader-rows.png`, `79-series-iptv-rows.png`. The initial independent provider request path produced generic row errors; routing through the existing shared session/reauthentication path restored loading. The original failure's HTTP cause was not captured, so authentication expiry is not proven. Sanitized diagnostics recorded no subsequent category errors during these checks.

Final source also cancels category HTTP work on title selection and inhibits automatic category loads behind playback/details. This behavior is covered by deterministic cancellation tests; no playback latency measurement is claimed. Final APK installs returned Success on Xiaomi Pad 5, Samsung SM-S948W and Fire TV AFTKRT (compatible armeabi-v7a APK); app data was preserved. Fire TV was idle/asleep before installation and was not awakened for navigation testing. No new full-suite/lint or timed frame/memory benchmark was run. Full collection touch search and a final Fire TV D-pad smoke test remain unverified for this follow-up. No commit or push.

### 2026-10-20 — accessible Categories shortcut and shared chooser redesign

The new discovery header now has a Categories action on tablets/TV and a labelled-for-accessibility Tune icon on phones. Movies/Series open the existing profile/type editor directly; Home offers a media-type picker. The chooser was extracted from CatalogDialogs.kt into CategoryPickerUi.kt with navigation/selection policy in CategoryPickerPolicy.kt. It retains the existing applyCategoryFilters persistence callback, local keyword search, bulk selection/clear and staged edits; no new preference store or provider lookup was introduced. The hero pauses while the editor/type popup is open. Closing restores the originating shortcut.

Targeted verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nikhil.niktv.ui.CategoryPickerPolicyTest --tests com.nikhil.niktv.discovery.ProviderDiscoveryTest --tests com.nikhil.niktv.discovery.DiscoveryNavigationLayoutTest :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Final log: `D:/workspace/niktv-playback-tests/category-picker-keyboard.log`, BUILD SUCCESSFUL in 46s; **25 tests passed**, zero failures/errors (4 chooser policy, 18 provider discovery, 3 navigation layout). Coverage added: horizontal grid boundaries, incomplete final rows, vertical exits, larger-font column policy, explicit empty selections, and draft precedence over saved/default values. Build preparation tasks were inspected; no integrated-source change was needed. `git diff --check` passed with line-ending notices only.

Installed the final APK on Xiaomi Pad 5 only, preserving app data. Tablet Wio checks confirmed Home's type picker, direct Movies/Series access (78 and 29 categories respectively), selected-category remote entry, D-pad movement to offscreen categories, return focus to Categories, touch search (NETFLIX -> 7 matching movie categories), and temporary toggle count changing 78 -> 77. The temporary toggle was canceled; reopening restored 78. The initial touch-search test found the keyboard obscuring the footer. Fixed edge-to-edge dialog insets and compacted controls while the IME is visible; the final screenshot confirms Cancel/Save above the keyboard and Cancel dismisses correctly. Final screenshots in `D:/workspace/niktv-playback-tests/niktv-phone-ui-2026-10-20/`: `93-final-movie-picker.png`, `94-final-keyboard-resize.png`, `95-series-category-picker.png`. Earlier D-pad captures `86-picker-offscreen.png`, `87-picker-return-focus.png`, `88-type-dpad-entry.png`, `89-dpad-chooser-entry.png` cover the same navigation policy before inset refinement.

No saved selections were changed during device testing; saving uses the existing persistence path and was not exercised with a new permanent selection. Samsung showed its notification shade, so no phone interaction/install was performed. Phone/large-font layout policy is unit-covered, but final phone visuals are not device-verified. Fire TV was neither accessed nor installed, at the user's request. No full-suite/lint or performance benchmark was run. Changes remain uncommitted; no push.

### 2026-10-20 — TV/tablet series episode layout and focus

Scope narrowed explicitly by the user to TV/tablet; mobile presentation was retained. Shared landscape episode tiles now serve IPTV and TMDB series; IPTV episodes use a horizontal rail, while preserving progress, season ordering, download/removal and description callbacks. Provider hero/season/sort/tools/episodes have explicit vertical remote paths; episode Left/Right stays horizontal. Pagination and playback-return focus now distinguish vertical slots from horizontal episode indices. Stale focus movement is canceled on season/search/order changes. Episode popups return focus to their originating tiles.

Focused command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nikhil.niktv.ui.SeriesDetailPolicyTest --tests com.nikhil.niktv.ui.SeriesEpisodeEntryFocusTest --tests com.nikhil.niktv.discovery.DiscoveryNavigationLayoutTest :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Initial compile found a Compose stickyHeader callback signature mismatch; corrected it without changing the phone toolbar. Final log: `D:/workspace/niktv-playback-tests/series-landscape-device.log`, BUILD SUCCESSFUL in 1m11s. **20 tests passed**, zero failures/errors (11 series policy, 6 entry focus, 3 discovery navigation). New policy assertions cover horizontal playback-return slots and horizontal pagination gates for loading/filtering/interaction. Whitespace check passed with line-ending notices only.

Final arm64 APK installed successfully on Xiaomi Pad 5, preserving app data. Wio device checks: One Euro House's three episodes displayed as a landscape row; Down through controls entered the row; Left/Right stayed on it; long Select opened Download episode without executing it, and Back dismissed the popup. Below Deck loaded TMDB-backed metadata and multiple seasons; selecting season 12 from season 13 displayed its 16 episodes. D-pad returned through tools/sort to the season strip and navigated beyond the initially visible episode tiles. Matched captures `110-season12-episode-entry.png` and `111-episode-offscreen-right.png` retained the same vertical coordinates while episode focus/scroll moved horizontally. Cyberpunk TMDB details displayed the same episode tile design; season Down entered the first episode and episode Up returned to the season strip. Captures `114-tmdb-episode-entry.png` and `115-tmdb-season-return.png` record these checks. All captures are under `D:/workspace/niktv-playback-tests/niktv-phone-ui-2026-10-20/`.

Device testing did not start playback or a download, so playback-return behavior and provider pagination are regression-policy-covered, not exercised end-to-end in this session. No final phone test, full lint/suite or frame-time benchmark was run. Fire TV was neither accessed nor installed. Final changes remain uncommitted; no push.

### 2026-10-20 — Fire TV offscreen focus, description, pagination and return presentation

The subsequent Fire TV report exposed a missing case: the season strip can remain composed while its hero focus target is disposed. Direct requestFocus then cannot reach the top. Landscape vertical navigation now follows an explicit TOP/PLAY/INFO/SEASONS/SORT/TOOLS/EPISODES path, revealing the target's lazy slot through the cancellable scroll coordinator before transferring focus. The focus viewport suppresses competing implicit scrolling during that operation. A dedicated More info action opens the complete provider/TMDB synopsis and restores its originating control on close. Mobile keeps inline description expansion. Landscape Load more is the final item in the horizontal episode row; the separate bottom button remains only for the phone's vertical list.

Back to discovery previously discarded preview state, rebuilt category-to-row mappings asynchronously from an empty map, and initialized backdrop presentation empty. Landing previews now pause requests while retaining their existing bounded per-profile metadata and row mappings; nested detail copies still release on exit. The focused row, selected presentation and prepared backdrop survive detail returns. Profile/type identities still guard rendering, and rebinding to a different profile clears incompatible state. Original browse-category returns and playback engines were not changed.

IPTV rows, Continue Watching and New Episodes now identify the provider title associated with focus. After stable row focus, the discovery VM immediately selects that title's fallback presentation and calls the existing public-only provider-to-TMDB metadata cache for enrichment. Cached mappings use the existing six-hour feed TTL and full details use the 24-hour detail TTL. This does not search the IPTV catalogue or resolve/open a stream. Cancellation/generation/profile/selection guards reject late responses. Hero Play/My List/Info continue dispatching against the original provider media identity and queue, so enrichment does not replace playback identity with a TMDB ID.

Final targeted command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nikhil.niktv.ui.SeriesDetailPolicyTest --tests com.nikhil.niktv.ui.SeriesEpisodeEntryFocusTest --tests com.nikhil.niktv.discovery.DiscoveryViewModelTest --tests com.nikhil.niktv.discovery.ProviderDiscoveryTest :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Log: `D:/workspace/niktv-playback-tests/firetv-series-return-presentation.log`, BUILD SUCCESSFUL in 1m41s; **54 tests passed**, zero failures/errors (13 series policy, 6 episode entry focus, 16 discovery VM, 19 provider discovery). Added regressions traverse all Up destinations including single-season series, check offscreen destination slots, retain ready previews without reparsing disk, reject stale provider-title metadata, preserve focused/prepared presentation on return, clear incompatible profile presentation, and reuse settled metadata without another request. `git diff --check` passed with line-ending notices only. No new full-suite/lint or frame-time benchmark was run.

Observed the original Fire TV Taarak Mehta screen stuck at Season 49 with a separate bottom Load more button; Back returned to Series discovery with an unrelated TMDB background behind the focused Taarak tile. Screenshots: `D:/workspace/niktv-playback-tests/niktv-phone-ui-2026-10-20/116-firetv-series-before.png` and `D:/workspace/niktv-playback-tests/firetv-series-back-before.png`. Fire TV's accessibility dump returned a null root, so stale XML labels from that capture were disregarded; observations use the actual pixels. The updated compatible armeabi-v7a APK installed successfully on Fire TV with app data preserved. The user requested immediate installation and will perform the on-device acceptance checks; no post-install remote inputs were sent. No commit or push for this follow-up.


### 2026-10-20 - header re-entry and denser category chooser

The reported Back flash also occurs after scrolling the lazy header offscreen and returning. The header now intercepts focus-group entry before Back receives focus, routing automatic first entry and directional re-entry to the active Home/Movies/Series tab. Subsequent explicit focus restoration is preserved for popup origins; Back remains a normal child reachable within the header. Regression coverage checks a newly created group, repeated Up entry and explicit popup-return requests. The first focused run passed seven navigation/motion tests and assembled the APK (`header-focus-entry.log`, BUILD SUCCESSFUL in 1m27s). The compatible armeabi-v7a APK installed successfully on Fire TV; user acceptance testing was requested instead of further remote input.

At the user's request, compact the category chooser beyond the initial redesign: up to four columns at 800 effective dp, two at 560, otherwise one, scaling down with larger fonts. Use a wider panel, 40 dp TV rows (48 dp minimum touch targets), 4 dp vertical gaps, smaller checkbox spacing and corner radius. Combine the selection/available count with the header and place search beside bulk actions when readable. Keep fixed Cancel/Save, keyboard inset handling, one-line category titles with focused marquee, draft selection, and explicit remote movement. Added four-column incomplete-row/edge regression coverage.

Final focused command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nikhil.niktv.ui.CategoryPickerPolicyTest --tests com.nikhil.niktv.discovery.DiscoveryNavigationLayoutTest --tests com.nikhil.niktv.discovery.DiscoveryMotionTest :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
```

Log: `D:/workspace/niktv-playback-tests/compact-category-header.log`, BUILD SUCCESSFUL in 1m. All **12 targeted tests passed**, zero failures/errors (5 category policy, 5 navigation layout, 2 motion); `git diff --check` passed with line-ending notices only. Visual acceptance remains with the user; no new frame-time benchmark, full unit suite or lint run is claimed for this focused follow-up. Changes remain uncommitted; no push.


The final compact chooser APK installed successfully on Fire TV with `adb -s 192.168.2.19:5555 install -r app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk`; app data was preserved. Subsequently, the user explicitly requested observing Up/Down on the current discovery screen. Reopened NikTV after installation and sent only Up/Down keys, without starting media or changing saved selections. Recorded `D:/workspace/niktv-playback-tests/firetv-focus-settle-20261020.mp4` with input log `firetv-focus-settle-events.txt`; contact sheets show vertical row movement followed by visible settling/correction, consistent with the reported stutter impression. This is a qualitative reproduction under screen recording, not a clean frame-time benchmark or a proven cause. No additional animation fix was made; the user asked to investigate the cause later.


### 2026-10-21 - bounded row motion, menu entry and hero category actions

Temporary Fire TV geometry logging without screen recording confirmed that the explicit critical spring can keep navigation active through a long one-pixel settling tail. One sampled downward destination ran from 00:04:23.065 to 00:04:24.095 (about 1.03 s under diagnostic load); offscreen Up used a different default lazy-list spring path. This establishes inconsistent/extended animation behavior, not a complete rendering-performance diagnosis. Trace: `D:/workspace/niktv-playback-tests/discovery-scroll-probe-live.txt`. The initially suspected competing automatic reveal is not proven as the cause of every visible correction.

Use one finite 240 ms easing timeline for both visible and offscreen destinations via LazyLayoutScrollScope. Recalculate remaining distance as the destination becomes measured, without launching a second settling animation. Use UserInput mutation priority to protect explicit moves from lower-priority automatic reveal; real touch and new navigation remain cancellable. Immediate focus transfer, remote rapid-input cursor, reduced motion and horizontal edge reveal remain. Removed the temporary production per-frame probe.

Add a discovery navigation-entry identity so selecting Home/Movies/Series from a menu starts at its active header tab, while detail/playback returns retain their saved origin. The IPTV category dialog intercepts initial focus before Search, including a selected category initially outside the first grid row. Remove decorative icons from category access/header. At the user's subsequent request, move IPTV category access from top navigation to the featured controls near the carousel counter and add TMDB categories alongside it. Wrap controls on constrained layouts; retain existing category filters, independent trial TMDB preferences and popup-origin restoration.

Commands/results:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.nikhil.niktv.discovery.DiscoveryMotionTest --tests com.nikhil.niktv.discovery.DiscoveryNavigationLayoutTest --tests com.nikhil.niktv.ui.CategoryPickerPolicyTest :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:assembleDebugAndroidTest -PregressionBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8'
adb -s 192.168.2.19:5555 shell am instrument -w -r -e class com.nikhil.niktv.ui.DiscoveryScrollDeviceTest,com.nikhil.niktv.ui.DiscoveryNavigationDeviceTest#remoteRowNavigationAndReversals com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
adb -s 6889f3db shell am instrument -w -r -e class com.nikhil.niktv.ui.DiscoveryScrollDeviceTest,com.nikhil.niktv.ui.DiscoveryNavigationDeviceTest#remoteRowNavigationAndReversals com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
adb -s 192.168.2.19:5555 install -r app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk
```

- Focused unit tests: **12 passed**, zero failures/errors. Regression build SUCCESSFUL in 3m10s (`discovery-settle-regression-build.log`). Normal debug build SUCCESSFUL in 2m38s (`discovery-settle-final-build.log`). Test APK rebuild SUCCESSFUL in 2m6s (`discovery-menu-device-rebuild.log`). Build preparation scripts were inspected; generated embedded Flow/SmartTube work did not introduce extra tracked changes.
- Fire TV: **two focused device tests passed**: selected-category entry without a Search focus event, and real nested lazy poster rows moving monotonically, completing within a 700 ms wall-clock allowance, staying stationary for 450 ms after completion, and accepting a reversal. The existing broader test failed at an unrelated device-classification assertion because it used width alone to classify Fire TV as a phone. Corrected the test to use the same TV/phone policy as production, keeping its phone checks active. This failure is retained in `discovery-settle-firetv-device.txt`; do not call that three-test Fire TV run a pass.
- Xiaomi Pad 5: **all three device tests passed** after the fixture correction, including horizontal row stability, rapid vertical reversals, scroll back to the header, explicit Movies entry, and reopening Home without restoring the old tile. Log: `discovery-settle-tablet-device.txt`, OK (3 tests), 22.702 s. Tablet was idle on its launcher before testing. All fixtures use a separate regression package and fake metadata; no personal provider credentials, streams or saved category selections were used.
- Installed the final regular armeabi-v7a APK successfully on Fire TV with app data preserved, before moving the remaining test to the tablet so the user could test Fire TV uninterrupted. No additional Fire TV navigation was sent afterward.

The bounded animation and lack of post-completion correction are device-tested in the fixture. A matched end-to-end frame benchmark, cold-network artwork latency, final Samsung visuals, full unit suite and lint were not run in this focused follow-up. No claim of zero dropped frames or universal lag-free scrolling is made. The user subsequently requested committing and pushing all pending fixes.
