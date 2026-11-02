# Home 2 Phase 11: hero title artwork

Phase 10 committed as `a6730b87`. Phase 11 remains uncommitted and selectable only in Home 2. Dev build installed on Xiaomi Pad 5. Original Home/Movies/Series are unchanged; no Fire TV command, push or release build.

## Feature and bounds

Stage 11 adds exactly one flag to Stage 10: `heroTitleArtwork`. After a 200 ms stable hero selection, reuse `DiscoveryViewModel.prepareHero()` to obtain optional public TMDB metadata. The existing repository provides a 24-hour detail cache and two shared metadata request slots. Its single appended detail response includes images/credits/certification; only the matching title logo is applied here. Other enriched metadata does not replace feed values, so this phase does not add runtime/genre/synopsis changes or alter hero geometry.

The existing image loader renders the w500 logo within the reserved 64 dp title area, retaining the text title until image success and falling back on failure/missing data. A pure selection gate rejects mismatched IDs and movie/series identities and returns no logo when the stage is disabled. Existing generation guards protect profile/route/selection changes; rotation cancels obsolete work. Leaving the trial or backgrounding cancels optional preparation. Feed and playback actions keep the original complete card.

An uncached selected title can add one public metadata request plus one logo image request. No IPTV lookup, stream, decoder or provider request is introduced. Rapid hero paging coalesces preparation through the 200 ms delay; cached metadata/images are reused. Automatic rotation, focus-driven row artwork, ranking numbers and skeletons remain disabled. Initial metadata can finish while the user starts scrolling; this phase does not add a viewport-based cancellation policy.

## Tablet measurement

Xiaomi Pad 5, serial 6889f3db, Wio, landscape, Dev; same Phase 11 APK for controls and candidate. SHA in external `apk-sha256.txt`. No screen recording, tracing or audio capture. Public/personally cached data was retained. Logo success is visible in `phase11-origin.png`; comparison against `phase10-origin.png` confirms identical hero and row geometry.

D-pad scrolling starts at hero Play and sends Down x3, Up x3 at requested 250 ms intervals. Paging focuses Next and activates it twice 600 ms apart, resetting to the same titles between runs. Touch runs use two 710-pixel upward then two downward swipes at 450 ms each. The legacy helper origin strings mention the first media row, but the actual starting viewport here is the hero and first row, as shown in the screenshots. Touch fling offsets and loaded visible content are not pixel-locked, so treat touch as scenario comparison, not isolated proof of logo cost.

| Scenario | p95 frame duration, three runs | Median | Deadline misses / frames |
|---|---|---:|---:|
| Phase 10 scrolling, initial | 21 / 17 / 17 ms | 17 ms | 14 / 560 |
| Phase 11 scrolling | 15 / 15 / 20 ms | 15 ms | 7 / 563 |
| Phase 10 scrolling, repeated afterward | 17 / 15 / 15 ms | 15 ms | 6 / 560 |
| Phase 10 manual paging, initial | 12 / 10 / 11 ms | 11 ms | 7 / 523 |
| Phase 11 manual paging | 11 / 11 / 14 ms | 11 ms | 13 / 511 |
| Phase 10 manual paging, repeated afterward | 12 / 11 / 18 ms | 12 ms | 9 / 520 |
| Phase 10 touch scrolling | 16 / 16 / 16 ms | 16 ms | 48 / 978 |
| Phase 11 touch scrolling | 19 / 17 / 17 ms | 17 ms | 66 / 921 |

Relative to the warm scrolling control, median p95 changed by 0 ms. Paging medians are within 1 ms, but Phase 11 missed 2.54% of deadlines versus 1.73% in the repeated control (about +0.81 percentage points). Touch median rose by 1 ms (about 6%); missed deadlines rose from 4.91% to 7.17% (about +2.26 percentage points). These small, order-dependent samples do not prove causality, but the added deadline misses are a potential cost and must not be described as free or lag-free. All measured slow-bitmap-upload counters were zero.

The results include ready-artwork scrolling and cached/retained-cache paging, not controlled cold HTTP/decode tests. Frame rings are partial; global counters are used. No input-to-visible-focus timing or stable PSS/memory-growth measurement was made. The existing request gate and cache bound work, but cold metadata/image traffic and memory are additional costs not quantified here. Fire TV results cannot be inferred from the faster tablet.

## Behavior and checks

- Actual title artwork rendered for A Story of Derry while feed metadata/actions and layout stayed unchanged. Manual paging exercised multiple heroes. Info opened the expected details and Back restored focus to Info (`phase11-detail-return.png`). No playback or favorite mutation.
- Dev APK build passed. 200 targeted unit tests passed with zero failures/errors/skips, including a new stale-logo/control/fallback test and existing metadata cancellation/profile/error tests. The cumulative-stage test asserts only the logo flag changes; original-route protection includes Stage 11.
- Lint failed: 22 errors, 347 warnings, 3 hints. All 22 error source lines are present in HEAD a6730b87 (`lint-audit.json`). No suppressions.
- Source credential scan zero findings; whitespace checks passed. No instrumentation rerun for this small presentation change; previous native row/hero device fixture results remain prior evidence.
- Final APK `D:/workspace/niktv-playback-tests/home2-phase11-tablet/phase11-dev.apk` is installed on tablet, left on Home 2 Stage 11. No personal data/cache clearing, release installation or Fire TV access.

Exact commands with JDK 17:

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:lintDebug -PskipLocalGithubToken=true --console=plain
adb -s 6889f3db install -r D:/workspace/niktv-playback-tests/home2-phase11-tablet/phase11-dev.apk
python D:/workspace/niktv-playback-tests/home2-phase11-tablet/compare.py
python D:/workspace/niktv-playback-tests/home2-phase11-tablet/repeat.py
python tools/check-source-secrets.py
git diff --check
```

Evidence: `D:/workspace/niktv-playback-tests/home2-phase11-tablet/`, including immutable APK, hash, metrics JSON/raw gfx counters, host input timestamps, scripts, screenshots and build/lint logs.

## Remaining / next phase

Keep Home unchanged and this stage optional. Pending: Nikhil-specific comparison, portrait, missing/failed logo device cases, reduced-motion device check, long memory/thermal runs, controlled cold metadata/image tests and actual input latency. No SmartTube parity claim. Before migration, validate the deadline increase on constrained hardware when authorized. Automatic hero rotation can be the next independent phase, with visibility/focus/touch/popup/lifecycle gating; row-focus backgrounds remain separate. Do not enable either as part of Phase 11.
