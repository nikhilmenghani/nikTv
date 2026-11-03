# Home 2 static loading placeholders

Baseline: `988ceed3` (the preceding row-tail changes were committed before this work). Original Home is unchanged. Evidence, sanitized captures and APKs are outside git in `D:/workspace/niktv-playback-tests/home2-phase18/`, using `skeleton-*` / `tablet-skeleton-*` filenames.

## Behavior and implementation

- Known, pending Home 2 TMDB/provider rows draw same-size poster silhouettes and caption bars. One custom drawing view per retained row paints only the viewport plus one slot, capped at twelve. No fake catalogue records, image requests, bitmap layers, shimmer, repeating invalidations or timers are added.
- Existing image requests use a static muted background until success/failure. Canceled or obsolete image work cannot clear/replace a newer selection's presentation. Failed/missing artwork keeps the existing readable text fallback.
- Cached media stays visible during refresh. Known empty results and errors stop the skeleton; definitive empty-row filtering remains unchanged. Provider loading is scoped to the current profile and selected source categories. Unknown category names continue through the existing catalogue loader rather than inventing category rows.
- Skeletons are not focusable or clickable. The loading row retains its existing focus anchor, reserves the same height, and waits for real holders to attach before removing its drawing. Normal row-tail View all/Close behavior is retained.
- Device tests reproduced a neighbor-focus race: disabling the loading title could focus the next loading row before the first card attached. Keep descendants parked until the requested real holder is ready; newer directional navigation releases that parking. The final test observes the handoff and rejects visits to neighboring titles or action controls.
- Home 2's performance menu has a default-on **Loading placeholders** switch for comparison. Disabling it removes the extra row wrapper/drawing view and artwork backgrounds. There is no shimmer in either reduced-motion or normal mode.

Main files: `DiscoverySkeleton.kt` (policy/bounded drawing), `DiscoveryUi.kt` (pending-row state and trial switch), `NativeDiscoveryRows.kt` (ephemeral loading flag), `RecycledDiscoveryRows.kt` (drawing/image/focus handoff). Tests: `DiscoverySkeletonTest.kt`, `DiscoverySkeletonDeviceTest.kt`.

## Verification

Optimized Dev and isolated fixtures have debug code disabled (`BuildConfig.DEBUG=false`). No Release build was installed. Commands use JDK 17:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.DiscoverySkeletonTest' --tests 'com.nikhil.niktv.discovery.Native*' --tests 'com.nikhil.niktv.discovery.RecycledPositionTest' --tests 'com.nikhil.niktv.discovery.DiscoveryScrollExperimentTest' --tests 'com.nikhil.niktv.discovery.DiscoveryMotionTest' -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
adb -s 6889f3db shell am instrument -w -r -e class com.nikhil.niktv.ui.DiscoveryExperimentDeviceTest,com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest,com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#loadingRowsAreNonInteractiveAndResolveWithoutFocusOrHeightJump,com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#artworkPlaceholderClearsOnFailureAndOldImageCannotReplaceNewSelection com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
adb -s 6889f3db shell am instrument -w -r -e measure true -e class com.nikhil.niktv.ui.DiscoverySkeletonDeviceTest#measureStaticSkeletonLoadingCost com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
.\gradlew.bat :app:lintDebug -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
python tools/check-source-secrets.py
git diff --check
```

Build and **42 focused units pass** (`skeleton-final-dev-build.log`). Tablet: **15 device tests pass**, 47.797 seconds (`tablet-skeleton-final-15-tests.txt`), plus the opt-in nine-sample measurement test passes. Coverage includes cached/error/empty/profile loading policy, bounded slot count, non-interactive placeholders, stable height, first-media handoff without neighbor focus, canceled/stale/failing images, row-tail navigation/removal, long captions, memory pressure, held input, hero entry, exact viewport restoration and original Home isolation.

Lint **fails** with the same 22 pre-existing errors: 13 `RememberInComposition`, four `NonObservableLocale`, three `RestrictedApi`, one `SuspiciousIndentation`, and one `UnsafeOptInUsageError`. None are located in the changed discovery files. The report also contains 359 warnings and three hints; this is not a lint pass. Source-secret scanning reports zero findings and `git diff --check` passes.

Earlier failures are retained: both devices reproduced the loading handoff failure before the fix. The first image assertion also failed because it tried reading a hardware bitmap directly; the corrected test copies it to a readable bitmap without changing production decoding. The final successful handoff is verified on tablet only.

Final Dev is installed on Xiaomi Pad 5; its fixture apps were removed after saving the sanitized preview `tablet/skeleton-loading-fixture.png`. Fire TV testing was paused at the user's request, and no TV input/install/cleanup occurred afterward. The earlier TV fixture packages remain installed but are not running; cleanup and new-build validation await explicit resumption. No private provider/playback/audio tests were made.

Final installed-app check: opened Wio → Home 2, verified **Loading placeholders** and **Home-style hero framing** are on and optional **Hero title artwork and metadata** is off. Closed the experiment popup and left Home 2 open on the tablet (`tablet/skeleton-home2-final.png`).

## Measured tablet cost

Same optimized fixture APK, off/on/off; three samples per mode. Local data: 25 rows × 30 backing titles; eight rows initially empty, all published after 1,200 ms. Each sample dispatches eight Down/eight Up locally at a requested 160 ms cadence. No recording, tracing, hierarchy inspection, image requests, provider traffic, concurrent device operations or host builds occur during timing. Arrival of 240 titles together deliberately stresses the handoff; it does not reproduce the production provider's request/batching schedule.

These are physical-window **FrameMetrics durations**, not input latency, FPS, or the earlier dumpsys histogram figures. Evidence: `tablet-skeleton-performance.txt`, parsed `tablet-skeleton-performance.json`, and `tablet-skeleton-comparison.json`.

| Mode | Frame p95 per run | Median frame p95 | Median UI-work p95 | Frames >50 ms / frames |
|---|---|---|---|---|
| Off A | 29.02 / 18.61 / 29.17 ms | 29.02 ms | 3.17 ms | 25 / 850 |
| On B | 21.26 / 20.35 / 22.58 ms | 21.26 ms | 3.02 ms | 24 / 860 |
| Off return A | 20.48 / 20.40 / 18.83 ms | 20.40 ms | 3.15 ms | 23 / 853 |

Against the warmed return control, enabled median frame p95 is **+0.87 ms (~4.2%)**. UI-work p95 has no corresponding increase, and run-to-run variation is larger than this difference; do not claim a precisely established causal cost or an improvement from the first off phase. All nine 400 ms idle samples produced **zero extra frames**, including all three enabled/loading samples. This supports keeping static placeholders enabled, not adding animation.

Large load-arrival stalls remain in both modes (up to roughly 121 ms total / 111 ms UI work). Skeletons improve loading presentation; they do not solve existing batching/scrolling stalls. PSS rose across both enabled and later disabled phases, then fell from 208,014 to 167,393 KB. A steady-state memory delta or leak conclusion is not established by this short allocation-heavy fixture. TV cost, real-provider cold cache, remote image latency and prolonged soak remain unmeasured for this change.

## Resumed Fire TV review

Fire TV testing subsequently resumed at the user's request. The [replacement review](home2-firetv-replacement-readiness.md) records TV off/on/off results, final real-app Home/Home 2 comparisons on both profiles, a cropped-row visibility fix, successful and failed functional runs, and remaining replacement blockers. Statements about Fire TV being paused/unmeasured above describe the earlier tablet-only checkpoint.
