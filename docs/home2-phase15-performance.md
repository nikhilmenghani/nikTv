# Phase 15 — Home-like tiles and readable captions

Preceding commit: `1f4b280f`. Original Home unchanged. Same optimized Dev method, profiles and settings as [Phase 14](home2-phase14-performance.md). Evidence/APKs: `D:/workspace/niktv-playback-tests/home2-phase15/`.

Contained-caption native tiles now reuse Home's responsive width and typography scale. Posters use a 2:3 base rather than the old tall narrow shape, reserving native focus/padding insets. Titles wrap up to six lines and subtitles up to four; the source is a separate themed badge instead of consuming subtitle width. Watch progress keeps its own bottom inset. Full text remains exposed to accessibility; exceptionally long strings beyond these budgets still use an ellipsis. Larger system fonts expand the caption budget rather than clipping against fixed-height artwork. Configuration changes recreate the native view with saved row/media identity.

Normal-font captures show the complete Taarak Mehta title and the episode/date/title subtitle, Friends episode title, source badges and progress on tablet and TV. More ordinary posters fit the Home proportions; TV rows become shorter, which also changes the visible drawing workload. Do not attribute the entire delta to text alone.

| Device / profile | Vertical p95 runs (ms) | Median | Delta vs Phase 14 | Deadline misses | Horizontal p95 |
|---|---|---|---|---|---|
| Tablet / Wio | 15, 15, 16 | 15 | 0 | 3, 3, 2 | 13 |
| Tablet / Nikhil | 17, 18, 15 | 17 | +2 | 2, 2, 2 | 14 |
| TV / Wio | 23, 19, 19 | 19 | +1 | 5, 3, 3 | 8 |
| TV / Nikhil | 18, 19, 17 | 18 | 0 | 2, 1, 1 | 7 |

Small samples: no statistically established regression or speedup. There is a possible small cost from extra wrapping/badge drawing and a changed image/viewport budget. Retain the readable design for functional parity, then investigate TV cache lifecycle separately.

The tablet initially appeared in portrait (system accelerometer/user rotation both 0); the landscape selector failed before any measurements were recorded. Those setup failures are not passes. Set `user_rotation=1` temporarily for the matched landscape runs and add an orientation precondition to the external harness. Restore the original rotation after the validation cycle. Portrait is a separate functional check, not pooled with landscape timing.

Optimized Dev build/focused tests passed: **213 tests, zero failures/errors**. Isolated regression build and instrumentation (commands in Phase 13): **7/7 on each device**. New native-caption checks assert full visible bounds and zero ellipsis for the long sample title/subtitle at 1.0× and 1.6× font sizes, using isolated configuration contexts rather than modifying system font settings. Existing rapid reversal, held/canceled input, row removal and detail-return assertions still pass. Fixture apps removed after checks. No Release changes.

Credential scan: zero findings. `git diff --check`: clean. Applicable lint failed with the same 22 errors, none in changed files; the unsuppressed result is retained in `lint.log`.
