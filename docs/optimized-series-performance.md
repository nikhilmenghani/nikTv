# Optimized Series: implementation and validation

## Status — November 4, 2026

Series is implemented on `codex/optimized-series`, based on `791abc71`. The optimized non-debuggable Dev APK is installed on Xiaomi Pad 5 and Poco X3 Pro; the subsequent lint-fixed Dev build is installed on Fire TV. Home and Movies retain their existing behavior. Previous Series remains available in Options.

This checkpoint records the completed tablet/Poco implementation and measurements. The user requested committing it on November 4, then fixing lint and beginning Fire TV validation. The full rollout gate is not complete: the remaining mobile/profile coverage, faster-cadence TV timing and unmeasured metrics listed below still require follow-up. This checkpoint does not certify those pending gates. No push, Release installation or publication has been made.

## Implementation

- Series reuses Movies' recycled renderer and provider adapter: complete selected-category identity, a six-category demand window, retained hydrated/cache-first rows, 30-item landing previews and the existing full View All collection. No categories or actions were removed to improve timings.
- Profile, surface and experience retain separate ViewModels, preferences, scroll/focus identities and exact SERIES favorite membership. Home keeps its TMDB adapter and existing keys; Movies keeps its provider path.
- Up to ten header entries append from already-loaded selected provider categories without replacing the active selection. Provider artwork and cached public enrichment are read off the UI path. Browsing does not initiate TMDB matching or open media streams.
- Header actions preserve original provider items and queues. A valid unfinished IPTV episode in the current profile uses the existing recent/episode resume callback. Alternate-source history, another profile, missing/zero/completed progress and an invalid newest recent cannot drive IPTV Resume.
- Series enables the existing provider touch-selection and skeleton-reconciliation behavior. No playback engine, episode loading, parental authorization or download implementation changed.
- A real Poco detail-return check exposed an integration gap: the old entry effect accepted focus on the Compose/native container, allowing the first arrow to enter the phone bottom bar. Series detail return now retries actual native card restoration; fresh menu entry still focuses its tab. The hook runs on route entry, not each tile movement. Home and Movies do not use this new branch.

## Overnight checkpoint checks (before the lint follow-up)

All commands run from `D:/workspace/android/nikTv-optimized-series` with JDK 17. Build preparation uses the existing tasks; tracked changes were checked after building. No integrated sources or unrelated player changes were modified.

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.ProfileDashboardStartupTest' :app:lintDebug -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --max-workers=2 --continue --console=plain
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --max-workers=2 --console=plain
python tools/check-source-secrets.py
git diff --check
```

| Check | Result |
|---|---|
| Dev APK build, arm64/armv7/universal | Passed |
| Applicable discovery/startup JVM suite | 258 tests, zero failures/errors/skips |
| Isolated regression main/test APK builds | Passed |
| Lint | **Failed: 22 existing errors**, identical ID/message/relative-path fingerprints to the earlier baseline; no new errors or suppressions |
| Tracked-source and separate new-file credential scans | Zero findings |
| Whitespace/diff check | Passed |
| Final APK manifest | `com.nikhil.niktv.debug`, `0.1.0-dev`, no debuggable flag |

An initial combined command attached `--tests` to lint and failed argument parsing. The corrected command above built the APK, passed the unit suite and failed only lint. Raw logs for both attempts remain available.

`performanceBuild=true` means non-debuggable Dev, not a minified Release. The pre-return-fix Wio comparison APK SHA-256 is `e08da3e9c372be775e4b9c563e84364109f5655f8ccf9c3e2e851bb37b2d86b8`. The final installed APK is `9bf9f68769f06a40beafa03041d8ebfd63e7f174f7db8b9316974d81b8c40dea`. Nikhil comparisons use the final APK. Three additional Poco Wio trials confirm the final build's warm navigation range below.

## Comparison method

A–B–B–A blocks, three cached/warm trials per block, six accepted trials per renderer. Categories, profile, provider identities and first-item origin are verified outside timing. Recordings, screenshots, hierarchy reads, tracing, builds and other-device work stay outside timed intervals. No personal caches are cleared. Catalogue-first-exposure and cold-cache arrival are not silently counted as controlled cold runs.

Horizontal input is Right, Right, Left, Left at nominal 400 ms spacing. Wio uses the English Series row and Lingo UK / Days of Our Lives / Son of a Critch. Nikhil uses INDIAN Reality Shows and Dance Deewane / BIGG BOSS SEASON 15 / THE KAPIL SHARMA SHOW. Every accepted horizontal and tablet vertical trial returns to the same media identity. Native tablet hierarchy gaps use closed-border/artwork proof captured outside timing.

Tablet vertical input makes one category round trip at nominal 800 ms spacing: original Down, Down, Up, Up (heading plus card stops), optimized Down, Up (direct row entry). This matches the logical category movement, not the number of key events or interval length. Frame counts and misses per round trip therefore accompany percentages.

Phone touch uses the same upward 900-pixel / 500-ms swipe, beginning with the first English Series card near y=300 (actual y=237–319). Each trial realigns outside timing. Native cards are shorter, so the same stroke reveals more content. These are real layouts, not geometry-identical renderer microbenchmarks.

### Wio results

| Device / path | Previous frame p95 / p99 | Optimized frame p95 / p99 | Previous deadline misses / frames | Optimized deadline misses / frames |
|---|---:|---:|---:|---:|
| Pad 5 horizontal | 32 / 50.5 ms | 13.5 / 31 ms | 77 / 1,104 (6.97%) | 36 / 935 (3.85%) |
| Pad 5 vertical | 38 / 65 ms | 30.5 / 42 ms | 94 / 1,173 (8.01%) | 28 / 444 (6.31%) |
| Poco X3 Pro horizontal | 37 / 81 ms | 14.5 / 19 ms | 77 / 398 (19.35%) | 31 / 945 (3.28%) |
| Poco X3 Pro touch | 20 / 42 ms | 14 / 18 ms | 43 / 558 (7.71%) | 10 / 576 (1.74%) |

Values are medians of per-trial Android frame-pipeline percentiles, not input-to-visible-focus latency. Horizontal/vertical animation and viewport geometry differ between the presenters; rendered frame counts are intentionally disclosed. Warm p95 ranges are Pad horizontal 31–34 → 13–18 ms; Pad vertical 36–38 → 26–34 ms; Poco horizontal 32–44 → 12–16 ms; Poco touch 19–24 → 13–14 ms. Tablet vertical movement improves less than horizontal traversal and still has missed deadlines.

The bounded final framestats rings contain eight UI-work spans above 50 ms on original Poco horizontal and zero on its optimized path; the other listed warm samples contain zero. This cannot rule out earlier loading spikes that fell outside a ring.

Final-build Poco confirmation: three warm horizontal trials, p95 16/12/15 ms, p99 24/21/19 ms, 15 missed deadlines across 474 frames. This is a B-only confirmation after the return-focus fix, not another complete A–B–B–A experiment.

### Nikhil tablet results

| Metric | Previous Series | Optimized Series |
|---|---:|---:|
| Median trial frame p95 | 40 ms | 15 ms |
| Median trial frame p99 | 61 ms | 29 ms |
| Trial p95 range | 36–44 ms | 11–15 ms |
| Deadline misses / frames | 68 / 958 (7.10%) | 35 / 939 (3.73%) |
| Sampled UI-work spans above 50 ms | 0 | 0 |

These Nikhil cached records had no available artwork in either renderer. They validate missing-artwork presentation and renderer/focus work without bitmap cost; they must not be compared directly with Wio's artwork-heavy row. The observed improvement without artwork supports renderer/focus work as a contributor, rather than implying all gains come from image loading.

### Warm memory samples

PSS / graphics MB, sampled after the timed trials:

| Cohort | Previous PSS range | Optimized PSS range | Previous graphics | Optimized graphics |
|---|---:|---:|---:|---:|
| Pad Wio horizontal | 236.5–264.7 | 238.7–247.5 | 96.5–107.5 | 108.4–116.5 |
| Pad Wio vertical | 238.4–251.7 | 223.4–231.8 | 96.2–105.5 | 99.2–105.1 |
| Poco Wio horizontal | 147.6–151.2 | 148.8–149.5 | 61.5–63.1 | 63.5–63.6 |
| Poco Wio touch | 150.5–154.7 | 153.2–154.7 | 62.9–65.4 | 65.5–66.4 |

These ordered samples are not isolated allocations. The header/artwork can retain additional graphics memory, and the table does not establish a standalone header allocation cost or absence of leaks.

## Functional checks

- Pad: header Series actions/Resume fixture plus tall-hero reversal boundaries, held activation without leaked click, mixed touch/detail return, skeleton reconciliation, and larger-font caption fixtures passed (six checks).
- Poco: Series header, mixed touch/detail return, larger-font captions and the new full-shell bottom-navigation return fixture passed (four checks).
- The new `ProviderSeriesReturnDeviceTest` fails on the pre-fix regression APK and passes on the corrected APK. Its fake catalogue cannot open a stream. The real final APK then restored Lingo UK before an arrow and moved Right to Days of Our Lives, rather than bottom navigation.
- Actual Wio Lingo UK details loaded seasons 4/5 and six season-5 episodes on both devices. Tablet D-pad Left reached the selected season and Right returned to the episode. Detail Back retained the exact original tablet card bounds and artwork identity. Missing TMDB details left provider episodes usable.
- Actual Poco long-press opened the existing provider action menu without auto-playing on release. View All opened its full category page (14 loaded, more available), with Search/Refresh and provider titles, then returned to the source row. No download or stream was launched.
- Nikhil and Wio retained different selected categories and titles; no category or credential edits were made.

A preliminary 400-ms mixed legacy traversal and the original Poco vertical D-pad trial ended on different categories and were excluded. Poco uses the separately matched touch comparison instead. Test harness checks were tightened to reject a whole focused native container as a poster. An active Previous Series tab can retain that renderer; controlled comparisons explicitly verify the actual route.

## Sustained check

Both final-Dev checks completed after approximately 909 seconds, with two background returns per device. Every cycle sent Right, Right, Left, Left, Down, Up. Pad completed 179 cycles (1,074 directional inputs), Poco 182 (1,092 inputs). Both ended on their original media identity with a visible focused poster, unchanged app PID and no foreground-guard failure. Row alignment settled after initial traversal, so identical final pixel coordinates are not claimed.

| Device / profile | Cycles | PSS start → end | PSS range | Graphics start → end |
|---|---:|---:|---:|---:|
| Tablet / Nikhil | 179 | 281.3 → 241.3 MB | 240.6–281.3 MB | 83.2 → 73.4 MB |
| Phone / Wio | 182 | 174.9 → 172.8 MB | 172.8–177.5 MB | 90.5 → 90.1 MB |

These are functional/memory checks with both devices operating concurrently, not precise timing trials. Samples show warm-up reclamation and bounded fluctuation during this window, not proof that no longer-term leak exists. No streams or audio were launched. The owned regression packages were removed afterward; the regular Dev APK and personal data remain. Tablet was restored to its original Wio profile with Series open.

## November 4 follow-up: lint and Fire TV validation

The Series checkpoint was committed as `4611ecd2`. The user then explicitly requested fixing lint and starting TV validation, superseding the earlier no-TV restriction. The lint fixes and TV follow-up below are subsequent, currently uncommitted work. No push or Release installation was made.

### Fresh host checks

- `:app:lintDebug`: **PASS, zero errors, 370 warnings**. The original 22 errors are corrected without new suppressions. These involved FocusRequester allocation during composition, locale observation, an extracted Media3 opt-in, misleading Settings indentation, and overriding a restricted Activity API. Trailer remote interception now lives in the root view, with focused-child media-key and delegation tests.
- `:app:testDebugUnitTest --tests 'com.nikhil.niktv.TrailerPlayerActivityTest' --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.*Settings*Test' --tests 'com.nikhil.niktv.ui.CategoryPickerPolicyTest' --tests 'com.nikhil.niktv.ui.VideoAppearanceScheduleTest'`: **273 tests, zero failures/errors/skips**.
- `:app:assembleDebug`: **PASS**. Combined final host check took 1m33s; log: `series-resumed/lint-fixes-final-checks.log`. Flags: `-PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --max-workers=2 --continue --console=plain`.
- `:app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true` with the same performance/credential flags: **PASS** (1m18s). Preparation writes generated build outputs, not integrated sources.
- `python tools/check-source-secrets.py`: **PASS, zero findings**. `git diff --check`: **PASS**.

### TV setup and test-harness findings

Fire TV AFTKRT (Android 11/API 30, 32-bit ARM, logical 1920×1080) received the non-debuggable armv7 **Dev** APK, preserving existing data. Release was untouched. All fixture networking uses isolated/local fake data; no media/audio was started.

The first combined fixture run had three failures. Two came from the instrumentation APK shipping a reduced `j$.util.DesugarTimeZone` that shadowed the app copy and lacked `getTimeZone(String)`; an explicit Java/networking linkage regression fixes the test APK reachability. Settings also needed fresh ancestor accessibility reads on API 30 and remote traversal past existing Watching preference rows. The normal app did not show the linkage exception.

An intermediate combined run passed seven of eight tests; the viewport-restoration test intermittently requested initial focus before the nested Compose hero was laid out. It now waits for that test precondition, while a separate rapid-entry/cancellation case retains coverage before deferred entry finishes. The fresh combined run **passes all nine distinct TV fixtures**: Series provider header/actions/resume eligibility, full-height hero reversal, held remote release, both-axis viewport restoration, rapid-entry cancellation, Settings picker/nested Back, trailer fallback, original/optimized startup, and Java runtime linkage. Raw earlier failed runs remain retained; no production renderer was changed to make these fixtures pass.

Fire OS initially returned a null UI hierarchy, and an older host probe could read a leftover XML file. Those probes were discarded. The TV driver now removes only its own temporary XML before each dump and refuses unsuccessful dumps. Screenshots and closed poster-border/artwork identity checks are collected outside timed windows.

Wio TV A–B–B–A measurements (three warm trials per block, six per renderer) now show:

| Path | Previous p95 / p99 | Optimized p95 / p99 | Previous missed deadlines / frames | Optimized missed deadlines / frames |
|---|---:|---:|---:|---:|
| Horizontal | 22 / 22 ms | 6 / 11 ms | 11 / 24 | 1 / 504 |
| Vertical category round trip | 26 / 26 ms | 6 / 9 ms | 22 / 71 | 2 / 252 |

Horizontal uses 24 directional inputs per renderer. Vertical uses six logical category round trips: 24 keys on Previous (heading/card stops) and 12 on optimized (direct row entry). The renderers draw different numbers of animation frames; these pipeline metrics are not visible-focus latency or FPS. Optimized vertical p95 ranges 6–27 ms; both first trials after reopening are slower than later trials. The original final vertical block initially returned to a different category from a lower viewport; it was excluded, realigned to the earlier top viewport and repeated. Both failed and corrected trials remain available. No >50 ms UI-work span appeared in the bounded final framestats rings of accepted Wio samples.

Nikhil also completed A–B–B–A horizontal comparisons, six warm trials per renderer on the same Dance Deewane / BIGG BOSS / Kapil row:

| Path | Previous p95 / p99 | Optimized p95 / p99 | Previous missed deadlines / frames | Optimized missed deadlines / frames |
|---|---:|---:|---:|---:|
| Nikhil horizontal | 21 / 21 ms | 6 / 11.5 ms | 14 / 27 | 0 / 512 |

Both renderers use 24 input events. Previous p95 ranges 19–25 ms, optimized 5–9 ms. Exact screenshot digests, rather than permissive image similarity, verify native endpoints because these provider titles have no artwork. Do not compare this cohort directly with artwork-heavy Wio. No >50 ms UI-work span appeared in these bounded final framestats rings. Nikhil vertical was not benchmarked.

Real Nikhil Dance Deewane details opened; Back restored the same native tile, and Right moved to BIGG BOSS rather than the navigation bar. Held OK opened the popup beside Dance Deewane, and Back restored it without activating Play. Forty Right inputs reached the category close control; Left reached View All. View All opened the modern provider collection (95 loaded titles and further pagination available), and Back returned to the same View All control. Forty Left inputs restored the first fully visible Dance Deewane tile. The close/remove control was never activated; category choices and My List were preserved.

The final combined fixture run passed nine distinct tests in 27.01s. A second three-test confirmation (Series header, both-axis restoration, rapid entry) passed in 10.868s. Regression packages were removed afterward. The final Dev build completed the finite navigation test from 09:58:54 to 10:14:08 Toronto: **94 cycles**, two background returns, one unchanged process and a fully visible Dance Deewane tile still selected. Each cycle is Right/Right/Left/Left/Down/Up; the adjacent Netflix row includes provider artwork. Final viewport differs from the initial bottom-row position because the test explicitly navigates between rows; this is not an exact viewport-restoration benchmark. No media/audio was started.

PSS: 171.9 → 143.0 MB, range 140.8–171.9 MB. Graphics: 34.0 → 40.8 MB, maximum 40.9 MB; it stabilized after the first background return. This window shows no steadily rising PSS and no repeated graphics step after the second return; it does not rule out a longer-term leak or certify an artwork-heavy Wio endurance run.

**Cadence limitation:** input JSON shows the host foreground guard plus Android shell startup stretched the nominal 400 ms spacing to a median **953–953.5 ms**, matched across old/new and both profiles (range 921–1,000 ms). These are controlled warm single-step/slow-reversal comparisons, not fast-repeat performance. The separate forty-key horizontal burst exercised functional traversal, not measured frame latency. Fast-cadence TV benchmarking and an in-app dispatch/highlight probe remain required before certifying rapid-input smoothness or the two-display-period target.

The final locale helper observes `LocalResources`, rather than the process-wide Compose locale snapshot. A fresh combined host command again passed lint (zero errors) and all 273 unit tests, but its first APK packaging attempt failed without a detailed cause. An unchanged `:app:assembleDebug --stacktrace` rerun passed in 20s; the cause of the one-off packaging failure remains unproven. No check was suppressed. Final armv7 Dev SHA-256: `a52c553901a7d4be7898e3e49ebdd421df2609597629cb4926e18c80e0c42b38`. Wio timed comparisons used `5d6483b072aa70ec0315434ed47b1f522927fe09173f5e4294061f02bcbfe6f3`; the subsequent production change only affects playback date/locale observation.

Wio was restored after the test; a focused normal-app Home/Series route smoke check completed, and Fire TV was left on optimized Series with the final Dev APK. No Release package was changed.

Artifacts: `D:/workspace/niktv-playback-tests/series-tv-validation-20261104/` (`verification-manifest.json`, per-trial input/navigation/frame JSON, `wio-summary.json`, `nikhil-summary.json`, `endurance-summary.json`, screenshots and all pass/failure logs).

## Remaining gates and limits

- Fire TV warm horizontal comparisons on both profiles, Wio vertical comparison, targeted fixtures and the 15-minute Nikhil navigation/background-return run are complete as described above. Nikhil vertical timing, a heavy-artwork Wio sustained run and faster-cadence timing remain unmeasured. The combined hero fixture passes after correcting its viewport-readiness precondition; early-entry cancellation is tested separately.
- Poco has no configured Nikhil profile; no Nikhil phone result is claimed. Samsung and opposite physical orientations remain untested in this Series session.
- Real input-to-highlighted-draw latency, first usable tile time, artwork readiness and provider-request counts are not instrumented here. Shell timing and gfxinfo are not substitutes. The two-display-period focus target is therefore not certified.
- Renderer-only versus actual provider-header cost has not been isolated. The before/after tables include the header and all current actions.
- Real cold-cache/page-arrival profiling, slow-network behavior and full pagination exhaustion are not completed. Existing deterministic cache/window tests pass; inherited synthetic all-at-once metadata fixtures had mixed arrival results and cannot certify production hydration below 50 ms.
- Actual playback/resume routing is covered by pure/fake tests and existing callbacks; no new streaming-engine playback test is claimed from this UI-only session.

Raw artifacts and command logs: `D:/workspace/niktv-playback-tests/series-resumed/`. `comparison-summary.json`, `nikhil-summary.json`, navigation JSON, screenshots, unit/lint logs, pre-fix failure and post-fix pass evidence are retained. Historical shared-component fixture notes are archived separately in `historical-series-report.md`; they are not represented as fresh Series measurements.
