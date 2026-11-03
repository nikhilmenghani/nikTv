# Home 2 bulk-arrival investigation — November 3, 2026

Baseline: `b68e8652`, isolated checkout `nikTv-home2-bulk-loading`. Original Home, tablet, Release, provider requests and playback remain outside this change. Evidence and immutable APK snapshots are under `D:/workspace/niktv-playback-tests/home2-bulk-loading`, outside git. No commit, push or default-screen migration is included.

## Finding and accepted change

The concentrated arrival stall reproduces with **no images or network**. A sampled main-thread trace locates substantial work in nested rail layout and card construction. The old passive three-dp progress decoration constructs an Android `ProgressBar`, including theme/style lookup, layered drawables and animated-vector resources even when the card has no progress to display. In the diagnostic interval, card construction accounts for 175.3 ms inclusive sampled CPU and `ProgressBar` construction for 51.0 ms. These are interval totals, not individual-card latency or unbiased benchmark timings.

Replace that decoration with `NativeDiscoveryProgress`, a small `View` drawing a track and red fraction using a retained Paint. It keeps the same reserved height, red color, missing-progress visibility, fraction clamping, RTL direction and accessibility progress range. It does not animate, allocate during drawing, request layout when progress changes, or receive focus. No artwork, title/subtitle, category, navigation, shadow or playback feature is removed. The original Home presenter does not use this class.

A separate candidate trace records 89.4 ms inclusive sampled card construction and 164.7 ms rail layout versus 175.3 / 277.3 ms in the earlier diagnostic run. Sampling, warm-up and different execution paths prohibit treating those totals as a precise causal percentage. The non-traced comparison below is the performance evidence.

## Controlled Fire TV comparison

Device: AFTKRT / ARMv7. Optimized Dev fixture, actual package flags checked non-debuggable. Same native-only fixture and input: 25 rows × 30 records, eight loading rows receive 240 records at 1,200 ms; eight Down / eight Up at requested local 160 ms cadence. Each batch has three samples with skeletons off, three on, three off again. No recording, profiling, images, provider traffic, concurrent builds or other-device tests during measurement. FrameMetrics collection is identical across these runs. Detailed per-frame JSON and method sampling are disabled in benchmark runs.

| Build/run, in execution order | Median frame p95: off / on / off (ms) | Worst UI work (ms) | UI-work frames >50 ms, nine samples |
|---|---:|---:|---:|
| Original | 43.87 / 51.16 / 48.48 | 77.47 | 14 |
| Progress strip | 31.63 / 38.02 / 38.64 | 51.73 | 2 |
| Original reversal | 37.95 / 37.81 / 37.33 | 68.74 | 10 |
| Progress strip repeat | 38.05 / 38.08 / 41.68 | 51.92 | 2 |
| Final progress strip, including accessibility range | 38.11 / 38.23 / 38.01 | 47.39 | 0 |

The reversal demonstrates substantial run-order variation in frame p95: **do not claim a consistent p95 speedup**. Reduction in concentrated UI-work spikes repeats across the candidate runs. The final batch reaches the under-50-ms UI-work target in this fixture; earlier candidate batches still contain occasional ~52-ms work. This is a short stress test, not a guarantee for every device or real feed.

All final 400-ms idle intervals produce zero frames. Final batch PSS peaks at 128,916 KiB and ends at 86,268 KiB; GC and short-run variation prevent a memory-saving or leak-free claim. Frame p95 remains around 38 ms, and neither this metric nor global gfxinfo measures input-to-visible-focus latency. No fresh SmartTube parity claim is supported.

## Discarded experiment

Explicitly styling card TextViews without their default Android textViewStyle yielded 38.52 / 37.75 / 37.98 ms median frame p95 and a 51.57-ms worst UI-work frame. Long-caption/larger-font checks passed, but there was no clear additional p95 benefit. It is reverted to preserve caption styling. No buffer, pool size, cache limit or prefetch setting is changed.

## Verification and scope

The added deterministic drawing test checks missing progress, exact fraction/color boundaries, RTL, range clamping, accessibility range and reserved height. RTL is enabled only in its local Context wrapper because the app manifest does not opt in; the first test attempt correctly failed to exercise RTL until this fixture setup was corrected. That failed attempt is retained and is not counted as a pass.

Applicable discovery/navigation units: **227 tests, zero failures or errors**. The complete focused Fire TV functional suite passes **21/21 in 93.592 s**, including external-series returns, held menus, rapid reversals, memory-pressure recovery, captions/larger fonts, stale artwork, loading handoff and the new progress drawing test. Optimized fixture and test APK builds pass. Source credential scan: zero findings; whitespace check passes.

Normal optimized Dev `assembleDebug` succeeds. The combined build/lint invocation exits 1 because **lint fails with 22 errors, 359 warnings and three hints**, matching the previous reported totals. No errors point to the changed files; no baseline or suppression was added. The existing RailRecycler ViewConstructor and configuration-size warnings remain. The failed lint stage is not a pass.

The normal optimized Dev APK is installed on Fire TV, with installed flags verified non-debuggable. SHA-256: `30c90338b56e0651f87b34ac89ac863cbc246f757adca48e67b9e186abdbb32d`. Its source is the committed baseline plus this discovery fix; concurrent unrelated canonical alternate-source/sports/player edits were preserved and excluded from the isolated build. Temporary regression packages and the temporary device UI hierarchy file were removed. Release, tablet and personal app data remain untouched.

Regular-app Nikhil and Wio smoke checks exercise Home 2 entry, rows and directional input without playing media. Wio's bounded Down/Up and Right/Left route returns to the visible first Continue Watching tile; screenshots show readable captions, source badges, intact borders and red progress. These are **visual/functional checks, not matched profile performance measurements**. UIAutomator does not expose native focused nodes here, so empty before/after focus lists are explicitly not counted as a successful automated assertion. An initial multi-row Nikhil burst reached a feed boundary; it is not an exact viewport-return assertion. The deterministic 21-test fixture suite supplies the focus/return assertions.

The diagnostic test options (`sampleMethods`, `detailFrames`, `bulkRounds`, `cacheTiles`) are opt-in and test-only. Trace chunks are bounded and written to host files, never emitted into chat. No diagnostic switch or tracing is enabled in the normal app.

Commands, JDK 17:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' --tests 'com.nikhil.niktv.ui.TileMenuPositionTest' -PregressionBuild=true -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s 192.168.2.19:5555 shell am instrument -w -e measure true -e class com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#measureStaticSkeletonLoadingCost com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
# Separate diagnostics, never benchmark timing:
adb -s 192.168.2.19:5555 shell am instrument -w -e measure true -e sampleMethods true -e detailFrames true -e bulkRounds 1 -e class com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#measureStaticSkeletonLoadingCost com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
python D:/workspace/niktv-playback-tests/analyze-recycled-trace.py TRACE_FILE
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PperformanceBuild=false -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s 192.168.2.19:5555 shell am instrument -w -e externalRuns 5 -e class com.nikhil.niktv.ui.DiscoveryExperimentDeviceTest,com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest,com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#loadingRowsAreNonInteractiveAndResolveWithoutFocusOrHeightJump,com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#artworkPlaceholderClearsOnFailureAndOldImageCannotReplaceNewSelection,com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#passiveProgressRetainsFractionDirectionAndReservedSpace com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
python tools/check-source-secrets.py
git diff --check
# Normal app build and lint (assemble succeeds; lint fails as reported):
.\gradlew.bat :app:assembleDebug :app:lintDebug -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
```

Full-shell functional instrumentation uses a debuggable fixture because the separate optimized full-shell test APK has the previously recorded FireOS desugaring collision. Native performance uses the verified non-debuggable fixture. Neither test changes personal app data.

## Readiness

This addresses a measured bulk-loading cost without reducing the feed or removing functionality. It does not by itself close the prior phone landscape/exact touch-return and longer resource/thermal gates. Original Home remains unchanged and available; optional focus-driven artwork remains deferred. Keep Home 2 as a trial until the remaining readiness checks are complete. See [previous blockers](home2-blocker-resolution.md).
