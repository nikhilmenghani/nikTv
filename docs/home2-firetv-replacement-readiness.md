# Home 2 Fire TV replacement review

Update 2026-11-03: [blocker follow-up](home2-blocker-resolution.md) records the new focus/return fixes, complete device results and final loading measurements. The November 2 findings below are retained as historical evidence; use the follow-up for the current gate.

2026-11-02. Resumed at the user's request after the pause. **Do not replace/remove Home yet.** Warm Home 2 browsing is substantially faster, but the external IPTV-series return path and an intermittent focus failure under simulated memory pressure remain open. No migration, Release installation, commit or push was performed.

## Candidate and scope

Baseline commit: `988ceed3`, plus the pending loading-placeholder work and this review's focused row-reveal fix. Original Home retains its existing presenter and behavior. Real-app timing uses optimized Dev (`performanceBuild=true`, debug code disabled), not Release/R8. Public/private caches were retained; profiles, categories, history and credentials were not cleared. A mistaken hero favorite toggle during an original-Home navigation check was immediately undone and the original plus icon verified.

- Initial measured APK: `tv-skeleton-dev.apk`, SHA-256 `d5feb1807a2693e77650e4482b6b382b75804b264811fb91e40bff0168a47591`.
- Final measured/installed APK: `tv-row-reveal-fixed-dev.apk`, SHA-256 `8218d75f341af71e735bb514a0ad3ba197ed13844ddf78186bff8189573217c7`.
- Evidence: `D:/workspace/niktv-playback-tests/home2-phase18/`, with real-app captures/metrics in `tv/`. Reports and captures stay outside git.

## Reproduced issue and focused fix

On Wio, three Down/three Up from the first Continue Watching tile returned the correct cursor but left the tile's lower portion outside the screen. The crop persisted after five seconds; pressing Right brought the next tile fully into view. Captures: `skeleton-wio-timing-origin.png`, `skeleton-wio-home2-return.png`, `skeleton-wio-reversal-5s.png`, `skeleton-wio-after-right.png`.

`RowHolder.focus` in `RecycledDiscoveryRows.kt` now requests the **complete category row** from its vertical owner, including heading/focus padding, after the existing horizontal visibility correction. This avoids relying on a card rectangle propagated through nested horizontal containers for vertical placement. It adds no polling, extra asynchronous work or network requests. The failing real-app sequence now returns a complete row beneath the header on both profiles (`row-reveal-wio-return.png`, `row-reveal-nikhil-return.png`). This verifies the observed result; the simplified/full-shell local fixtures did not independently reproduce the original persistent crop, so they do not establish its precise framework cause.

Added regression coverage exercises Home-height hero geometry and repeated reversals in the real hero shell. Existing exact viewport-return, held-action and row-tail tests remain enabled.

## Matched navigation measurements

Same final optimized APK within each profile comparison. Verify the first Continue Watching identity before timing; issue three Down/three Up at a requested 250 ms cadence, three runs per screen. Both screens traverse the corresponding three rows, with their own intended layout/header policies. Wio has a New Episodes row; Nikhil does not, so profiles are not pooled. First traversal is **not** a cold-cache run; separate it from repeats. No builds, recording, tracing, screenshots, hierarchy inspection or other device operations during timing. Shell overhead means the requested cadence is not the actual event interval; raw input timestamps are retained.

| Profile/screen | First traversal frame p95 | Repeat p95 runs | Deadline misses / frames across all three |
|---|---:|---:|---:|
| Wio Home | 40 ms | 38 / 32 ms | 46 / 202 |
| Wio Home 2 | 38 ms | 13 / 13 ms | 11 / 381 |
| Nikhil Home | 61 ms | 44 / 36 ms | 41 / 131 |
| Nikhil Home 2 | 28 ms | 10 / 8 ms | 4 / 381 |

These are global `dumpsys gfxinfo` frame histograms, **not input-to-visible-focus latency, FPS, or an isolated one-feature causal delta**. The native presenter produces different numbers of animation frames; compare timings and misses with that limitation. Final 120-frame UI-work rings contain no >50 ms spans for these Home 2 runs, but the ring is not a whole-run trace. The original Home runs include UI-work spans over 50 ms. Evidence prefixes: `wio-row-reveal-home*`, `Nikhil-row-reveal-home*`; summary: `tv/row-reveal-final-comparison.json`.

Before the row-reveal fix, Wio Home 2 measured 38/22/17 ms p95 with the cropped return. Its different final viewport prevents treating the newer 38/13/13 ms runs as an isolated numerical cost of the fix.

### Sustained browsing

Nikhil Home 2: 144 directional inputs, three 48-input segments (vertical round trips and horizontal reversals), approximately 135 seconds. Requested spacing 180 ms; actual timestamps are retained. No playback or personal-list actions. Per-segment p95: **8/8/8 ms**; deadline misses **0/1/1**, across **3,039 frames**. Post-segment PSS: **185,415 / 184,866 / 184,339 KiB**. Memory did not steadily increase in this short run; this is not a prolonged thermal/leak soak or a precise memory delta versus Home. Cached TV drawing still trades graphics memory for less redraw work, with pressure callbacks covered separately.

## Loading placeholders

Before the additional row-reveal fix, the optimized local fixture tested 25 rows with 30 backing titles each and a deliberately simultaneous 240-title arrival. Off/on/off median physical-window FrameMetrics p95: **45.06 / 52.29 / 49.04 ms**. Enabled versus warmed return control: **+3.26 ms (~6.6%)**; run variance and batching limit causal precision. Median UI-work p95: 8.97/7.28/9.79 ms. All nine 400 ms idle intervals generated zero extra frames. Large arrival stalls occurred in both modes (up to ~118 ms total / 73 ms UI work). PSS fell again in the disabled return phase; no steady-state memory cost/leak conclusion follows.

This measurement predates the row-reveal fix; it is **not** a fresh off/on/off measurement of the final candidate. The final real-app comparisons retain placeholders enabled but use warm feeds, so they do not measure the final cold-arrival cost. Evidence: `tv-skeleton-performance.txt`, `tv-skeleton-performance.json`, `tv-skeleton-comparison.json`.

## Functional checks and failures retained

- Real optimized Dev: Wio/Nikhil entry, vertical/horizontal return visibility, long captions/source/progress presentation, anchored held menu with no release activation, IPTV Open series/details/Back, and TMDB movie/details/Back. Process restart preserves profiles/cache. No playback/audio test was necessary for this renderer change.
- **Remaining real-app issue:** from a scrolled Continue Watching rail, hold → Open series → Back restores the correct IPTV series tile, but the viewport shifts toward the hero. Source: `row-reveal-nikhil-hold.png`; return: `row-reveal-series-back.png`. This specific external-menu route is not equivalent to the local saved-state fixture or internal TMDB details. Its cause and introduction point are unproven. Do not call this exact viewport restoration a pass.
- The final fixture run is **15/16**, not a complete pass: `memoryPressureDropsLayersWithoutLosingNavigation` lost child focus during its final rapid reversal (`actual=null`, native root still focused). Isolated rerun **passes**, 7.08 seconds. This remains intermittent/unexplained; a successful isolated rerun does not erase the failed full run. Related intermittent pressure/setup failures were also recorded in phase 18 before this review.
- The other final checks include first-real-media loading handoff, stale/failing artwork, row-tail/removal actions, large-font captions, bounded recycling, exact local viewport restoration, header/hero entry and original-Home isolation. Evidence: `tv-row-reveal-final-16-tests.txt`, `tv-row-reveal-pressure-isolated.txt`.
- An earlier 15-test run before the row-reveal change passed after fixing **test setup**, not production focus: Fire OS retained touch mode despite `setInTouchMode(false)`. The skeleton fixture now waits for its window and sends a real D-pad event before local dispatch, asserting that touch mode is cleared. Earlier failures and diagnostic logs remain.
- Optimized full-shell instrumentation hit missing desugared-library methods (`DesugarTimeZone.getTimeZone`, later `LocalDate.now`) in the separate test APK on Fire OS. Standard debuggable functional fixtures run; optimized native-only measurement and the real optimized app run. Do not describe the final full-shell functional suite as an optimized-build pass or assign this test-classloader problem to the regular app.

## Verification commands

JDK 17, repository root:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.Native*' --tests 'com.nikhil.niktv.discovery.RecycledPositionTest' --tests 'com.nikhil.niktv.discovery.DiscoverySkeletonTest' --tests 'com.nikhil.niktv.discovery.DiscoveryMotionTest' -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s 192.168.2.19:5555 shell am instrument -w -r -e class com.nikhil.niktv.ui.DiscoveryExperimentDeviceTest,com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest,com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#loadingRowsAreNonInteractiveAndResolveWithoutFocusOrHeightJump,com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#artworkPlaceholderClearsOnFailureAndOldImageCannotReplaceNewSelection com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:lintDebug -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
python tools/check-source-secrets.py
git diff --check
```

Final optimized build and **25 focused unit tests pass** (`tv-row-reveal-fix-build.log`). Earlier skeleton work passed 42 relevant units; do not present those as a newly rerun suite. Lint **fails with 22 existing errors**, 359 warnings and three hints; no suppression/baseline. Error groups remain RememberInComposition 13, NonObservableLocale 4, RestrictedApi 3, SuspiciousIndentation 1, UnsafeOptInUsageError 1; none are in the changed recycled renderer. Final tests and limitations above govern readiness, regardless of build success.

Final source credential scan: zero findings; `git diff --check` passes. Temporary Fire TV fixture/test packages were uninstalled, and the regular optimized Dev app was brought back to the foreground. No automatic input worker remains. Tablet and Release were not updated during this resumed review.

## Replacement decision

Keep both entries for now. The performance case for Home 2 is strong for warm TV browsing, but complete replacement needs:

1. Fix the external IPTV menu → series → Back viewport shift and revalidate hold/menu/return paths.
2. Explain and resolve the intermittent pressure/reversal focus loss; obtain a repeatable complete functional pass.
3. Recheck the final visibility fix on tablet and finish actual phone/portrait coverage before a global replacement. Prior tablet results predate this additional fix.
4. Measure final cold-content arrival and longer resource/thermal behavior. The first-traversal and synthetic arrival spikes remain; skeletons do not remove them.

Optional focus-driven artwork (phase 19) stays deferred and is not required for the first migration. Metadata remains optional/off by default. No fresh matched SmartTube comparison or input-latency measurement was made, so do not claim SmartTube parity or production-grade zero-stutter behavior.
