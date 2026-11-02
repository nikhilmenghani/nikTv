# Phase 13 — native tile activation and focus

Home 2 only; original Home and playback are unchanged. Optimized Dev (not R8 Release), optional hero metadata off, Stage 12 on Xiaomi Pad 5 and cached-tile TV candidate on Fire TV. Both profiles retain separate history and navigation state.

## Changes

- Remote short/held actions commit on key release. A held release cannot activate the popup's first option; canceled input, focus loss and window replacement invalidate the press.
- Keep the native popup's composition alive when its menu closes, so nested description dialogs survive. Restore the actual originating card, including after touch input, with route-scoped retries and owner-scoped disposal.
- Correct partially visible attached cards before restoring horizontal focus. Rapid reversals previously left a correctly identified card only 19 pixels visible on TV.
- Defer the Compose hero's entry until the native bridge owns focus. A successful request inside Android's descendant search could otherwise be overwritten by the outer anchor.

## Verification

`./gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain`: build passed; 211 tests, zero failures/errors.

Isolated fixture build: `:app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true`. Instrumentation: `am instrument -w -r -e class com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner`. Final tablet and TV runs: 5/5 each. Coverage includes 3,200 records with fewer than 100 materialized cards, short/held/canceled activation, touch-to-remote restoration, details return, hero/header entry and rapid reversal with full viewport visibility.

Earlier runs failed cropped-reversal, touch restoration and hero entry assertions. These led to fixes; they are not counted as passes. Temporary fixture apps are removed after checks. Main Dev data and Release remain intact.

## Measurements

Evidence: `D:/workspace/niktv-playback-tests/home2-phase13/`. No screen recording during timing. Three vertical Down×3/Up×3 runs at requested 250 ms cadence, then Right×4/Left×4. Before each set, restore the first Continue Watching card and capture its identity. These are frame histogram p95 values, not input latency or FPS.

| Device / profile | Vertical p95 runs (ms) | Median | Deadline misses per run | Horizontal p95 |
|---|---|---|---|---|
| Tablet / Wio | 14, 17, 15 | 15 | 3, 2, 3 | 12 |
| Tablet / Nikhil | 19, 15, 18 | 18 | 2, 3, 2 | 13 |
| TV / Wio | 20, 19, 17 | 19 | 2, 2, 2 | 7 |
| TV / Nikhil | 24, 17, 17 | 17 | 2, 2, 2 | 7 |

The preceding tablet Phase 12 median was 16 ms in each profile: this sample is −1 ms Wio and +2 ms Nikhil. Earlier TV cached samples had 17 ms medians; this Wio sample is +2 ms. Personal Continue Watching contents changed between historical captures, and sample variance is material. These differences do not establish the cost of the input fixes. Use this phase's verified captures as the adjacent baseline for subsequent phases. Preliminary runs with missing native focus were retained under `provisional-*` and excluded.

Actual-app held-menu/Back checks passed on both devices. On tablet, touch-selecting View description displayed the nested dialog and Back restored the white outline to the originating Spider-Man tile. The Nikhil RRR item has no description action; that absence is expected rather than a popup failure.

`python tools/check-source-secrets.py`: zero findings. `git diff --check`: clean. `:app:lintDebug -PperformanceBuild=true -PskipLocalGithubToken=true` failed with the same 22 existing project errors (350 warnings, 3 hints); none are in this phase's changed files. Findings are not suppressed. Evidence: `lint.log` and the XML report.
