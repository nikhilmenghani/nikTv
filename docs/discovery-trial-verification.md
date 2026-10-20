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
