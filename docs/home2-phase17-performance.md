# Phase 17 — TV cache lifecycle and pressure fallback

Preceding commit: `f523b80f`. Original Home unchanged. TV drawing-cache opt-in remains guarded to physical TV; tablet production path does not enable it. Cache eligibility now requires window focus and no prior low-memory pressure for that view instance. Detached/recycled/pooled cards drop explicit layers. Window/background loss drops attached layers; low-memory callbacks additionally cancel optional pool warming, shrink view caches and disable scale animation while preserving immediate focus borders and navigation. Callback registration follows view attachment and is released on detachment.

This makes the resource policy explicit; it does not establish that Android previously leaked detached layers. Shared image caches are not globally cleared, preserving other screens. The main app's fullscreen-player branch replaces the browsing branch, so native view disposal already separates playback from this presenter; no player changes were made.

## Matched short navigation samples

Same optimized Dev settings/method as [Phase 16](home2-phase16-performance.md), no recording or tracing during timing.

| Device / profile | Vertical p95 runs (ms) | Median | Delta vs Phase 16 | Deadline misses | Horizontal p95 |
|---|---|---|---|---|---|
| Tablet / Wio | 14, 17, 16 | 16 | −1 | 4, 2, 3 | 13 |
| Tablet / Nikhil | 15, 16, 17 | 16 | 0 | 3, 3, 2 | 14 |
| TV / Wio | 22, 18, 17 | 18 | +1 | 3, 3, 2 | 7 |
| TV / Nikhil | 18, 18, 18 | 18 | 0 | 1, 1, 3 | 7 |

Small differences, not an established speedup/regression. Wio TV graphics PSS after run 3 stayed 42,492 KiB before/after; total PSS 171,186 →165,437 KiB. Nikhil process/cache/GC snapshots differ as well. These are **not proof of a steady-state GPU-memory saving** attributable to this change. The cache's previously measured graphics trade-off remains relevant.

## Repeated navigation and lifecycle

Nikhil, twelve cycles of eight Down/eight Up at requested 200 ms cadence, memory sampled after cycles 0/1/4/8/12, then Home and launcher return. This is a short growth check, not a thermal/long-session soak. New feed/artwork exposure can legitimately grow caches.

| Device | Total PSS at cycles 0 / 1 / 4 / 8 / 12 (KiB) | Graphics at 0 / 12 | Background graphics | Foreground graphics |
|---|---|---|---|---|
| Tablet | 232784 / 251765 / 257800 / 256033 / 259420 | 97424 / 118080 | 52956 | 117124 |
| TV | 179565 / 179963 / 182455 / 184389 / 184355 | 48223 / 48223 | 24543 | 37426 |

TV late-cycle totals plateau in these samples. Tablet grew mostly on early artwork exposure, with about 1.6 MiB net growth from cycle 4 to 12 and intervening variation. Neither proves leak freedom. Foreground TV launcher return showed the active Home 2 tab/hero rather than the prior card viewport; tablet retained card focus. That launch-entry difference is documented, not counted as identical restoration. Background/foreground snapshots have different viewport/cache residency, so do not report their difference as an isolated feature saving. Detail/Back restoration remains covered separately.

## Verification

- Optimized Dev build and **216 focused unit tests passed**, zero failures/errors. Two new policy tests cover foreground eligibility, disabled cache and pressure remaining sticky across focus gain.
- Isolated device regression: **8/8 each**. The new case confirms a real foreground card has a hardware layer, invokes the owning view's low-memory callback, asserts layer removal with focus intact, then completes the navigation/detail-return checks. It does not induce global device pressure or clear personal app data.
- Source credential scan: zero; whitespace check clean. Applicable lint failed with the existing 22 project errors, unsuppressed.
- Fixture apps removed. Release unchanged. Evidence/APKs/commands: `D:/workspace/niktv-playback-tests/home2-phase17/`; external `home2-cache-soak.py`. Same build/test commands as Phase 13.

Next: broaden functional/performance routes to row-edge scrolling, Search/All, details/back, touch hold, both provider previews, profile changes and portrait. Existing short Right×4/Left×4 samples mostly exercise within-viewport focus; they cannot establish smooth horizontal scrolling across the ends of a long rail.
