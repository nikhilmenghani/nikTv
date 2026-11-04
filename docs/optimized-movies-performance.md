# Optimized Movies rollout

Baseline: `c13b4968`. Worktree: `nikTv-optimized-catalog`, branch `codex/optimized-catalog`. Unrelated source/player edits in the primary checkout are preserved and excluded. No push, publication or Release install.

## Final Movies session status

Movies implementation and the authorized mobile/tablet checks are finished for this session. Both devices have been restored to the Movies-only Dev build; Series development is stopped and remains uncommitted in an isolated worktree. Home and personal data are preserved. Warm navigation improved in every measured Movies cohort. This does **not** establish that every production-performance target is met: bulk-arrival stress limits and unmeasured metrics remain below, and final-binary TV smoke waits for renewed availability.

| Device/profile | Original median per-trial frame p95 | Optimized | Status |
|---|---:|---:|---|
| Fire TV / Nikhil | 21 ms | 6.5 ms | Earlier same-binary ABBA + 15-minute soak complete; latest binary smoke pending |
| Fire TV / Wio | 23 ms | 6 ms | Earlier same-binary ABBA and pagination/details checks complete |
| Pad 5 / Nikhil | 33 ms | 16.5 ms | Same-binary ABBA, final functional checks and 15-minute soak passed |
| Pad 5 / Wio | 37 ms | 16 ms | Same-binary ABBA complete; both fixes retested on final build |
| Samsung / Wio | 17 ms | 6 ms | Same-binary ABBA complete; final-build confirmation 7, 6, 7 ms |
| Samsung / Nikhil | 16 ms | 6.5 ms | Final-build ABBA, details return and 15-minute soak passed |

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

## Fire TV controlled renderer comparison (no artwork; harness correction)

The actual original/recycled production row components render 14 local categories x 30 identical fixture titles in a physical window. No accounts, provider requests or network. A later source audit found that `localArtwork=true` created a shared neutral PNG, but both adapters still passed null artwork. These recorded component runs therefore use placeholders/no artwork. They do not measure poster decoding or image draw cost. Corrected fixtures bind the PNG and must be rerun; real-app ABBA with visible provider artwork is unaffected. Recycled rows include contained captions, shadows, source badges and progress. Same eight-direction traversal, with an assertion that each trial returns to the first media identity. Header actions are excluded here; actual app focus paths require separate checks.

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

An earlier diagnostic mixed first traversals with repeated traversals. The later controlled flagged runs also omitted artwork because of the adapter bug above; they remain useful as no-artwork component comparisons, not image-loading evidence. Initial failed fixture attempts were setup defects in the new harness, including missing Compose frame-clock scheduling and block focus reuse. Fixed by awaiting mounted media and independently keying comparison blocks; they are not production crashes.

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

Tablet functional tests found touch-to-D-pad selection reentry and an already-attached row's stale skeleton overlay. Regression tests and Movies-only corrections are in progress; Home keeps the existing default behavior. Samsung setup showed different first-visible titles. Independent retained horizontal positions accounted for the verified mismatch; no cache-order regression was established. Fresh same-binary comparisons align the actual first slot, use verified endpoints and reject drift before accepting metrics.

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

The recorded fixtures use identical local 14-row x 30-item content, but the later-discovered null-artwork adapter bug means they did not draw the generated PNG. Their draw measurements are **no-artwork component** results. Corrected bitmap runs are pending and none of these fixture values measure public/provider image delivery or initial tab hydration. Real-app first-usable-tile latency, artwork readiness and provider request totals remain unquantified; fake sources validate their own request/cancellation policy. Static header cost is quantified on TV only. Final phone Nikhil matching, tablet/mobile endurance and final-binary TV verification remain explicit pending gates. No actual streaming playback/download was initiated by these browsing regressions; original provider callback dispatch is tested instead.


## Samsung Nikhil final-build comparison and navigation

All blocks use final `d8dd4a92...` normal Dev, keeping existing Nikhil caches/categories. The origin is Wonder Woman 1984 in ENGLISH | 4K; the next row contains Tox, Sleepwalker and Coexistence, My Ass!. Six warm trials per renderer, A-B-B-A, requested 450 ms cadence. A has eight arrows and B six as in the other cohorts. Screenshots/UIA remain outside timing; shared build waits occurred between exclusive blocks.

| Block | Frame p95 per trial | Deadline misses / frames |
|---|---|---|
| A1 | 13, 16, 14 ms | 43 / 373 |
| B1 | 6, 6, 7 ms | 0 / 375 |
| B2 | 7, 7, 6 ms | 3 / 374 |
| A2 | 17, 17, 16 ms | 45 / 377 |

Median per-trial p95 is **16 versus 6.5 ms**. Every accepted endpoint matches, with original semantic title and optimized visual artwork/border checks. These are frame distributions, not input-to-physical-focus measurements. Different key/frame counts and warm shared caches prevent attributing the raw miss-rate difference solely to rendering. Evidence: `phone/movies-final-nikhil-summary.json` and accepted blocks.

On the final Samsung build, a physical long press opens the correct compact provider action menu and dismisses without activating Play. Opening Wonder Woman's actual provider movie details shows its expected title, release/runtime metadata, actions and cast. Back restores the same visual movie and **exact tile bounds**. No actual playback or favorite/download mutation was initiated. The Nikhil 900-active-second navigation/background-return soak is running separately; it must complete before an endurance pass is claimed.

Additional targeted existing regressions pass: `MovieContainerRoutingTest` (4), `PortalVodHierarchyTest` (3), `CatalogNavigationTest` (2), and `CatalogBackNavigationTest` (10), all zero failures/errors. This is **19 additional relevant tests**, separate from the earlier 248 applicable discovery/startup tests. Command: `./gradlew.bat :app:testDebugUnitTest --tests 'com.nikhil.niktv.ui.MovieContainerRoutingTest' --tests 'com.nikhil.niktv.data.PortalVodHierarchyTest' --tests 'com.nikhil.niktv.ui.CatalogNavigationTest' --tests 'com.nikhil.niktv.ui.CatalogBackNavigationTest' -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain`. Log/results: `movies-navigation-container-checks.*`.

The Movies implementation checkpoint is **4a037c5bdb9eae8b147ebc72554e62a0240d142b**. The primary checkout was fast-forwarded to it without a push. SHA-256 hashes of all seven unrelated pending file diffs are identical before and after integration. Trial APKs were built from the isolated committed baseline plus the discovery work; unrelated uncommitted source/player edits remain preserved in the primary checkout.


## Benchmark harness correction discovered during Series work

The test's `localArtwork` option generated a PNG, but the legacy `RailEntry` and recycled `NativeDiscoveryItem` constructors still received null artwork. This is a **test-harness defect**, not an app rendering change. Existing component logs are preserved and reclassified as no-artwork/placeholder measurements. Their first positive focus-border draw timing remains what the hook recorded; the earlier claim that those runs loaded a PNG was incorrect.

All actual-app ABBA tables remain valid: screenshots show real provider artwork on the titles traversed. The synthetic static-header cost excludes real header images and now also needs to be understood without row poster images. A corrected test-only adapter and image-readiness assertion are being prepared, together with a separate deterministic cache/page-arrival fixture. Corrected TV fixture execution waits for the user's renewed authorization. Do not claim bitmap fixture coverage or substitute renderer numbers for provider/network readiness.


## Final endurance, restoration and stop point

Both normal Dev Movies runs completed the required 900 active seconds, excluding lock waits. These are navigation/memory observations with diagnostics, not latency benchmarks.

| Device | Active duration | Cycles | Same-task/process/poster background returns | PSS start / peak / final |
|---|---:|---:|---:|---:|
| Samsung | 903.62 s | 136 | 3 | 351.0 / 351.0 / 274.8 MiB |
| Xiaomi Pad 5 | 904.89 s | 109 | 3 | 281.3 / 357.3 / 276.1 MiB |

No focus-loss, navigation-return drift or unexpected task/process replacement was recorded. Memory rose/fell and ended below its warm-up peak; this is evidence against steadily growing memory **within these workloads/durations**, not proof that no leak exists. Different device loop speeds and diagnostics mean cycle counts are not performance comparisons. Samsung's final-build Wio confirmation retained the same item in all three warm trials (frame p95 7, 6, 7 ms), supporting the earlier full Wio comparison after the scoped source fixes.

At the user's request, Series work stopped. Samsung and tablet were both restored with data retained to `movies-final-dev-arm64.apk` (`d8dd4a92...048a18`) and left on Movies. The two isolated fixture packages created for these tests were removed successfully on both devices; personal Debug/Stable apps and their data were retained. No further device testing is planned in this session. Fire TV has been untouched since the user reclaimed it. The separate Series worktree/artifacts remain uncommitted for a later session; no Series implementation is merged into the primary checkout.

## Corrected artwork and arrival results

One corrected invocation per device passed **two tests**, producing 16 records per fixture (four ABBA blocks, one first exposure plus three warm trials each) and eight pixel-proof PNGs. Both adapters bind the generated checkerboard; assertions and independent image inspection confirm it actually appears. There are no provider requests. This fixture uses local metadata, a single cached image and **different component geometry** (for example, about seven versus more than ten visible posters on tablet), so it is supplementary component/design evidence, not a pixel-matched or real-provider comparison. The actual-app tables above remain the primary before/after evidence.

The warm traversal fixture does not populate source-badge/progress fields, although its components support them; the arrival fixture explicitly draws IPTV badges and 40% progress. The phone legacy path has no actual tile-highlight hook, so that value is unavailable; its common decor-after-focus draw is a separate metric. Draw callbacks are not confirmed physical presentation.

| Corrected warm fixture | Original median trial frame p95 | Recycled | Frames with UI-phase sum >50 ms |
|---|---:|---:|---:|
| Samsung | 12.98 ms | 5.71 ms | 0 / 0 |
| Pad 5 | 22.92 ms | 20.31 ms | 5 / 0 |

The synthetic arrival fixture remounts settled skeletons and publishes all 14 x 30 local records at once, after common worker parsing. It is deliberately different from the app's six-category demand window and is not a provider HTTP/startup measurement.

| Warm synthetic arrival | Original submission-to-focus median | Recycled | Original / recycled median frame p95 | UI-phase frames >50 ms |
|---|---:|---:|---:|---:|
| Samsung | 37.79 ms | 58.68 ms | 30.68 / 8.01 ms | 0 / 0 |
| Pad 5 | 140.01 ms | 123.74 ms | 39.47 / 43.55 ms | 6 / 6 |

**There is no universal bulk-arrival improvement claim.** Tablet arrival spikes remain and phone focus arrives later despite better frame distributions. Geometry, fresh remounting and the oversized simultaneous publication prevent treating this alone as a proven regression in the real app. No production optimization or extra rerun was added from this stress result. This remains a documented limit for future focused work, following the user's direction to stop after Movies.

Evidence: `phone/` and `tablet/corrected-artwork-and-arrival.log`, both `*-artwork-corrected-abba.json` files and `corrected-artwork-proofs/`. Commands: install the isolated regression main `848adbd9...` and Android test `9fc70df9...`, then `adb -s SERIAL shell am instrument -w -r -e localArtwork true -e arrivalPerformance true -e class 'com.nikhil.niktv.ui.CatalogRendererPerformanceTest#comparePhysicalWindowRenderersABBA,com.nikhil.niktv.ui.CatalogArrivalPerformanceTest#compareSkeletonToPopulatedMetadataArrivalABBA' com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner`. Both have since been uninstalled. No further benchmark execution is needed for this session.

The follow-up changes only tests/reports. Production APK content remains the tested Movies build. New arrival-test configuration access initially introduced one `LocalContextConfigurationRead` lint error; it was corrected to use `LocalConfiguration`, without suppression. Test compilation and final lint results are recorded in `movies-harness-compile-lint.log`; no applicable unit suite or device fixture was rerun after this configuration-only test correction.

Final corrected-harness Kotlin compilation passed. Lint remains failed with **22 pre-existing errors, 369 warnings and 3 hints** after the new configuration-aware test correction; no new lint errors or suppressions remain. The staged source credential scan reports **0 findings**, and `git diff --check` passes. Only the two test files and reports are included in the follow-up; Series production changes remain excluded.
