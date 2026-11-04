# Optimized Movies rollout

Baseline: `c13b4968`. Worktree: `nikTv-optimized-catalog`, branch `codex/optimized-catalog`. Unrelated source/player edits in the primary checkout are preserved and excluded. No push, publication or Release install.

## Current checkpoint status

This is a Movies implementation checkpoint, with complete TV evidence on the earlier build and final shared-fix verification underway on tablet/mobile. It does **not** mark every cohort/performance gate complete. Series is still using its previous renderer. Fire TV testing is paused at the user's request; final-binary TV smoke and all Series TV measurements wait for renewed availability.

| Device/profile | Original median per-trial frame p95 | Optimized | Status |
|---|---:|---:|---|
| Fire TV / Nikhil | 21 ms | 6.5 ms | Earlier same-binary ABBA + 15-minute soak complete; latest binary smoke pending |
| Fire TV / Wio | 23 ms | 6 ms | Earlier same-binary ABBA and pagination/details checks complete |
| Pad 5 / Nikhil | 33 ms | 16.5 ms | Earlier same-binary ABBA complete; final functional/soak pending |
| Pad 5 / Wio | 37 ms | 16 ms | Earlier same-binary ABBA complete; touch/skeleton fixes under verification |
| Samsung / Wio | 17 ms | 6 ms | Same-binary ABBA complete; final shared-fix recheck pending |
| Samsung / Nikhil | Pending | Pending | Original baseline captured; final matched comparison pending |

These are gfxinfo warm-traversal results, not visible-focus latency. Original and optimized paths use eight and six arrows respectively and produce different frame counts. They corroborate the independent renderer fixture but do not establish SmartTube parity or cold/real-network behavior. Details and excluded runs follow.

## Execution gates

1. Capture current Movies/Series on Samsung, Xiaomi Pad 5 and Fire TV, for Nikhil/Wio. Personal app caches are never cleared. Real-app first exposure is not a clean cold cache.
2. Complete Movies recycled presentation and stable preview-window loading; measure before adding the provider header.
3. Add a cached IPTV header and provider actions; measure its additional cost.
4. Complete visual, touch, D-pad, navigation, stale-arrival and 15-minute navigation/background-return checks per device. Record ABBA run-order variation and failures.
5. Run applicable tests, lint, build and credential checks; commit Movies before enabling Series.

Home remains unchanged. Series retains its existing route until the Movies commit. Keep every selected category, 30-item previews, full View All pagination, profile isolation, existing resource limits and provider actions. Previous Movies remains accessible.

## Findings and implementation

The old provider binding identity included the moving visible category window. Scrolling could cancel jobs, replace page state and initialize caches again. The optimized path separates the full selection identity from its six-category neighborhood. Legacy callers retain the existing API behavior.

Window changes retain hydrated pages, cancel obsolete work without joining cleanup, reject old cache/network results and clear obsolete window errors. Cache merging and queue preparation run on worker dispatchers. Unchanged pages, queues and populated row models are reused. Existing page/item limits, image limits and the provider gate are retained. Background return uses the stable binding; inactive Movies does not initiate demand loads.

Movies has separate profile/surface/device presenter and presentation keys and a separate preview ViewModel. Home's exact existing keys stay unchanged. Movies alone defaults to the optimized route, with Previous Movies in the menu. Series is not enabled yet.

The provider header keeps original provider items and queues separate from presentation cards. Its ten-item pool appends distinct provider identities without moving the selected entry. Metadata reads only existing presentation/detail caches; a miss never matches, fetches or writes an empty result. Original provider callbacks handle Play/Resume, favorites and Info. Trailer lookup receives the original provider item. Home's TMDB action dispatch remains unchanged.

## Verification so far

Artifacts/screenshots: `D:/workspace/niktv-playback-tests/optimized-catalog-20261103`, outside git. Baseline APKs were built from c13b4968 before implementation. No personal cache, category selection or credential was cleared. Runtime builds use optimized, non-debuggable Dev; fixture APKs have the separate regression package.

Commands from the isolated worktree with JDK 17:

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.ProfileDashboardStartupTest' -PregressionBuild=true -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s 192.168.2.19:5555 shell am instrument -w -r -e localArtwork true -e class com.nikhil.niktv.ui.CatalogRendererPerformanceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
adb -s 192.168.2.19:5555 shell am instrument -w -r -e localArtwork true -e compareHeader true -e class com.nikhil.niktv.ui.CatalogRendererPerformanceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
```

The first command passed 241 tests in 41 suites, zero failures/errors (`movies-full-discovery-build.log`). Later fixes require a fresh suite run before commit. Initial targeted tests exposed an unnecessary copy of unchanged pages; fixed and rerun. A first combined lint command incorrectly placed test options after lint; rejected before execution, then corrected. Lint and remaining device gates are not yet passed.

## Fire TV controlled renderer comparison

The actual original/recycled production row components render 14 local categories x 30 identical fixture titles in a physical window. No accounts, provider requests or network. Local artwork is one shared neutral PNG, not a realistic diversity of provider posters. Recycled rows include contained captions, shadows, source badges and progress. Same eight-direction traversal, with an assertion that each trial returns to the first media identity. Header actions are excluded here; actual app focus paths require separate checks.

A-B-B-A order, one separate exposure then three warm trials per block. No builds, tracing, recording, UI hierarchy capture or other-device work during timing. Dispatch-to-focus and first decor draw after focus are measured in process; drawing-pass latency is not physical presentation latency. FrameMetrics UI work sums input, animation, layout and draw phases; it is not an independent whole-thread stall trace.

| Warm metric | Original rows | Recycled rows |
|---|---:|---:|
| Trials | 6 | 6 |
| Input to focus p95 | 54.18 ms | 0.98 ms |
| Input to first drawing pass p95 | 71.12 ms | 15.28 ms |
| Frame duration p95 range per trial | 44.07-124.92 ms | 5.33-5.97 ms |
| Maximum UI phase sum range | 57.35-125.53 ms | 3.31-4.23 ms |
| Frames with UI phase sum >50 ms | 12 | 0 |

Instrumentation passed. Evidence: `tv/renderer-artwork-abba.log` and `.json`. Original run-order variation is substantial. These results support the renderer choice; they do not establish full-app improvement, bulk-arrival behavior, diverse-artwork readiness or SmartTube parity.

An earlier no-artwork diagnostic passed but mixed first traversals with repeated traversals, so it is not the primary warm comparison. Initial failed fixture attempts were setup defects in the new harness, including missing Compose frame-clock scheduling and block focus reuse. Fixed by awaiting mounted media and independently keying comparison blocks; they are not production crashes.

## Fire TV static header added cost

Header comparison uses recycled rows on both sides, with/without the actual hero controls/text composition. It uses a solid header background; it does not measure real header image decoding, metadata/cache arrivals or rotation. A-B-B-A with three warm trials per block after separate exposure. Instrumentation passed (`tv/header-artwork-abba.log` and `.json`).

| Warm metric | Rows only | Rows plus static header |
|---|---:|---:|
| Median of per-trial frame p95 | 8.95 ms | 10.27 ms |
| Mean of per-trial frame p95 | 8.04 ms | 10.26 ms |
| Input to focus p95 | 1.46 ms | 1.71 ms |
| Input to first drawing pass p95 | 15.13 ms | 15.50 ms |
| Maximum UI phase sum | 7.97 ms | 40.47 ms |
| Frames with UI phase sum >50 ms | 0 | 0 |

Added median frame-p95 cost: 1.32 ms; mean 2.22 ms. The initial chat update's 2.5 ms median was an arithmetic error, corrected here and in chat. Real-profile header verification remains required.

## Remaining Movies gates

- Real provider header artwork/cache arrivals, action dispatch and stable selection.
- Fresh applicable discovery/navigation suite, lint, credential checks and normal Dev build.
- Samsung/tablet/Fire TV profile comparisons and visual/touch/D-pad/details-return checks, including the six-category boundary.
- First usable tile/artwork readiness, provider request counts, memory and 15-minute navigation/background return per device.
- Review results before committing Movies; Series migration waits for that checkpoint.

## Fire TV real-app Nikhil comparison

All four blocks used the same optimized non-debuggable Dev binary (`movies-header-armv7.apk`) and existing personal caches. A is Previous Movies; B is optimized Movies. The first visible Continue Watching title was The Shawshank Redemption, and the traversal visited Oscar titles Inception, Interstellar and The Return of the King, returning to the first tile. A uses eight key events because it visits Search / All on entering/leaving a category; B uses six. Both use requested 400 ms cadence. Key counts/frame counts differ intentionally; these are not identical low-level input paths.

| Block | Frame p95 per trial | Missed deadlines per trial | Frames per trial |
|---|---|---|---|
| A1 original | 19, 21, 23 ms | 2, 4, 3 | 16 |
| B1 optimized | 7, 6, 7 ms | 1, 0, 0 | 126 |
| B2 optimized | 6, 7, 6 ms | 0, 0, 0 | 126 |
| A2 original | 21, 22, 21 ms | 4, 2, 3 | 16 |

The original p95 median is 21 ms; optimized 6.5 ms. Original has 18 deadline misses / 96 frames; optimized 1 / 756 frames. Native scrolling generates more frames; raw miss counts/rates alone must not be treated as a causal comparison. Both blocks' results and the independently instrumented component fixture support the renderer improvement for this short warm traversal. This does not establish first-exposure/bulk-arrival/soak results.

Original return identity is available through UIAutomator. Fire OS hides native tile descendants while the nested Compose header is present. For B, screenshots verify the actual visible title, and a closed white poster-border plus tolerant artwork hash checks the same visual tile before/after timing. That is visual evidence, not a runtime media-ID assertion. All screenshots/UIA work occur outside timing. These gfxinfo results do not measure dispatch-to-visible-focus latency.

Evidence: `tv/app-matched-nikhil-{A1,B1,B2,A2}-r{1,2,3}.*`, origin screenshots. Earlier fast trial `app-abba-nikhil-A1-r1` ended on the top nav and is excluded. A later mislabeled B1 origin remained on Previous Movies because clicking its already-active Movies tab is a no-op; corrected by going through Home before Movies. No timing data were accepted from that wrong route.

Fresh normal Dev build/unit checks passed 242 tests. Lint failed with 22 pre-existing errors (same issue set in unchanged existing code, including existing locations in NikTvApp); no suppression was added. `movies-dev-checks.log` preserves the failure. Subsequent cache-resume fix still needs rerunning.

## Fire TV real-app Wio comparison

Same binary, caches, cadence and path policy as Nikhil. Continue Watching starts with Verity; the first selected category is HINDI | LATEST MOVIES 4K. The horizontal traversal covers The Kerala Story 2 Goes Beyond, Jhaad Phoonk and Meethe Mein Kya Hai, then returns to Verity.

| Block | Frame p95 per trial | Missed deadlines per trial | Frames per trial |
|---|---|---|---|
| A1 original | 24, 23, 24 ms | 4, 3, 4 | 16 |
| B1 optimized | 6, 6, 6 ms | 0, 0, 0 | 126 |
| B2 optimized | 6, 5, 5 ms | 0, 0, 0 | 126 |
| A2 original | 23, 20, 19 ms | 4, 2, 2 | 16 |

Median per-trial p95: original 23 ms; optimized 6 ms. Deadline totals: original 19 / 96 frames; optimized 0 / 756. Frame/key counts differ as explained for Nikhil; shell cadence and gfxinfo are not focus-latency measurements. Evidence: `tv/app-matched-wio-*` plus origin screenshots.

For Nikhil, short-run optimized PSS stayed 176.4-176.8 MiB and graphics 44.7 MiB across both B blocks. A1 was approximately 164.7-164.8 MiB PSS / 19.3 MiB graphics. A2 varied approximately 155.0-195.5 MiB PSS / 34.5 MiB graphics. These in-process switches retain shared image caches, so this is not an independent cold-memory attribution. A longer soak is required before claiming bounded long-run behavior.

## Subsequent functional hardening (retest required)

- Returning during incomplete window cache hydration retries the missing cache read, retaining ready rows. Automatic provider demand waits for that cache check; explicit preview refresh can still take priority. Unit tests cover delayed cache, force refresh arriving first, obsolete errors and profile/session cancellation.
- Empty/failed/zero-selected-category headers stop their loading animation and leave category management available.
- A snapshot read inside the nested provider hero makes favorites, lifecycle/rotation eligibility, progress and preferences reactive without a provider feed emission. Cached metadata is reread on return from Info. Home's existing header branch is unchanged.
- Applicable unit suite now passes 245 tests. Isolated header UI checks and native focus/skeleton tests are in progress; a first cold accessibility-window setup failed to locate a title, while the subsequent rotation test passed. Do not mark the failed batch green.


## Fire TV Movies checkpoint, 2026-11-03

The Fire TV device owner completed 911.22 active seconds of cached navigation, 53 cycles, 658 arrows and three background/return checks. Exclusive waits were excluded. There were no lost highlights or reentry errors. PSS peaked at 197.88 MiB and ended at 151.93 MiB; graphics peaked at 51.10 MiB and ended at 44.13 MiB. This run shows no steady memory growth over its duration; it is not a latency benchmark or an independent cold-memory measurement.

Real Wio View All pagination expanded 42 to 84 titles while retaining Dial 1975 focus. Details/Back returned to that item and viewport. A freshly entered collection now positions its first tile before exposing the grid; loading identity changes no longer flash a header focus or an unpositioned bottom grid. The isolated delayed 14-to-80-item entry/details-return fixture passed on TV and tablet. Seven-row Nikhil traversal crossed the six-category preview boundary and returned to the same Shawshank artwork. Category manager opened with the original five selected / 78 available Wio categories and was dismissed without changing them. Home navigation smoke passed. No media playback, download or favorite mutation was performed in this real-app validation.

Evidence: `tv/movies-agent-validation-summary.json`, `tv/movies-endurance-nikhil-agent/`, collection entry fixtures and screenshots. The TV uses `movies-entry-final-dev-armv7.apk`; later shared corrections still need final-binary TV verification. Fire TV interaction is paused for the user.

A subsequent normal Dev packaging retry (`:app:assembleDebug --stacktrace` with performance/skip-local-credential flags) succeeded in 23 seconds after the combined build/unit/lint run's splitter-worker failure. The failed command remains a failure; its partial output APKs were not installed. The last applicable suite before the new favorite-kind regression passed 247 tests. Lint initially added two test-only `RememberInComposition` errors; these have been corrected with `remember`, with a fresh lint result pending. Existing project lint errors remain failures and are not suppressed.

Header favorite lookup now checks both profile and media kind, because provider movie and series identifiers can overlap. The existing Home lookup is unchanged. Trailer launch captures the selected provider media at the instant of activation so a later hero rotation cannot change the opened trailer identity.

Tablet functional tests found touch-to-D-pad selection reentry and an already-attached row's stale skeleton overlay. Regression tests and Movies-only corrections are in progress; Home keeps the existing default behavior. Samsung's original cache changed while refreshing (new titles preceding Beware Boiúna), so that earlier exposure is not a matched-current-catalog before/after sample. Fresh same-binary comparisons use verified endpoints and reject drift before accepting metrics.

These Dev builds set `isDebuggable=false` through `-PperformanceBuild=true`. They do not use the Release variant's R8/resource shrinking; Dev and Release results must not be mixed.


## Samsung Wio matched first-slot comparison

All twelve accepted trials use `movies-package-retry-arm64.apk` (SHA-256 `016c29d2ba58c89b03224100a025b6b8faf0f74b479203db1733d020d8938005`) with the same existing profile caches. The origin is One Piece Heroines in ENGLISH | LATEST MOVIES 4K; the next category contains The Mosquito Bowl, Wild Horse Nine and Wildwood. A uses eight arrows, B six, at requested 450 ms cadence. Both are warm and retain the selected title.

| Block | Frame p95 per trial | Deadline misses / frames |
|---|---|---|
| A1 | 14, 19, 20 ms | 47 / 391 |
| B1 | 6, 6, 6 ms | 3 / 373 |
| B2 | 6, 6, 6 ms | 2 / 378 |
| A2 | 18, 16, 15 ms | 62 / 380 |

Median per-trial p95 is 17 versus 6 ms. All accepted endpoints match: original semantic title, optimized screenshot/closed border and tolerant artwork comparison. The latter is not a runtime media-ID assertion. UIA/screenshots are outside timing; builds and other-device work are excluded during each timed block. Shared build/functional waits occurred between blocks, so this was not uninterrupted ABBA.

Earlier Charlie Kirk/Beware origins were independently retained horizontal positions, not evidence of a proven cache-order bug. Thirty-five Left arrows aligned the actual first slot. An initial original warm traversal drifted, was remapped before timing, and is not counted as a successful matched trial. Baseline captures and rejected setup data remain outside Git for audit. No personal data/cache/category choice was cleared. The later shared input/skeleton corrections require a final-binary smoke/recheck; these numbers must retain the earlier build identity. Evidence: `phone/movies-wio-firstslot-summary.json` and accepted block files.


## Final source checks for Movies checkpoint

Normal Dev `:app:assembleDebug` passed. Immutable ARM64 APK: `movies-final-dev-arm64.apk`, SHA-256 `d8dd4a926ea8adda6f8fb2caaf129558f65bc9937303bb88781b8221db048a18`. ARMv7: `movies-final-dev-armv7.apk`, SHA-256 `391a97b788f57507e0fbc4733e64b04fc154e75c163d84915d9c7b3cb5d846c0`. This includes the Movies-only mixed-input and skeleton corrections, header kind/profile membership, the collection input-mode guard, and trailer identity capture. It is installed on Samsung and being installed on tablet. Fire TV has not been updated during the user's pause.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
./gradlew.bat :app:assembleDebug -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain --stacktrace
./gradlew.bat :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.ProfileDashboardStartupTest' :app:lintDebug --continue -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain --stacktrace
```

The applicable unit suite passed **248 tests, zero failures/errors**. The combined second command exits 1 because lint fails with **22 existing errors, 367 warnings and 3 hints**. The two new test-only composition errors were fixed; no lint suppression or baseline was introduced. Existing errors include SuspiciousIndentation, NonObservableLocale, RememberInComposition, RestrictedApi and UnsafeOptInUsageError. Logs: `movies-final-dev-build.log`, `movies-final-unit-lint.log`, `movies-final-check-results.json`.

The complete recycled Android device class passed **19 tests** on tablet, including kept-mounted and saved-state-remounted touch/details-to-D-pad restoration, modal ownership and same-cached-card skeleton clearing. Initial focused failures and the intermediate test-harness Dialog error remain recorded; the independent final rerun is green. Cache/window/profile races and header/cache identity are covered by the unit suite. Collection entry, callback/hero rotation and physical portrait/landscape fixtures were also exercised as documented in the tablet report.

`git diff --check` passes. Added diff/untracked source/docs credential-pattern scan found no GitHub token, Google access/API key or private-key marker; this is a local pattern check, not a hosted secret-scanning guarantee. Generated captures, logs, APKs and personal data remain outside Git.

### Most precise focus measurement

The latest probe measures first positive, clipped-in-bounds focus-border drawing, separately from the older first-decor-draw metric. This is an Android draw callback, not confirmed physical pixel presentation.

| Fixture device | Original focus p95 | Recycled focus p95 | Original highlighted draw p95 | Recycled highlighted draw p95 |
|---|---:|---:|---:|---:|
| Fire TV | 55.40 ms | 1.58 ms | 75.72 ms | 17.12 ms |
| Pad 5 | 78.21 ms | 6.41 ms | 101.47 ms | 14.10 ms |

The fixtures use identical local 14-row x 30-item content and shared repeated artwork. Their controlled draw measurements do not measure public/provider image delivery or initial tab hydration. Real-app first-usable-tile latency, artwork readiness and provider request totals remain unquantified; fake sources validate their own request/cancellation policy. Static header cost is quantified on TV only. Final phone Nikhil matching, tablet/mobile endurance and final-binary TV verification remain explicit pending gates. No actual streaming playback/download was initiated by these browsing regressions; original provider callback dispatch is tested instead.
