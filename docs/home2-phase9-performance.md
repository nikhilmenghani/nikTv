# Home 2 Phase 9: static hero artwork

Phase 8 was committed as `3036f1f4`. Phase 9 remains an isolated, selectable Home 2 experiment; original Home, Movies and Series are unchanged. No Fire TV commands, release build or push.

## Change

Stage 9 adds exactly one flag to Stage 8: static hero artwork. One shared-Coil request is capped at 1280 × 720, with the existing backdrop gradients baked on a worker thread. The image occupies the existing hero bounds, so row geometry stays unchanged. Each hero identity owns its image subtree: a newly selected title cannot retain the previous title's image while loading. Missing backdrop falls back to poster; absent/failed artwork leaves the same dark hero. Actions retain the complete card metadata.

No crossfade, autorotation, title-logo request, dynamic row-focus backdrop, additional metadata/provider lookup or speculative image prefetch was introduced. Ranking numbers and skeletons remain excluded.

## Tablet comparison

Xiaomi Pad 5, serial 6889f3db, Wio, Dev build, no screen recording. Same candidate APK, same hero and saved rows, three Down then three Up at a requested 250 ms cadence per key. Host input timestamps and global gfx counters are retained. Start at hero Play; this covers the hero leaving/reentering the viewport and media row movement.

| Mode | p95 frame duration, three runs | Deadline misses / frames |
|---|---|---|
| Phase 8 before artwork | 20 / 15 / 18 ms | 14 / 545 |
| Phase 9, artwork ready | 16 / 16 / 15 ms | 7 / 544 |
| Phase 8 repeated afterward | 15 / 15 / 16 ms | 8 / 551 |

The warm control median is 15 ms versus 16 ms with artwork: approximately +1 ms, within this small sample's variability. The initial control is noisier, so its higher median does not establish a performance improvement. No slow-bitmap-upload counter increments occurred during these traversals. Raw frame rings are partial; global counters are used, not a claim about full-run maxima or input-to-focus delay.

These are artwork-ready navigation results, not cold image decode/upload measurements, network latency or Fire TV results. No matched memory-growth run was performed. A 1280 × 720 ARGB bitmap is approximately 3.52 MiB; transformation temporarily requires both source and output, and image-loader caching can retain more. This is an allocation estimate, not a measured PSS delta. Portrait composition, resource pressure, failed artwork and extended navigation still need device coverage.

Manual hero Next was checked visually: title, metadata and artwork changed together to Verity while Next retained focus. Phase 8 and Phase 9 screenshots show identical hero/card geometry. No playback or favorite mutation was performed. Existing Stage 8 focus/device regression results remain prior evidence, not freshly rerun instrumentation for Phase 9.

## Verification

- Dev APK build passed; 199 targeted unit tests passed, zero failures/skips. The cumulative-stage regression now verifies that only artwork changes from Stage 8 to 9; original-route protection includes the new stage.
- Lint failed with 22 existing errors, 347 warnings and 3 hints. No suppression or unrelated fixes.
- Secret scan: zero findings. Whitespace check passed.
- Final labeled Dev APK installed on tablet. The timed candidate differs only in the Stage 9 display label (the candidate duplicated Stage 8's label); final build/tests rerun after correcting it.

Commands:

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:lintDebug -PskipLocalGithubToken=true --console=plain
python tools/check-source-secrets.py
git diff --check
adb -s 6889f3db install -r D:/workspace/niktv-playback-tests/home2-phase9-tablet/phase9-final-dev.apk
```

Evidence: `D:/workspace/niktv-playback-tests/home2-phase9-tablet/`; initial build log at `D:/workspace/niktv-playback-tests/phase9-build.log`. Timed candidate `phase9-dev.apk`; final `phase9-final-dev.apk`. Do not migrate original Home based on this tablet sample. Next isolate prepared artwork transitions, after cold-load and constrained-device checks.
