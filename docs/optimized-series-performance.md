# Optimized Series: implementation and validation

## Status — November 4, 2026

Series is implemented on `codex/optimized-series`, based on `791abc71`, and the optimized non-debuggable Dev APK is installed on Xiaomi Pad 5 and Poco X3 Pro. Home and Movies retain their existing behavior. Previous Series remains available in Options.

This checkpoint records the completed tablet/Poco implementation and measurements. The user requested committing it on November 4, then fixing lint and beginning Fire TV validation. The full rollout gate is not complete: TV checks, remaining mobile/profile coverage and the unmeasured metrics listed below still require follow-up. This checkpoint does not certify those pending gates. No push, Release installation or publication has been made.

## Implementation

- Series reuses Movies' recycled renderer and provider adapter: complete selected-category identity, a six-category demand window, retained hydrated/cache-first rows, 30-item landing previews and the existing full View All collection. No categories or actions were removed to improve timings.
- Profile, surface and experience retain separate ViewModels, preferences, scroll/focus identities and exact SERIES favorite membership. Home keeps its TMDB adapter and existing keys; Movies keeps its provider path.
- Up to ten header entries append from already-loaded selected provider categories without replacing the active selection. Provider artwork and cached public enrichment are read off the UI path. Browsing does not initiate TMDB matching or open media streams.
- Header actions preserve original provider items and queues. A valid unfinished IPTV episode in the current profile uses the existing recent/episode resume callback. Alternate-source history, another profile, missing/zero/completed progress and an invalid newest recent cannot drive IPTV Resume.
- Series enables the existing provider touch-selection and skeleton-reconciliation behavior. No playback engine, episode loading, parental authorization or download implementation changed.
- A real Poco detail-return check exposed an integration gap: the old entry effect accepted focus on the Compose/native container, allowing the first arrow to enter the phone bottom bar. Series detail return now retries actual native card restoration; fresh menu entry still focuses its tab. The hook runs on route entry, not each tile movement. Home and Movies do not use this new branch.

## Builds and checks

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

## Remaining gates and limits

- Fire TV has not been touched. Its Series comparison, D-pad/return checks and sustained run remain pending.
- Poco has no configured Nikhil profile; no Nikhil phone result is claimed. Samsung and opposite physical orientations remain untested in this Series session.
- Real input-to-highlighted-draw latency, first usable tile time, artwork readiness and provider-request counts are not instrumented here. Shell timing and gfxinfo are not substitutes. The two-display-period focus target is therefore not certified.
- Renderer-only versus actual provider-header cost has not been isolated. The before/after tables include the header and all current actions.
- Real cold-cache/page-arrival profiling, slow-network behavior and full pagination exhaustion are not completed. Existing deterministic cache/window tests pass; inherited synthetic all-at-once metadata fixtures had mixed arrival results and cannot certify production hydration below 50 ms.
- Actual playback/resume routing is covered by pure/fake tests and existing callbacks; no new streaming-engine playback test is claimed from this UI-only session.

Raw artifacts and command logs: `D:/workspace/niktv-playback-tests/series-resumed/`. `comparison-summary.json`, `nikhil-summary.json`, navigation JSON, screenshots, unit/lint logs, pre-fix failure and post-fix pass evidence are retained. Historical shared-component fixture notes are archived separately in `historical-series-report.md`; they are not represented as fresh Series measurements.
