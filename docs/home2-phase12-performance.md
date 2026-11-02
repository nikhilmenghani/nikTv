# Home 2 Phase 12: idle hero rotation

Implemented on top of pending Phase 11 diagnostic/switch work (HEAD 07d9f938). No new commit or push. Home/Movies/Series remain unchanged. No Fire TV access, release build or playback. Tablet only: Xiaomi Pad 5, 6889f3db, Wio, landscape, Dev. Optional title metadata remained off throughout.

## Behavior

Stage 12 adds only idle rotation to Stage 11. An eight-second cancellable delay advances one hero, then restarts for the new identity. Native view callbacks report hero visibility (at least 80% of header height), window/content focus, vertical/horizontal scrolling and touch state. Popup and lifecycle state also gate the timer. Any blocked state cancels the timer; eligibility returning starts a fresh eight seconds. Reduced-motion configuration disables automatic rotation. No adjacent artwork prefetch, additional metadata lookup or provider search was added. Existing manual carousel/actions remain.

Only the single may-rotate boolean crosses into the screen's Compose state. Native movement details must not trigger full-feed recompositions when eligibility stays false. Detached horizontal rows are removed from the busy set. Previous routes/stages use no callback; original Home does not use this experimental presenter.

## Regression found and corrected

The first implementation observed the entire HeroActivity object at screen level. Visibility/scroll changes caused parent recompositions even with focused content already blocking rotation. It measured 26/25/25 ms p95 versus a repeated Stage 11 18/16/17 ms. Changing the observed state to eligibility alone brought the Stage 12 result to 16/16/16 ms. This experiment supports a concrete state-granularity cost here; it does not prove the earlier Phase 11 metadata problem has the same cause.

## Final scrolling comparison

Same eligibility-dev.apk for all final comparisons, hash in external sha256.txt. Hero Play entry, Down x3/Up x3, requested 250 ms cadence; no recording/tracing. Metadata off. Short samples, preserved caches and partial frame rings; global counters are used. These are frame statistics, not input-to-focus latency or a SmartTube parity result.

| Final APK mode | p95, three runs | Median | Deadline misses / frames |
|---|---|---:|---:|
| Stage 11 initial control | 26 / 19 / 17 ms | 19 ms | 10 / 541 |
| Stage 12 eligibility-only observer | 16 / 16 / 16 ms | 16 ms | 9 / 551 |
| Stage 11 repeated control | 18 / 15 / 14 ms | 15 ms | 8 / 554 |

Relative to the warm control, median p95 is +1 ms (about 7%), and misses are 1.63% versus 1.44%. This small sample cannot establish a consistent regression. First-exposure control spikes remain. The initial observer candidate and final eligibility candidate have separate APKs/evidence; do not pool them as one version.

## Idle artwork transition remains a limitation

Actual screenshots confirm that a focused hero stays on its title for over nine seconds, an open Options popup holds the same title, and an idle visible hero advances from A Story of Derry to Dexter while the navigation tab remains focused. Final popup-before/after and fixed-idle-before/after screenshots provide the evidence. Timer duration is configured to eight seconds; no exact pixel-onset timing was measured.

The first candidate's idle window included a 101.849 ms frame. The final candidate's ten-second idle window contained 38 rendered frames, two missed deadlines, one slow bitmap upload counter increment, and a worst retained pipeline frame of 172.239 ms. Global histogram p95 was 73 ms; retained-ring quantiles differ at this small sample size. This is an unresolved idle-transition hitch, not a navigation result or a claim that decoding/upload alone caused it. A control idle window also had a 58.9 ms frame. Do not subtract these unmatched isolated maxima or call rotation lag-free. Adjacent prepared artwork and isolating hero rendering from feed recomposition are future experiments, not implemented fixes.

## Checks and delivery

- Normal Dev build and 202 targeted unit tests passed, zero failures/errors. Route isolation includes Stage 12; cumulative stage test asserts only rotation is added. Existing HeroActivity tests cover every blocked condition.
- Four isolated tablet device regressions passed in 15.369 s: recycling/restoration, full tile visibility, native/Compose focus handoff, original Home isolation, and new assertions for focused/offscreen/idle hero eligibility. Fixture APK was built before the final parent-observer reduction; native callback code tested is unchanged. Temporary regression packages removed.
- Applicable final lint failed with 22 errors, 348 warnings, three hints. All 22 error source lines exist in HEAD (lint-audit.json); no suppression. Credential scan zero findings; whitespace checks passed.
- Installed final eligibility-dev.apk and left Home 2 on Stage 12, hero focused, metadata off. No personal data/cache clearing. Touch/background/reduced-motion device scenarios, Nikhil profile, portrait and extended thermal/memory behavior remain unmeasured; native gates and policy tests alone do not establish all device cases.

Commands (JDK 17):

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s 6889f3db shell am instrument -w -r -e class com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest,com.nikhil.niktv.ui.DiscoveryExperimentDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
gradlew.bat :app:lintDebug -PskipLocalGithubToken=true --console=plain
adb -s 6889f3db install -r D:/workspace/niktv-playback-tests/home2-phase12-tablet/eligibility-dev.apk
python D:/workspace/niktv-playback-tests/home2-phase12-tablet/fixed-compare.py
python D:/workspace/niktv-playback-tests/home2-phase12-tablet/final-behavior.py
python tools/check-source-secrets.py
git diff --check
```

Evidence: D:/workspace/niktv-playback-tests/home2-phase12-tablet/. Initial final-dev.apk uses the detailed observer; eligibility-dev.apk is the optimized final build. control/rotation/repeat runs use the initial APK; fixed-* runs use the optimized APK. The initial idle collector attempt lacked an origin label and failed before capture; completed control runs were preserved and remaining stages resumed after correcting the script. No failed capture is counted as a result. Build logs, tests, raw counters, input timestamps, screenshots and APKs remain outside the repository.
