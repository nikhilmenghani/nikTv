# Home 2 functional parity and performance cycle

Original Home stays unchanged. Each step gets a separate commit, functional checks and a recorded delta before the next step. Use optimized Dev; do not install Release or migrate existing destinations. Keep tablet and TV policies separate where measurements justify them. Do not hide a regression by removing an existing category or action.

## Recorded baseline

Phases 1–12 and their individual findings are linked from [rollout](recycled-discovery-rollout.md). The latest [tablet comparison](home2-optimized-tablet-comparison.md) and [TV rendering experiments](home2-tv-rendering-experiments.md) are the current baseline, not historical unmatched 100 ms samples.

## Remaining steps

| Step | Scope | Status |
|---|---|---|
| 13 | Reliable short/held remote activation, popup lifetime and originating-tile focus restoration | Complete: [findings](home2-phase13-performance.md) |
| 14 | Row/category management and recommendation dismissal parity, stable focus after removal | Complete: [findings](home2-phase14-performance.md) |
| 15 | Responsive Home-like tile geometry, readable title/subtitle/source/progress placement | Next |
| 16 | Hero transitions and on-demand information behavior; measure rather than assume cached work is free | Pending |
| 17 | TV tile-cache memory/lifecycle limits and sustained navigation; keep tablet path independent | Pending |
| 18 | End-to-end navigation/action matrix: profiles, categories, Search/All, details/back, touch and D-pad | Pending |
| 19 | Optional focus-driven artwork last, followed by final cost/benefit review | Pending |

For each step record APK/build identity, device/profile, feature settings, per-run frame p95 and deadline counters, first-exposure versus repeat results, memory snapshots where relevant, and actual functional outcomes. Do not label histogram timing as input-to-visible-focus latency. Keep recordings/tracing outside timed runs. Preserve personal categories, history and credentials; restore any temporary UI selection changes.

Short runs are regression indicators, not proof of SmartTube parity or a production soak. Failed/blocked checks remain failures, and unsuccessful experiments remain off. Source/cache/stream data and captures stay outside git. Any feature deliberately deferred must remain explicit in the final evaluation.
