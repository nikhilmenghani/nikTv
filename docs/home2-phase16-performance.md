# Phase 16 — isolated hero updates and stable bounds

Preceding commit: `16cc248e`. Home unchanged. Promote the isolated-feed observer and exact native hero bounds into Home 2's recycled hero path. Hero index/logo changes stay in the hero composition rather than resubmitting an unchanged feed. Profile, route, detail and actual feed updates still propagate. The landscape native frame uses the existing font-aware bound, so artwork/title changes cannot alter the height of the following row. No new metadata fetching, prefetch, provider matching or player changes.

Optional hero metadata remains off. Info is explicit and continues to open full details. Manual and eight-second eligible-idle paging remain available; neither can rotate during row/action focus, scrolling, popups or backgrounding. The original presenter cannot opt into this policy (unit guard).

| Device / profile | Vertical p95 runs (ms) | Median | Delta vs Phase 15 | Deadline misses | Horizontal p95 |
|---|---|---|---|---|---|
| Tablet / Wio | 17, 17, 18 | 17 | +2 | 2, 2, 2 | 14 |
| Tablet / Nikhil | 15, 16, 20 | 16 | −1 | 2, 3, 2 | 13 |
| TV / Wio | 22, 17, 17 | 17 | −2 | 3, 1, 1 | 8 |
| TV / Nikhil | 18, 18, 18 | 18 | 0 | 1, 1, 1 | 8 |

Optimized Dev, same profiles/cadence/modes as Phase 15, no recording. Small mixed differences; isolation is not established as a scrolling speedup. Keep the functional stable-bounds/observer separation and evaluate hero transitions independently.

## Separate hero probe

Actual screenshots verified Next, not Play, was focused before three OK presses spaced 1,200 ms apart. Then move to the top tab, verify visible hero, and collect 24 seconds idle with no input. Metadata off; images/cache retained. These selected titles differ between devices and from historical captures, so this is an absolute diagnostic, not a matched historical delta.

| Device | Manual p95 / p99 | Manual misses / frames | Idle p95 / p99 | Idle misses / frames |
|---|---|---|---|---|
| Tablet | 13 / 30 ms | 10 / 304 | 44 / 69 ms | 7 / 118 |
| TV | 12 / 26 ms | 3 / 72 | 26 / 44 ms | 4 / 63 |

Tablet idle retained all global frame rows: full-pipeline max 77.048 ms, six intervals >50 ms; measured UI-work max 40.600 ms, zero >50 ms; draw max 19.801 ms and start-delay max 30.075 ms. This does **not** identify a specific function or prove network/bitmap upload as the cause. The global histogram and raw-ring percentiles use different representations; do not interchange them. Cache hits alone do not make a changing backdrop free. Idle transition tails remain a reason to keep automatic artwork optional in the final cost review; do not claim polished/zero-stall rotation.

The actual hero/row captures show stable action and row placement and working Next paging. Targeted optimized build passed **214 unit tests**, zero failures/errors. Isolated device regressions passed **7/7 on tablet and TV**, including hero entry/return and large-font captions. Credentials scan zero; diff check clean. Applicable lint failed with the same 22 project errors, none in changed files; unsuppressed results are in the evidence logs.

Evidence/APKs/commands: `D:/workspace/niktv-playback-tests/home2-phase16/`; `home2-cycle-harness.py` and `home2-hero-probe.py`. Build and instrumentation commands are unchanged from Phase 13. Temporary fixture apps removed; Release untouched. Tablet stays temporarily landscape for the cycle; restore original user rotation at the end.
