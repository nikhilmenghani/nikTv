# Phase 14 — row management parity

Preceding commit: `b79fbe5c`. Home unchanged; optimized Dev, same Stage 12 tablet / cached-tile TV settings and input method as [Phase 13](home2-phase13-performance.md). No Release install or push.

Home 2 now has a remove control on each recycled row. Touch selects it directly; remote Left from the first tile or Menu enters it, Right/Down returns to that row's saved tile, Up reaches the preceding row. Recommendation dismissal uses the existing recommendation action so later activity can recreate it. Other rows are hidden in separate profile/surface trial preferences, without deleting history or changing Home's selections. Restore hidden rows is available in Performance experiment settings; TMDB category checkboxes reflect trial visibility and can re-enable a hidden category.

Diff updates preserve stable media identity. Removing a focused row parks focus inside the native island and restores the nearest surviving neighbor instead of jumping to the header/hero. No extra provider lookup, metadata work or media decoder is added.

| Device / profile | Vertical p95 runs (ms) | Median | Delta vs Phase 13 median | Deadline misses | Horizontal p95 |
|---|---|---|---|---|---|
| Tablet / Wio | 14, 15, 19 | 15 | 0 | 2, 3, 5 | 12 |
| Tablet / Nikhil | 14, 16, 15 | 15 | −3 | 2, 2, 2 | 13 |
| TV / Wio | 23, 18, 17 | 18 | −1 | 3, 2, 2 | 7 |
| TV / Nikhil | 24, 18, 17 | 18 | +1 | 2, 2, 2 | 7 |

These short runs do not establish a speed improvement or a zero-cost guarantee. Their small differences are within observed variance. The visible header gains one compact native control per materialized row; all 30 cards/categories remain available. Evidence and APKs: `D:/workspace/niktv-playback-tests/home2-phase14/`; no recording during timing.

Focused optimized Dev command from Phase 13 passed with **212 tests**, zero failures/errors. The regression build/instrumentation commands from Phase 13 passed **6/6 on each device**, including removal of first/last rows, insertion without losing identity, touch/held/canceled input, details return and cropped-reversal checks. A first compile caught a ViewHolder `position` name collision; corrected and rebuilt. Actual tablet checks hid Continue Watching, confirmed it remained in original Home, then restored it through trial settings. Personal history and category choices were preserved.

Source credential scan: zero findings; diff whitespace check clean. Applicable `:app:lintDebug -PperformanceBuild=true -PskipLocalGithubToken=true` failed with 22 existing errors, none in this phase's changed files; findings remain unsuppressed. See the phase evidence logs for exact commands/results.
