# Phase 18 — functional review

Preceding commit `798519f3`. Original Home stays unchanged; optimized Dev only. APKs, raw measurements and captures stay outside git in `D:/workspace/niktv-playback-tests/home2-phase18/`.

## Implemented fixes

- Fresh Home 2 starts with its full presenter. Explicit choices persist by profile/device class; original routes always use Control.
- Generation/target guards reject obsolete deferred hero entry. The same-frame entry/Down race was reproduced on tablet and TV before fixing it.
- Bounded stable-identity bookmarks restore row, item and both pixel viewport axes before requesting focus. An already visible restored item avoids a second padding correction.
- Up to Play reveals the complete hero. Top-navigation entry and detail/popup return have separate focus policies.
- Centered View all/X row controls participate in Up/Down navigation. Continue Watching/New Episodes have View all without X. Replace the duplicate end-of-rail Search/All tile with the header action. View all opens the specific provider/TMDB/activity collection.
- Park focus while asynchronous first cards attach, temporarily disabling header focus during handoff. Fresh media receives focus without an intermediate View all/X highlight; obsolete loading work cannot reclaim focus.
- Keep Home 2's navigation context in full TMDB collections rather than highlighting/focusing My List. Supplementary descriptions/cast in those trial collections use explicit Info/hold actions instead of automatic delayed enrichment.
- Anchor native hold menus beside their originating tile, capturing bounds only on activation. Existing non-native menu positioning is unchanged. Nested dialogs preserve the originating tile.
- Closing trial settings restores Options; entry from the top tab then reaches Play. Provider Movies/Series previews omit an empty TMDB hero.
- Match Home's landscape hero-height policy and top-aligned full-width 16:9 artwork, with a bottom gradient. The Home-style hero framing switch retains the compact A/B control. Existing 1280×720 requests/cache are reused. Optional metadata remains off by default; focus-driven artwork is deferred to phase 19.

Row controls add a deliberate vertical focus stop per row. The final three-row round trip uses six Down/six Up, so three-Down tests from earlier phases do not measure the same destination workload.

## Verification

From the repository, with `JAVA_HOME=C:/Program Files/Java/jdk-17`:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' --tests 'com.nikhil.niktv.ui.TileMenuPositionTest' -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s <serial> shell am instrument -w -r -e class com.nikhil.niktv.ui.DiscoveryExperimentDeviceTest,com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:lintDebug -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
python tools/check-source-secrets.py
git diff --check
```

Build and **223 focused unit tests pass** (`resumed-final-dev-build.log`). Tablet: **13 device tests pass** (`tablet-complete-13-tests.txt`). TV: **13 device tests pass**, 51.29 seconds (`tv-resumed-final-13-tests.txt`), after isolating the failures described below. No unrelated full-unit-suite pass is claimed. The final nonempty-activity-grid entry guard was subsequently built/unit-tested and verified in the real TV app; it changes neither the measured landing renderer nor the native fixture code.

Fixtures use a separate regression package, local data and no provider/playback/history/credentials. Coverage includes 3,200-record bounded recycling, header/hero entry, rapid reversals, stale requests, two-axis exact viewport return, held activation/release, memory pressure, 1.6× captions, reduced focus motion, row removal, late first-card entry, modal return and original Home isolation. Final geometry assertions wait for the existing scale animation; their two-pixel tolerance was not loosened.

Lint **fails: 22 errors, 363 warnings, 3 hints** (`resumed-final-lint.log`). Groups: NonObservableLocale 4, RememberInComposition 13, RestrictedApi 3, SuspiciousIndentation 1, UnsafeOptInUsageError 1. Their offending declarations, including four in the touched shared collection file, predate this change. No baseline/suppression was added. Secrets scan: **0 findings**; whitespace check passes.

## Real application checks

Tablet: Wio/Nikhil Home 2 and provider previews; movie/series details and Back; activity/TMDB View all and Back; anchored remote hold/nested description return; touch gestures; missing-artwork fallback. The final TMDB collection return retains the inspected header viewport exactly. The singleton provider series enters its first media tile. Personal category selections, favorites and history were preserved.

TV: full hero reveal; activity View all/Back; centered header controls; movie/series details and Back; anchored held menu and nested description return; both profiles' provider Movies/Series previews; first media entry; singleton/missing-artwork fallback. The movie details, series details and TMDB View all return captures have **pixel-identical content bodies** (1920×940 below the clock/header), confirming the previously observed vertical correction is absent in these checked returns. Nested description return restores the inspected series identity/viewport; no exact-pixel claim is made for that capture. Final Wio Continue Watching View all contains eight titles and focuses the first media, not Back. Test apps were removed; final Dev is installed on TV/tablet, TV is left on Wio Home 2 with Home-style framing on and metadata off. No playback or personal selection data was changed.

Before the pause, one remote movie Back sequence unexpectedly displayed the exit dialog. The resumed controlled movie/series/collection Back checks did not reproduce it. Retain it as an unproven intermittent observation, not a passing sequence or an assumption about another user's input.

TV accessibility dumps returned a null root in both NikTV and the launcher, while screenshots/input remained usable. The resumed shell fixture now inspects its own Compose semantics/window roots instead of depending on this system service. That is a test-tool adjustment, not a production accessibility fix. Shell input acceptance alone is not used as proof of the focused destination.

Actual-app portrait coverage remains incomplete: changing system user rotation left tablet captures in landscape. System rotation and animator scale were restored (0 and 1.0). Large-font/reduced-motion fixtures do not substitute for portrait coverage.

## Matched artwork comparison

All measurements: optimized Dev (`DEBUGGABLE=false`), not Release/R8; no recording/tracing/hierarchy inspection or host builds during timing. Frame histogram p95 is **not input-to-visible-focus latency**. First exposure is separate from warm repeats.

Tablet Nikhil A/B/A: same APK, metadata off, verified first Continue Watching card; six Down/six Up, 250 ms, same three-row round trip:

| Framing | Per-run frame p95 | Median p95 | Deadline misses / frames, three runs |
|---|---|---|---|
| Compact A | 18 / 15 / 15 ms | 15 ms | 8 / 1,087 |
| Home-style B | 19 / 15 / 15 ms | 15 ms | 8 / 1,089 |
| Compact return A | 19 / 15 / 15 ms | 15 ms | 8 / 1,094 |

Evidence prefixes: `tablet/Nikhil-reviewed-compact-v*`, `Nikhil-reviewed-homeframe-v*`, `Nikhil-reviewed-return-compact-v*`. **No measurable warm p95 cost** from larger framing in this short tablet comparison. Misses remain; it does not establish no cost with cold cache or idle rotation. Home-style framing is enabled in the final candidate.

Resumed TV Nikhil A/B/A uses the same three-row round trip, presenter and metadata-off setting. Verify each framing switch and first Continue Watching card in captures before timing; verify the same card on return. The first run of each group includes the return from hero-visible setup; subsequent runs start at the returned first row. Requested spacing is 250 ms, but shell dispatch overhead is reflected in the recorded input timestamps and elapsed time, not assumed to be 250 ms.

| Framing | Per-run frame p95 | Median p95 | Deadline misses / frames, three runs |
|---|---|---|---|
| Compact A | 19 / 8 / 8 ms | 8 ms | 2 / 758 |
| Home-style B | 14 / 8 / 8 ms | 8 ms | 2 / 758 |
| Compact return A | 9 / 8 / 8 ms | 8 ms | 2 / 757 |

TV evidence: `tv/Nikhil-resumed-*-v*`, `tv/resumed-artwork-comparison.json`. No measurable warm p95 increase from the larger artwork on TV either. There were six deadline misses in 2,273 frames; UI-work ring samples contained no values over 50 ms. The ring covers only the last 120 frames of each run and is not a whole-run main-thread-stall trace. Do not treat the first-run 19→14→9 ms progression as a causal benefit of either framing setting.

APK SHA-256 identities:

- Measured TV: `d830668e9f8a24e3e377e994d66ec77cb40a1d39489217fd13b091e82c5623e7` (`tv-artwork-measured-dev.apk`).
- Final TV: `ba53e9a254431937135c92c228812ac04fc4dbc96acc349e767917b317efdaa7` (`tv-final-dev.apk`).
- Final tablet: `8c4f96e60deed60e6665c98bcef4c4caa66bbf46511b02d4e162cbb2cddb82cf` (`tablet-final-dev.apk`).

The final APK adds only the activity-collection entry guard after these artwork measurements: nonempty grids wait for media attachment instead of using Back as a temporary fallback. No landing-rendering/decoder/buffer changes followed the timing runs.

Earlier phase 18 probes before the final header path: Wio tablet 23/15/16 ms (7/3/4 misses), TV 34/17/17 (7/2/2); Nikhil tablet 16/14/15 (4/1/2), TV 34/19/13 (6/3/3). Setup differs from final header stops and phase 17: these are provenance, not causal deltas. The TV Home-style probe 19/8/7 ms lacked a completed matched compact comparison when testing paused and is not a new baseline.

Phase 17 historical median p95: tablet 16 ms both profiles, TV 18 ms both profiles. Historical SmartTube vertical results (tablet 12 ms; TV 14 warm/24 first) use unmatched content. Phase 16's idle-rotation pipeline tail and long-soak coverage remain limitations. No SmartTube parity, leak-free guarantee or zero-stutter claim follows.

## Resumed TV sign-off

First resumed run: 11/13 passed; Options failed through global accessibility, and drawing layers were already off before the pressure simulation. An isolated pressure rerun failed initial card focus. The shell test now reads its own Compose roots, including dialogs. Both isolated tests then passed with all cache/focus assertions retained, followed by a complete **13/13 pass**. Preserve `tv-resumed-13-tests.txt` and `tv-resumed-pressure-isolated.txt`; their cache/setup failure was not reliably reproduced and is not assigned a proven production cause. Production code was not changed to force cache activation or bypass resource pressure.

The requested landscape TV/tablet checks are complete for this phase. Actual portrait, prolonged soak and the intermittent exit-dialog observation remain follow-up coverage; failed lint remains explicit. No automatic TV input worker is left running.

Phase 19 optional focus-driven artwork is not part of this phase.
