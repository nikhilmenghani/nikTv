# Home 2 scrolling experiment — 2026-11-01

## Delivery and isolation

Home remains the default. Home 2 is an additive tablet/TV tab and a phone Options entry. Its Options → Performance experiment menu selects Control, four rows, eight tiles, static artwork, minimal row movement, immediate focus, four landscape cards, text-only, the combined Lean preset, or the native-row renderer. The default trial is native rows with gentle cached focus motion. The same menu's Test Home / Test IPTV Movies / Test IPTV Series actions compare sources without replacing the original tabs.

Trial view models, scroll/focus state and TMDB category preferences are separate from original screens. Home categories are copied once, then isolated by profile and experience. Public metadata/image caches remain shared; personal catalogues, playback, favorites and history remain profile scoped. IPTV previews read the current profile’s existing selected categories. All clicks/long presses route through existing callbacks and parental/playback policy. No player implementation or provider stream was changed.

## Findings and implementation

- Tablet's original presentation retained animated poster layers, focused metadata/backdrop updates and top-aligned row scrolling; TV already used a simpler path.
- Lazy rows already compose only a visible subset. Cutting offscreen catalogue entries alone did not consistently improve touch performance.
- A separate sampled trace (670,714 bytes, no overflow, about 2.2 seconds) showed significant Compose recomposition and view/drawing work. Its inclusive spans overlap and must not be added; diagnostic timing is excluded from comparisons.
- Shared changes retain functionality: poster decode dimensions follow their actual physical footprint plus focus growth; row content types distinguish reusable rail/poster layouts; newly encountered feed/provider requests wait until vertical scrolling settles. This does not cancel already running provider calls or promise that all updates happen outside scrolling.
- A nested-row precomposition increase to eight was tried and reverted: full-screen results were mixed, including a slower Nikhil Movies touch sample. It is not delivered as a proven optimization.
- The bounded native prototype uses Android ScrollView/HorizontalScrollView, cached native child views, native directional focus and the existing Coil image loader (two concurrent requests, max 4×8 cards). Saveable restoration is nonobservable during scrolling; redundant reveal requests were removed. Native click and long-click stay separate. Touch activates on the first tap; accessibility exposes consolidated cards. Touch-to-remote reentry restores selection, and versioned feed-update restoration cannot steal focus from the header or a popup.
- A trial-window refresh-rate hint was tested and reverted. It did not reliably close the D-pad gap; no system refresh/animation settings were changed.

## Best delivered mode: native rows with gentle focus motion

A final native focus experiment adds an immediate border and a small 1.025× cached zoom over 320 ms, without shadows or layout animation. It respects reduced motion. This keeps animation/presentation active during remote navigation. Repeated Home trials reduced absolute missed deadlines as well as frame-weighted p95; it is not only a denominator change. The scheduling explanation is supported by lower captured start/sync/dequeue waits, but is not proven as a universal root cause.

| Source in Home 2 | Nikhil touch / D-pad p95 | wio touch / D-pad p95 |
|---|---:|---:|
| Home | 13–14 / 12 ms | 13 / 12–16 ms |
| IPTV Movies | 13 / 12 ms | 12 / 15 ms |
| IPTV Series | 13 / 10 ms | 13 / 15 ms |

Gentle-motion Movies/Series D-pad missed 4–6 deadlines in 235–238 rendered frames. Repeated Home runs missed 5–8 in 236–237 frames, compared with instant-focus Home's 11–12 in about 84 frames. SmartTube reference remains better at 2/246 and uses different content/geometry. Continuous motion creates more ordinary frames, so compare both absolute counts and frame-weighted statistics. These short runs do not establish pixel input latency, zero stutter or production equivalence.

The final installed normal Dev source was checked again on wio Home 2 after the touch/focus fixes: D-pad p95 12 ms with 5/235 missed deadlines; touch p95 13 ms with 6/263. This smoke check confirms the final source's Home path; the broader two-profile/three-source acceptance table above was measured before the later input-only fixes.

The rows below retain the earlier **instant-focus native control**, explaining the investigation rather than representing the final default.

## Real tablet measurements

Xiaomi Pad 5, normal Dev APKs, landscape, 120 Hz display snapshot. Touch workload: four 450 ms swipes; D-pad workload: three Down then three Up at 250 ms cadence after entry. No screen recording or sampling trace during timed runs. Hierarchy captures occur outside timing. Global gfxinfo counts cover the reset interval; final frame rings are bounded to 120 and may not cover a whole run. Measures below are render-pipeline frame times, **not pixel input-to-focus latency or FPS**.

| Profile / screen | Original touch p95 | Native preview touch p95 | Original D-pad p95 | Native preview D-pad p95 |
|---|---:|---:|---:|---:|
| Nikhil Home | 57 ms | 13 ms | 61 ms | 32 ms |
| Nikhil Movies | 97 ms | 13 ms | 77 ms | 38 ms |
| Nikhil Series | 46 ms | 13 ms | 53 ms | 32 ms |
| wio Home | 46 ms | 12 ms | 53 ms | 32 ms |
| wio Movies | 73 ms | 13 ms | 61 ms | 26 ms |
| wio Series | 77 ms | 13 ms | 61 ms | 28 ms |

Original catalogue APK baseline is commit 867d79e2. Nikhil Home uses an iteration-1 original-screen measurement, rather than a pristine frozen Home baseline. These are constrained workload comparisons: the native prototype omits features, changes geometry, preconstructs its bounded rows and has different cache/process history. No percentage universal speedup is claimed. Native touch repeats were 13–14 ms; final native touch missed 3–6 deadlines out of 252–273 frames, depending on profile/surface.

SmartTube reference on this tablet: D-pad p95 12 ms, 2 missed deadlines / 246 frames. Different content, tile geometry and animation-frame counts mean this is a reference target, not a matched A/B. The instant-focus native control missed about 10–15% of its 78–85 frames in these short runs, versus SmartTube's different frame-weighted denominator.

In the instant-focus native control, remaining D-pad tails were dominated by presentation waits rather than long UI computation: UI-work p95 roughly 2.5–4.1 ms. Captured slow frames include 20–29 ms start/sync/dequeue-buffer waits. One Nikhil Home 47.42 ms frame had only 1.45 ms UI work and 25.40 ms dequeue wait. These spans overlap. They suggest presentation scheduling/backpressure; they do not prove GPU shader overload or provider/network delay.

## Experimental trade-offs

Native mode deliberately limits its preview to four rows and eight cards per row. Original full tabs and Search remain available; the complete catalogue is never truncated in storage. The native trial omits the hero/carousel, dynamic focused artwork, the original Compose poster/shadow effects, ranking numerals and graphical progress overlays. Its default instead uses the small cached native focus zoom described above; an immediate-focus native control remains selectable. Source is retained as subtitle text, and existing resume/open callbacks remain. Its header stays available above the scrolling native rows. This is a controlled renderer prototype, not the final replacement design. Phone header/bottom-navigation spacing adapts, but this session's physical-device coverage is tablet landscape only.

The ordinary Movies/Series retain their full categories, visuals and behavior. Only shared low-impact fixes were applied there; their preliminary full-feature timings remained mixed, so no blanket smoothness claim is made.

## Verification and limitations

- 60 focused JVM tests passed: experiment budgets, pixel sizing, scrolling/focus policy, discovery view model/profile/preference isolation and startup navigation.
- Three isolated tablet fixture tests passed twice on the final native source, then twice again with a Surface/Box host matching the actual app and explicit view-disposal/attachment synchronization (`home2-host-sync-device-1.log`, `home2-host-sync-device-2.log`). They cover Home 2 setting/feed isolation, both native motion modes, native directional traversal and remembered columns, header return/reentry, click versus held-center behavior, first physical touch, accessibility exposure and detail-return restoration. The final focus-update race checks also ensure a queued feed refresh keeps native selection without stealing header focus. No personal provider data is used by these fixtures.
- Normal Dev APK built and was installed on the tablet. No release build/install, Samsung changes or Fire TV actions were performed in this task.
- Actual installed-app checks verified one-tap Clayface details, D-pad detail return to Clayface, separate long-press actions, and access to all three trial sources. The isolated regression apps were removed afterward; the regular Dev app and personal data remain.
- Full lint failed on the same 22 existing errors. No new native/discovery experiment error was reported; no check is suppressed.
- Native/source tests did not play media or alter favorites/history. Catalogue browsing warmed normal caches. No personal app data was cleared.
- A Nikhil measurement whose end foreground guard failed and another interrupted by host ADB-daemon loss are excluded, not counted as passes. The daemon was restored without device input; completed runs were repeated separately. The early wio preset matrix shared a minimal-reveal flag across presets and is exploratory only; it cannot establish isolated feature causality. The corrected presets isolate that policy.
- Expanded input checks exposed and fixed first-touch activation, touch-to-remote reentry and an intermittent saved-focus initialization failure. A fixture run accidentally using an earlier copied APK is explicitly excluded (`home2-focus-race-stale-artifact-excluded.log`). Final runs use APKs copied from `app/build-regression`, preserve the requested return identity during initial scroll and both pass (`home2-return-init-device-1.log`, `home2-return-init-device-2.log`).
- A later host-parity return check exposed test synchronization that relied on `waitForIdleSync` before a scheduled Compose frame. It now waits for actual old-view removal and new-view attachment/layout before requesting entry focus, retaining the exact origin assertion. Both final host-parity runs pass. Actual-app UiAutomator captures exposed the Compose header but did not enumerate native cards; fixture accessibility checks pass, including the Surface host. Full in-app TalkBack behavior remains unverified.
- Short PSS endpoints (final native-motion runs roughly 285–318 MiB) are not peak memory or leak-proof measurements. Longer repeated-navigation/thermal tests are still needed.

## Reproduction and next work

Evidence directory: `D:/workspace/niktv-playback-tests/scroll-experiment-20261101/`. Raw JSON/frame/memory data: `D:/workspace/niktv-playback-tests/tablet-home2-20261101/`. The frozen original and candidate APKs are retained outside Git. The collector/driver scripts and final-tablet-performance.md record exact runs, cache/geometry caveats and exclusions; recordings are disabled during timing.

Focused build/test example (JDK17):

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests '*DiscoveryScrollExperimentTest' --tests '*DiscoveryPosterDecodeSizeTest' --tests '*DiscoveryMotionTest' --tests '*DiscoveryRowFocusTest' --tests '*DiscoveryNavigationLayoutTest' --tests '*DiscoveryViewModelTest' --tests '*ProfileDashboardStartupTest' -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s 6889f3db shell am instrument -w -r -e class com.nikhil.niktv.ui.NativeDiscoveryRowsDeviceTest,com.nikhil.niktv.ui.DiscoveryExperimentDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
```

Regression APK outputs are under `app/build-regression/outputs/apk`; normal Dev outputs are under `app/build/outputs/apk`. Copy each artifact outside build outputs before switching configurations. Final normal-Dev checks use:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests '*DiscoveryScrollExperimentTest' --tests '*DiscoveryPosterDecodeSizeTest' --tests '*DiscoveryMotionTest' --tests '*DiscoveryRowFocusTest' --tests '*DiscoveryNavigationLayoutTest' --tests '*DiscoveryViewModelTest' --tests '*ProfileDashboardStartupTest' :app:lintDebug -PskipLocalGithubToken=true --continue --console=plain
```

Build and focused tests pass independently of the existing lint failures; the combined command exits unsuccessfully because lint fails. Reports and logs are retained rather than suppressed.

Final installed APK SHA-256: `41E67E2AB15A97350838566513424F14DC27FD83C31DF5C679B75A17292781C6`. Final-source checks: `home2-final-installed-source-checks.log`; copied JUnit/lint XML: `scroll-experiment-20261101/verification/`. These artifacts remain outside Git. No implementation commit or push was made.

Next: a short Perfetto FrameTimeline/SurfaceFlinger/RenderThread/scheduler capture around D-pad transitions; frame-accurate input/focus correlation; matched-content cold/warm repetitions and memory/thermal soak; then Fire TV validation when authorized. The default constrained native-motion preview is now much closer to the SmartTube reference on this tablet, but the full-feature production/Fire TV target remains unproven. Keep the original UI until longer matched-content and TV evidence supports a replacement.
