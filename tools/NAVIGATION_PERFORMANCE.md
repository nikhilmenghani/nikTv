# Tablet navigation performance — 2026-09-24

Device: Xiaomi 21051182G, serial 6889f3db, landscape 2560x1600.
Scenario: Wio / ENGLISH | 24x7 ACTORS, 56 loaded channels, start at top, two 500ms upward swipes and two downward swipes. Measurements use Android dumpsys gfxinfo; this is an exploratory device check, not a statistically controlled benchmark. No refresh-rate settings changed.

## Findings and changes

- An atrace capture showed main-thread measure/layout and drawing on most scroll frames, urgent lazy composition, and occasional Recomposer spans around 100ms. Nested trace durations overlap and must not be added together.
- Programme-clock state is now read by tile composition and active search rather than invalidating the whole collection every five seconds.
- Programme progress is painted with Canvas instead of changing a child layout's width.
- The live collection caches one viewport ahead and half a viewport behind. This caches UI rows; it does not request more provider data by itself.
- Earlier work in the same change set pauses guide scheduling during scrolling and retains unchanged catalogue lists when merging guides.
- Opt-in `-PperformanceBuild=true` makes the debug-package APK non-debuggable, preserving package/signature/data. Normal builds remain debuggable. This is not an R8-minified release build.

## Results

| Configuration | Janky frames | p95 | p99 |
| --- | --- | --- | --- |
| Earlier debug sample, before scroll/guide fixes | 61/213 (28.64%) | 77ms | not recorded |
| Earlier debug, guide fixes, repeat | 26/226 (11.50%) | 38ms | not recorded |
| Non-debuggable + precompiled + clock/progress changes, run 1 | 13/250 (5.20%) | 18ms | 34ms |
| Same, run 2 | 20/257 (7.78%) | 20ms | 36ms |
| Added viewport row cache, run 1 | 14/264 (5.30%) | 19ms | 30ms |
| Same, run 2 | 16/278 (5.76%) | 18ms | 29ms |

Build mode, precompilation, cache warmth and provider background activity differ from the earlier debug samples. These numbers do not isolate the impact of individual source changes. Zero stutter has not been achieved.

## Reproduce

Build with `./gradlew.bat assembleDebug -PperformanceBuild=true -PskipLocalGithubToken=true`.
Install the correct ABI APK with `adb -s 6889f3db install -r ...` (do not uninstall/clear data).
Run `adb -s 6889f3db shell cmd package compile -m speed -f com.nikhil.niktv.debug` for the precompiled configuration used here.
Open the same Wio category and restore the viewport to the top. Run `./tools/measure-tablet-scroll.ps1` twice. The script checks the foreground app and defaults to this tablet's coordinates. Do not compare measurements from different categories or orientations.

The non-debuggable build cannot use run-as based debugging/token import. Existing credentials remain available. Use an ordinary debug build to restore development tooling; this does not require deleting app data.

## Functional checks

34 focused unit tests passed: live guide updates, scheduling, live search, tile presentation, pagination.
Device checks: Wio category entry, repeated scrolling, Denzel local search (one result), long-press menu and programme refresh action, live playback visibly rendered, Back returned to the same category with 56 loaded channels. No NikTV crash entries observed in the inspected crash buffer.

Next acceptance step: automate a longer warm/cold benchmark on a fixed performance configuration, trace remaining slow frames with per-composable instrumentation, and repeat on Fire TV. This pass measured the tablet only.
