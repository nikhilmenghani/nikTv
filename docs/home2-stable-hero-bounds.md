# Next step: stable hero bounds

2026-11-02. Pending work was reviewed, credential-scanned and committed as `4e8f79e3` before this step. Nothing pushed. This experiment is uncommitted. Focus-driven artwork/backgrounds are explicitly last priority; no such feature was added.

## Scope and priority

Continue one performance change at a time: finish the idle transition investigation, then evaluate remaining visual/navigation polish individually and validate across profiles/devices before migration. Optional focus-driven backgrounds come last. Existing Home is preserved. Fire TV remains off limits until explicit permission. Only tablet 6889f3db, Wio, Dev was tested; no playback, release builds, cache clearing or private-provider probing.

## Changes tested separately

A. Fix the Compose hero frame at its existing landscape height, scaled upward for larger fonts. Control remains the existing isolated hero. This prevents local layout from choosing a different frame height as titles change.

B. Keep A, then constrain the hosting native ComposeView to the same exact pixel height instead of WRAP_CONTENT. Control is A on the same second APK. This isolates the native measurement boundary. Both sizing modes are diagnostic-only. The final implementation excludes phones; landscape tablet/TV gets the experiment, while other devices retain prior behavior. Normal fonts keep the existing 300 dp frame on this tablet. Larger-font/portrait device rendering remains unverified, though unit coverage checks sizing arithmetic.

No artwork preparation, metadata loading, tile redesign or row-focus background changes were mixed into these comparisons. Metadata and optional next-image preparation stayed off.

## Tablet measurements

Same starting hero and feed via Home/Home 2 route reset; two Down x3/Up x3 traversals per block at requested 250 ms cadence. Each idle sample holds a visible hero with navigation focused for ten seconds, containing one rotation. No recording/tracing during measurement; public caches retained. Raw frame rings are complete for these small idle windows, but scrolling rings are partial. Pipeline maxima are not input latency or precise Kotlin function attribution.

| First APK: Compose frame | Worst idle pipeline frame | Misses / frames | Scrolling p95 runs |
|---|---:|---:|---|
| Existing control A | 69.090 ms | 3 / 38 | 25 / 15 ms |
| Fixed Compose frame A | 107.906 ms | 3 / 38 | 24 / 17 ms |
| Fixed Compose frame B | 104.646 ms | 3 / 38 | 17 / 16 ms |
| Existing control B | 95.637 ms | 3 / 39 | 23 / 15 ms |

| Second APK: native constraint | Worst idle pipeline frame | Misses / frames | Scrolling p95 runs |
|---|---:|---:|---|
| Compose-only control A | 85.956 ms | 3 / 38 | 20 / 16 ms |
| Native exact height A | 100.371 ms | 3 / 39 | 20 / 18 ms |
| Native exact height B | 93.783 ms | 2 / 39 | 21 / 15 ms |
| Compose-only control B | 90.996 ms | 3 / 38 | 21 / 15 ms |

Neither change consistently improved the transition. Do not promote it as a performance fix. In the native comparison, candidate maxima averaged about 97 ms versus 88 ms control, but two windows per mode do not establish a fixed +9 ms cost. Navigation remains within observed variation; the idle hitch remains. Historical 172 ms frames from earlier sessions are not a matched baseline for this run.

This narrows out header sizing as a useful stand-alone fix. Text-layout costs and renderer synchronization from the prior method trace remain the next isolation targets. No speculative feature was enabled to hide the remaining hitch.

## Verification and delivery

- Normal Dev build passed; 204 targeted unit tests passed with zero failures/errors. New coverage checks baseline/larger-font sizing and original-route protection includes the diagnostic variants.
- Five isolated tablet instrumentation tests passed, including the new exact-header focus/navigation/detail-return test, recycling/visibility checks and original Home isolation. Temporary .regression and .regression.test packages removed afterward.
- Lint failed with 22 existing errors, 350 warnings and three hints. All error source lines exist in HEAD 4e8f79e3 (lint-audit.json). Warnings are reported as current totals, not all claimed pre-existing. No suppressions. Credential scan zero findings and whitespace checks passed.
- Latest native-dev.apk installed on tablet. Returned Home 2 to the prior isolated-hero baseline; both unsuccessful sizing options are off, metadata off. No Home migration or Fire TV interaction. The experiment remains available for review, not a new default.

Exact commands (JDK 17):

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s 6889f3db shell am instrument -w -r -e class com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest,com.nikhil.niktv.ui.DiscoveryExperimentDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
gradlew.bat :app:lintDebug -PskipLocalGithubToken=true --console=plain
adb -s 6889f3db install -r D:/workspace/niktv-playback-tests/stable-hero-bounds/native-dev.apk
python D:/workspace/niktv-playback-tests/stable-hero-bounds/compare.py
python D:/workspace/niktv-playback-tests/stable-hero-bounds/native-compare.py
python tools/check-source-secrets.py
git diff --check
```

Evidence: D:/workspace/niktv-playback-tests/stable-hero-bounds/. diagnostic-dev.apk contains the Compose-only comparison. native-dev.apk contains the native-boundary comparison and phone exclusion. First and second APK results are kept separate. Raw counters, screenshots, host timestamps, APKs and logs stay outside git. No new implementation commit or push.
