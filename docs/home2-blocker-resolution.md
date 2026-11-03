# Home 2 blocker follow-up - 2026-11-03

Baseline commit: `6d722926` (loading placeholders and November 2 readiness report). No push, Release update or default-screen migration. Original Home retains its renderer/presentation. Builds use `nikTv-home2-blockers`, an isolated checkout excluding unrelated pending player-queue edits. These fixes form a separate follow-up to the baseline.

## Fixes and reproduced causes

- Retain the saved viewport through external-series remount. Guard implicit reveal in both RecyclerView's rectangle callback and `LinearLayoutManager.onRequestChildFocus`. RecyclerView's internal focus path calls the LayoutManager directly, bypassing the View override. During restoration or explicit pending navigation, the row coordinator owns reveal. Zero-sized layouts do not consume restoration.
- Ignore late hero reveal requests when the committed/pending selection is a media row. The initial View-only guard and subsequent measured-offset correction were insufficient in the real app; the measured correction was removed after locating the bypass. No ongoing correction loop remains.
- Settle interrupted focused-tile scale when dropping its drawing layer, instead of retaining an arbitrary intermediate scale after a popup/window handoff.
- Retain the latest pending row through temporary Compose-host focus parking. Resolve stable row/item identities after layout. Cancel on touch, Tab or explicit header exit. Do not compete with another native control, another window or hero entry.
- A selected holder can lose focus after its pending request completed. Keep native key dispatch in the island during that recycling handoff and route arrows through the committed cursor. Recovery is event-driven and bounded, with no idle polling loop.
- Give anchored menus explicit remote entry, guarded against resetting a user's first arrow during delayed setup. Original Home uses the unchanged unanchored dropdown branch.
- Optional bounded navigation tracing is disabled normally and during timing. No player/provider/stream behavior changes are included.

Initial full runs failed despite isolated passes. A deterministic host-parking regression reproduced `AndroidComposeView:null` with the window still focused. An external-return fixture reproduced a 104 px shift. Those failures remain in the evidence directory.

Fixture corrections: use twelve rows for offscreen handoff (three can fit on tablet); supply the real app's alternate-source callback as a local no-op; assert menu-option focus and wait for the popup window to own input before the first arrow. These callbacks make no network requests or playback. Earlier incomplete/failed runs are not counted as passes.

## Real-route verification

The Nikhil Fire TV held-menu / Open series / Back route now preserves its viewport after the LayoutManager fix. Before: hero anchor saved as -554 px, but the layout started at +20 px and settled near -122 px. After: first measured layout and final restored tile retain -554 px (hero top -534 px including padding). Source/return screenshots match tile position. Evidence: `blockers-layout-owner-real-trace.txt`, `tv/blockers-owner-origin.png`, `tv/blockers-owner-back.png`.

Diagnostics were temporary isolated builds with row indices/window flags/offsets, excluding provider identities and credentials. They are excluded from performance timing and replaced by the final non-tracing Dev APK.

## Device coverage

Before the final LayoutManager addition, the complete focused suite passed 19/19 on Fire TV (106.346 s) and tablet landscape (98.970 s), each with ten external-series returns and 20 rapid three-Down/three-Up reversals. Tablet portrait passed 19/19 in 88.828 s with five external returns. The expanded final suite adds fixed-height hero viewport restoration. It passes 20/20 on Fire TV in 101.227 s and 20/20 on tablet landscape in 96.112 s, including five external returns and 20 reversal bursts per device.

Coverage includes pending and settled focus parking, header cancellation, whole-hero/first-row visibility, both viewport axes, long captions/larger fonts, reduced motion, held-button release, row-tail/removal actions, loading handoff and stale artwork.

Samsung received optimized Dev. Portrait artwork/captions/source badges/progress and bottom safe areas were checked; touch hold / Open series / Back worked without playback. Exact settled phone viewport restoration, phone landscape and uninterrupted final profile coverage remain incomplete; notifications/changing foreground state prevented a clean comparison. Do not call those passes. Real Wio Home 2 tablet portrait touch scrolling/captions/safe areas were checked. Original tablet rotation settings (accelerometer=1, user_rotation=0) were restored.

## Loading cost before final LayoutManager fix

Optimized native-only local fixture, no recording/tracing/images/provider traffic. 25 rows x 30 records; 240 records arrive together at 1.2 s; eight Down/eight Up at requested local 160 ms cadence. Three samples each off/on/off. This concentrated arrival stressor is not real cold-network startup or input-to-focus latency.

| Device/mode | Frame p95 samples (ms) | Median p95 (ms) | Maximum UI work (ms) |
|---|---|---:|---:|
| TV off, first | 43.92 / 37.82 / 37.67 | 37.82 | 70.23 |
| TV on | 38.23 / 39.05 / 37.87 | 38.23 | 54.66 |
| TV off, return | 38.01 / 40.03 / 40.40 | 40.03 | 53.16 |
| Tablet off, first | 20.36 / 20.20 / 20.60 | 20.36 | 120.28 |
| Tablet on | 20.02 / 27.82 / 23.06 | 23.06 | 101.03 |
| Tablet off, return | 15.99 / 20.32 / 17.98 | 17.98 | 116.38 |

All 400 ms idle intervals produced zero frame callbacks. TV PSS rose from 85,221 to 132,696 KiB, fell to 90,644 KiB and ended at 94,222 KiB. Tablet rose to 210,849 KiB then fell to 154,332 KiB. These are short allocation/collection sequences, not long-soak/leak conclusions. Different display cadence and TV-only tile caching prohibit pooling devices. Placeholder cost has no consistent TV penalty across this order; tablet shows run/order variability. Bulk-arrival UI-work spikes remain above the desired 50 ms ceiling.

An earlier tablet attempt failed to install its missing app artifact and ran the retained debuggable app against a new test APK. The test's inlined BuildConfig flag was misleading. That measurement is excluded. The valid rerun used immutable APK snapshots, checked install exits and verified actual installed package flags. Evidence: `blockers-tablet-cold-verified-performance.json`, `blockers-tablet-optimized-flags.txt`; TV: `blockers-tv-cold-performance.json`.

The November 2 45.06 / 52.29 / 49.04 ms TV medians used an older row-reveal implementation and are historical context, not a clean one-feature causal delta. Final post-LayoutManager results must be distinguished from the table above.

## Commands and checks

JDK 17, isolated checkout. Preparation tasks inspected: native-runtime extraction and Android dnsjava jar generation write build outputs; no integrated Flow source rewriting.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' --tests 'com.nikhil.niktv.ui.TileMenuPositionTest' -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s DEVICE shell am instrument -w -e externalRuns 5 -e class com.nikhil.niktv.ui.DiscoveryExperimentDeviceTest,com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest,com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#loadingRowsAreNonInteractiveAndResolveWithoutFocusOrHeightJump,com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#artworkPlaceholderClearsOnFailureAndOldImageCannotReplaceNewSelection com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s DEVICE shell am instrument -w -e measure true -e class com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#measureStaticSkeletonLoadingCost com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:lintDebug -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
python tools/check-source-secrets.py
git diff --check
```

Optimized Dev build and 227 applicable unit tests pass. Lint fails with 22 existing errors, 359 warnings and three hints, without suppression/baseline. A corrected invocation runs test filters on the test task and lint separately; the earlier command that attached --tests to lint failed argument parsing and is not a completed check. Source credential scan reports zero findings; whitespace check passes.

Optimized full-shell instrumentation retains the previously documented separate-test-APK desugaring collision on Fire OS. Full-shell functional checks use standard debuggable fixtures; native-only performance and regular app checks use optimized Dev. Build types are not interchangeable.

All APKs, captures and raw evidence remain outside git under `D:/workspace/niktv-playback-tests/home2-phase18`. Keep Home and Home 2 separate until final functional/performance gates are assessed. Optional focus-driven artwork remains deferred. No fresh matched SmartTube/input-latency comparison supports a parity claim.


## Final results and readiness decision

Final source, no tracing: Fire TV suite 20/20 (101.227 s); tablet landscape 20/20 (96.112 s). Applicable units 227/227. Final lint still fails with the same 22 errors, 359 warnings and three hints. Final source secret scan and whitespace checks pass. Verification preceded the follow-up commit; the user authorized commit and push afterward.

Final non-tracing Dev was installed on Fire TV, tablet and Samsung, preserving personal data and Release. Temporary fixture/test packages were removed on TV/tablet. Tablet rotation and the temporary TV log-tag setting were restored. No automatic test/input worker remains. Final APK identities are recorded in `blockers-artifacts/final-identity.json`: TV SHA-256 `5622747b968979160cd6c4aee49fd3c2759a06495e72687a7469397b87413cc1`; ARM64 `89e5546f9bbfff0f6e461edd57a84ad6b30fcd79ea25d980ec89c81be0deb120`.

The real Wio held-menu / Open series / Back route also preserves its viewport on the final non-tracing APK: `tv/blockers-wio-final-origin.png` and `tv/blockers-wio-final-back.png`. Nikhil was verified with the sanitized diagnostic build of the same navigation fix; do not describe that diagnostic run as timed production performance.

Final native-only loading measurement, same off/on/off fixture, verified installed optimized package flags:

| Device/mode | Frame p95 samples (ms) | Median (ms) | Max UI work (ms) |
|---|---|---:|---:|
| TV off, first | 46.15 / 41.81 / 37.95 | 41.81 | 69.28 |
| TV on | 33.23 / 38.17 / 38.34 | 38.17 | 57.19 |
| TV off, return | 46.43 / 47.28 / 46.67 | 46.67 | 75.95 |
| Tablet off, first | 33.05 / 28.75 / 26.37 | 28.75 | 123.74 |
| Tablet on | 38.22 / 21.24 / 29.96 | 29.96 | 110.53 |
| Tablet off, return | 36.88 / 28.50 / 20.52 | 28.50 | 97.42 |

All idle samples again produced zero frames. TV PSS peaked at 116,567 KiB and ended at 75,797 KiB; tablet peaked at 209,762 KiB and ended at 164,122 KiB. These short sequences show allocation/collection, not long-session stability. The LayoutManager fix corrects competing navigation/restore scrolling; this stress test does not establish a cold-arrival speed improvement. Spikes remain with placeholders off and on.

Final real-app Wio warm comparison, same optimized APK, first Continue Watching identity verified, three Down/three Up round trips. No recording, tracing, builds, UI hierarchy inspection or other-device work during timing. Requested 250 ms cadence differs from actual shell dispatch intervals; raw input timestamps retained.

| Screen | First traversal frame p95 | Repeated p95 | Deadline misses / total frames |
|---|---:|---:|---:|
| Original Home | 40 ms | 40 / 29 ms | 35 / 197 |
| Home 2 | 26 ms | 8 / 10 ms | 7 / 381 |

These global gfxinfo histograms are not input-to-focus latency, FPS, or a clean causal before/after feature delta. The two presenters generate different animation frame counts. First traversal is not a cold-cache run. Current Nikhil warm timing was not repeated after the final LayoutManager change; its earlier measurements remain historical context. Evidence: `tv/blockers-final-wio-comparison.json` and input/metric records.

**Do not replace Home globally yet.** Navigation blockers are fixed and passing in this batch, and warm browsing favors Home 2. Remaining gates: profile/phone final landscape and exact settled touch-return coverage; longer resource/thermal navigation runs; profile-matched final performance repetition as needed; profile/trace concentrated arrival UI work and bring its 50+ ms spikes down. No fresh SmartTube or input-to-visible-focus comparison establishes parity. Optional focus-driven artwork remains deferred and is not needed to address these blockers. Original Home remains available and unchanged.
