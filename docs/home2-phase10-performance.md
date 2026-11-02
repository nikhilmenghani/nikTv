# Home 2 Phase 10: prepared artwork fade

Phase 9 was committed as `00742171`. Phase 10 is an uncommitted, separately selectable Home 2 stage, installed as Dev on Xiaomi Pad 5. Original Home/Movies/Series and Fire TV remain untouched. No push or release build.

## Added feature and cost model

Stage 10 adds only `heroArtworkFade` to Stage 9. After the selected image succeeds, its layer alpha animates from zero to one over 300 ms. Reduced-motion mode uses zero duration. Focus, title, metadata and actions update immediately; the previous title's image is removed immediately. Thus this is a fade from the dark hero, not a two-image crossfade. It avoids retaining two hero image layers or displaying old artwork behind new metadata. The brief dark interval while an uncached image loads is an intentional trade-off.

Both hero identity and artwork URL key readiness state. Obsolete selection callbacks mutate only their discarded state. Existing Coil cancellation/caching, 1280 x 720 decode cap and worker-thread gradient transformation remain. No extra metadata/provider requests, speculative prefetch, title-logo fetching, automatic rotation or focus-driven backdrop. No ranking numbers or skeletons. No list/row geometry changes.

The fade adds image-layer blending for 300 ms after readiness. It does not animate the whole header or trigger layout work per frame. No two-image crossfade or decoded-memory increase is intended; actual memory/GPU allocation differences remain unmeasured.

## Matched tablet measurements

Xiaomi Pad 5 (6889f3db), Wio profile, landscape, Dev. Same APK for all stages; SHA in external `apk-sha256.txt`. No recording during timed runs. The tablet reports 120 Hz, but application deadlines may differ. Global gfx counters are authoritative for the short runs; raw frame rings are partial. These counters do not measure input-to-focus latency.

Scrolling: start at hero Play, three Down/three Up, requested 250 ms per key. Same title/artwork and row geometry, artwork already ready. Hero paging: focus Next, two activations 600 ms apart; reset to the same starting title between runs. Public image caches were retained, so these are not controlled cold-network measurements.

| Scenario | p95 frame duration, three runs | Median | Deadline misses / frames |
|---|---|---:|---:|
| Phase 9 scrolling, initial control | 24 / 16 / 17 ms | 17 ms | 14 / 559 |
| Phase 10 scrolling | 16 / 15 / 15 ms | 15 ms | 10 / 556 |
| Phase 9 scrolling, repeated after Phase 10 | 18 / 17 / 16 ms | 17 ms | 13 / 553 |
| Phase 9 manual paging | 15 / 11 / 11 ms | 11 ms | 10 / 518 |
| Phase 10 manual paging with fade | 11 / 18 / 11 ms | 11 ms | 8 / 523 |

Relative to the repeated control, scrolling median p95 was 2 ms lower, and manual paging median was unchanged. These small samples do not establish a speed improvement: the Phase 10 paging outlier was 18 ms versus the control maximum of 15 ms. There is no consistent measured regression here, but larger samples and Fire TV are required before judging the feature's cost on constrained hardware. Two slow-bitmap-upload counter increments appeared across Phase 9 paging; none appeared in Phase 10 paging. Cache/order effects prevent attributing that difference to the fade.

## Behavior and verification

- Manual Next/Previous paging retained its control focus. Screenshots confirm final hero artwork/title identity and unchanged geometry. Actual Info opened A Story of Derry details; Back restored Info, verified in `phase10-detail-return.png`.
- Targeted unit suite: 199 passed, zero failures/errors/skips. The cumulative stage test verifies that Stage 10 adds only the fade flag; original-route isolation covers the new stage.
- Dev APK build passed and installed on tablet. No personal app data cleared and no playback started.
- Lint failed with 22 errors, 347 warnings, 3 hints. All 22 error source lines exist in committed HEAD 00742171 (`lint-audit.json`); no suppressions.
- Credential scan zero findings; whitespace check passed.

Exact commands (JDK 17):

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:lintDebug -PskipLocalGithubToken=true --console=plain
adb -s 6889f3db install -r D:/workspace/niktv-playback-tests/home2-phase10-tablet/phase10-dev.apk
python D:/workspace/niktv-playback-tests/home2-phase10-tablet/compare.py
python D:/workspace/niktv-playback-tests/home2-phase10-tablet/return-check.py
python tools/check-source-secrets.py
git diff --check
```

Evidence root: `D:/workspace/niktv-playback-tests/home2-phase10-tablet/` (APK, build/lint logs, metrics JSON, raw gfx counters, input timestamps and screenshots). The scripts address only the tablet serial. No instrumentation suite rerun for this small presentation change; Stage 8 device regression coverage remains prior evidence.

## Remaining validation / next feature

Still unmeasured: controlled cold decode/network, long memory/thermal runs, reduced-motion device behavior, failed artwork under pressure, portrait/touch and Nikhil profile comparison, exact visible fade timing, and Fire TV. These results are not a SmartTube parity claim. Keep this selectable in Home 2 and validate constrained-device results before migration. The next independent feature to evaluate is title artwork; automatic rotation and row-focus backgrounds should remain separate later experiments.
