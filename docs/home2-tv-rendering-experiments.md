# Home 2 TV rendering experiments

2026-11-02. Fire TV AFTKRT, ARMv7, approximately 60 Hz, 1920 × 1080 app resolution. Original Home and Release remain unchanged. Existing local sizing experiments were preserved. New changes are selectable Home 2 diagnostics, not a migration or a new global default.

## Implementation

Two independent TV-only candidates retain the Phase 12 feed, captions, progress, hero and actions:

- **TV - immediate tile focus:** skips the native 320 ms scale animation on TV, retaining immediate border/shadow highlighting. This did not improve vertical scrolling and is not recommended.
- **TV - cached tile drawing:** uses an Android hardware layer for each recycled card, allowing static artwork, gradient and caption drawing to be reused during scrolling. Existing zoom remains. No metadata requests, additional image prefetch or provider calls were added.

The native holder/cache counts remain bounded by the existing RecyclerView pools. Drawing layers consume additional graphics memory; this is an opt-in experiment pending longer memory/thermal tests, not a memory optimization. Original routes cannot select it. On non-TV devices both candidates retain the previous behavior; unit tests cover these guards. Explicit Info still loads details and optional hero metadata stayed off.

## Measurements

No recording or tracing during timing. Same APK within each comparison, same first Continue Watching identity, requested 250 ms input cadence, three Down then three Up. Caches were retained. Initial entry and the returned viewport differ naturally; these are repeated browsing scenarios, not identical-pixel microbenchmarks. Figures are per-run global histogram p95 frame times, not input-to-visible-focus latency or FPS.

| APK / profile / mode | Vertical p95 runs | Median |
|---|---|---:|
| Focus APK / Wio / Phase 12 | 22 / 18 / 26 ms | 22 ms |
| Focus APK / Wio / immediate focus | 24 / 29 / 28 ms | 28 ms |
| Layer APK / Wio / Phase 12 | 24 / 18 / 18 ms | 18 ms |
| Layer APK / Wio / cached drawing | 21 / 17 / 17 ms | 17 ms |
| Layer APK / Wio / repeated Phase 12 | 26 / 18 / 18 ms | 18 ms |
| Layer APK / Nikhil / Phase 12 | 32 / 30 / 30 ms | 30 ms |
| Layer APK / Nikhil / cached drawing | 23 / 17 / 17 ms | 17 ms |
| Layer APK / Nikhil / repeated Phase 12 | 31 / 32 / 23 ms | 31 ms |

Cached horizontal runs were 6/5/5 ms on Wio and 6/6/6 ms on Nikhil. Wio's control horizontal sample was 9 ms (one run). Nikhil's initial control sample was 24 ms, with repeated control samples 17/17 ms. Do not present these unequal sample counts as a precise percentage improvement. Both cached rapid-reversal samples were 17 ms with one global missed-deadline increment. Screenshots confirm return to the first row/card.

These results support rendering reuse as a useful TV optimization, especially on the denser Nikhil path. They do not isolate caption text from gradients, clipping or image compositing. Wio's vertical improvement is small. Native animation modes render different numbers of frames; percentages of missed frames alone are not equal-work scores. No SmartTube parity claim.

## Memory and behavior

Wio graphics PSS after the third control/candidate run was 20,849 / 39,317 KiB; total PSS 133,760 / 155,952 KiB. Nikhil graphics PSS was 21,988 / 42,547 KiB; total PSS 149,600 / 169,318 KiB. The sampled graphics increase is about 18–20 MiB. Allocation was not immediately reclaimed in the repeated Wio control, so these figures are process snapshots with retained caches, not an isolated steady-state memory budget. No long soak, low-memory response or leak-free guarantee is established.

Movie Info opened details and Back restored Info focus with the same hero. The synthetic `input keyevent --longpress 23` check entered resume loading instead of establishing the context menu; Back canceled it. This check is **not a pass** for remote long-press behavior. No playback stability or audio validation is claimed. It occurred after timing, so it does not affect the benchmark arrays. Real-remote long-press still needs confirmation; the renderer change itself adds no input handlers.

Final Fire TV state: optimized Dev installed, Wio, Home 2, cached tile drawing selected. The choice is an experiment and can reset after process restart. Original Home, tablet/phone behavior, Release and playback engines are not migrated. No commit or push.

## Verification

- Latest optimized Dev build passed; 206 targeted discovery/navigation unit tests passed, zero failures.
- Final lint failed: 22 errors, 350 warnings, three hints. All error source lines exist in HEAD; no suppression. Warnings are current totals, not all claimed pre-existing.
- Source credential scanner: zero findings. Whitespace check passed.
- Initial focus candidate build failed because the new enum name was duplicated; corrected before tests/install. That build failure is not counted as a pass.

Commands (JDK 17):

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:lintDebug -PperformanceBuild=true -PskipLocalGithubToken=true --console=plain
adb -s 192.168.2.19:5555 install -r D:/workspace/niktv-playback-tests/firetv-phase12-20261102/tv-layers-dev.apk
python tools/check-source-secrets.py
git diff --check
```

Evidence: `D:/workspace/niktv-playback-tests/firetv-phase12-20261102/`, `tv-focus-*` and `tv-layers-*` files. Separate APKs for focus and layer experiments; do not pool their timings. Input scripts use `tablet_scroll_metrics.py` begin/end and preserve actual host input timestamps, raw counters, memory snapshots and screenshots outside the repository. Final unit results are in the app's standard XML reports; lint source-line audit is `tv-layers-lint-audit.json`.
