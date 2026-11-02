# Optimized tablet browsing comparison

2026-11-02. Pending sizing/TV experiments committed first as `72f18771`. This step uses Xiaomi Pad 5 `6889f3db` only. Fire TV, Release, normal Home implementation and playback engines were not modified. No push.

## Controlled comparison

Installed ARM64 Dev with `performanceBuild=true`; package flags confirm `DEBUGGABLE` is absent. This matches the TV trial's build configuration, but is not the identical TV APK: ABI differs and this build adds a diagnostic preset. All new tablet comparisons use the same APK, profile-scoped saved data and requested input pattern. No data/cache clearing, recording, tracing or playback during these tests.

Added **Control - TV presentation** in Home 2's original-renderer comparisons. It retains Compose rows/cards and full feed budgets while enabling the existing TV landing policy: no focused hero presentation/prefetch, paused optional hero metadata, immediate tile scale/border, and the existing minimal row-reveal behavior. The hero backdrop remains enabled. It does not emulate TV hardware, display timing or every device-specific layout choice. Original routes always resolve to CONTROL; a regression test protects that isolation.

Compared unchanged Home, Phase 12, the TV-policy control, then repeated unchanged Home. Each vertical group contains three Down-three/Up-three runs, requested 250 ms spacing and a 650 ms tail, matching the latest TV driver protocol. Same first Continue Watching identity within each profile: Spider-Man on Wio, RRR on Nikhil. Optional Home 2 title metadata stayed off; TV tile-layer caching remains disabled on this non-TV device. No new presentation feature or default migration was introduced.

## Results

Global frame histogram p95, per run; lower is better. These are not input-to-visible-focus latency or FPS.

| Profile | Mode | Three p95 runs | Median |
|---|---|---|---:|
| Wio | Original Home | 38 / 38 / 36 ms | 38 ms |
| Wio | Phase 12 | 22 / 15 / 16 ms | 16 ms |
| Wio | Compose TV-policy control | 28 / 48 / 48 ms | 48 ms |
| Wio | Repeated original Home | 28 / 28 / 30 ms | 28 ms |
| Nikhil | Original Home | 42 / 30 / 30 ms | 30 ms |
| Nikhil | Phase 12 | 18 / 16 / 15 ms | 16 ms |
| Nikhil | Compose TV-policy control | 36 / 34 / 38 ms | 36 ms |
| Nikhil | Repeated original Home | 30 / 30 / 31 ms | 30 ms |

Single horizontal Right-four/Left-four samples: Home 38/31 ms, Phase 12 15/13 ms, TV-policy control 42/44 ms for Wio/Nikhil respectively. One sample per mode/profile is not a reliable horizontal performance distribution.

Phase 12 retained a lower vertical p95 on both profiles. Against the warm repeated Home controls, medians were 16 versus 28 ms on Wio and 16 versus 30 ms on Nikhil. This remains a whole-screen renderer/layout comparison, not an isolated Kotlin-function cost.

The earlier roughly 100 ms Home samples did not reproduce under the new conditions. Do not attribute the entire difference to disabling debugging: APK revision, warm-up, content, viewport and timing conditions also differ. Use these fresh results as the current tablet baseline.

Applying the TV presentation policy alone did not consistently reduce the histogram p95. This prevents treating the previously identified TV code path as a complete explanation for the cross-device gap. The policy bundles several behaviors and changes frame counts/scroll alignment. In particular, immediate-focus horizontal control renders eight frames versus hundreds of animated frames in other modes; comparing histogram percentiles alone cannot establish perceived input smoothness or precise speedup.

## Limits and next work

- First identity/input protocol and build settings are controlled, but pixels are not matched: Home focuses its first row near y=133; the TV-policy control retains hero content with its first row near y=887; native Phase 12 has its own header/card geometry. These are actual route behaviors, not identical-view microbenchmarks. The initial native origin and returned viewport also differ naturally.
- Tablet resolution/refresh, ARM64 runtime and local histories differ from Fire TV's ARMv7/1080p app environment. No new direct Fire TV comparison was run. Existing TV results remain separate evidence.
- Frame rings are bounded; global counters cover each reset interval. Sparse or more numerous animation frames change the distributions. No pixel input-to-focus latency, new touch benchmark, cold cache or long memory/thermal soak was measured.
- A menu-selector attempt swiped outside the effective scrollable content and failed before measurement. Corrected to the observed content bounds; no failed capture is counted as a pass.
- Do not migrate Home, Movies or Series yet. Next validation should measure visible focus response and sustained memory/thermal behavior, especially TV cached tile drawing. Hero-transition and real-remote long-press limitations remain as recorded in the prior reports. Focus-driven backgrounds remain last priority.

## Checks and delivery

Optimized Dev build and **207 targeted unit tests** passed, zero failures. Source credential scan zero findings; whitespace checks passed. Final applicable lint failed with 22 errors, 350 warnings and three hints. All error source lines exist in HEAD `72f18771`; no suppression. Warnings are current totals. The source-line audit is `lint-audit.json`. Do not count the successful build as a lint pass.

Installed the diagnostic Dev on tablet and restored Wio / Home 2 / Phase 12, first Continue Watching tile focused. TV presentation is a selectable diagnostic, not the new default. Prior TV cached-drawing installation was left as-is.

Commands (JDK 17):

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
adb -s 6889f3db install -r D:/workspace/niktv-playback-tests/tablet-matched-20261102/diagnostic-dev.apk
gradlew.bat :app:lintDebug -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
python tools/check-source-secrets.py
git diff --check
```

Evidence: `D:/workspace/niktv-playback-tests/tablet-matched-20261102/`. `diagnostic-dev.apk` is the timed APK; `optimized-dev.apk` is the earlier same-configuration TV-source ARM64 build installed before adding the policy control and is not used for the new timed arrays. `driver.py` and `control.py` preserve inputs/selection behavior, raw frame/memory/display samples and screenshots. `summary.json` contains complete vertical arrays, frame totals and missed-deadline counters. No captures, APKs or provider data are committed.
