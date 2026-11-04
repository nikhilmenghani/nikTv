# Xiaomi Pad 5 Movies validation

Assigned USB serial: `6889f3db`. This report covers tablet evidence only. Baseline is the original `c13b4968` optimized Dev APK; real-app ABBA uses the first fixed normal candidate, while final normal-build functional/endurance work remains in progress. Home and Series retain their existing implementation. Personal data, caches, categories and app preferences are retained. No real playback/audio, downloads or raw provider probes are exercised.

All ADB actions use the shared performance-lock helper. Initially all work was exclusive. After protocol 2, builds/installs/precise measurements retain `exclusive`; untimed functional checks and endurance batches use `functional`, which allows other-device functional work and yields to queued exclusive work. Artifacts live outside git at `D:/workspace/niktv-playback-tests/optimized-catalog-20261103/tablet`. Screenshots and UI hierarchy checks happen outside timed intervals. `gfxinfo` reports frame work during shell-driven browsing, not physical input-to-visible-focus latency.

## Original baseline

Existing Wio Movies and Series screenshots and three-sample runs predate this validation pass. They establish original rendering and available cached content, but are short forward-only samples without robust same-origin return validation or ABBA ordering. They must not be promoted to a matched before/after comparison.

The first current read showed the existing profile chooser. Nikhil was selected normally without editing its settings. `original-nikhil-movies-entry.png` shows Continue Watching starting with RRR (2022)(4K)(Multi Languages), and selected category ENGLISH | 4K starting with Wonder Woman 1984, Soul (2020), and The Equalizer 2.

The first repeated Movies traversal (`original-nikhil-movies-r1`) moved from Continue Watching RRR to Wonder Woman 1984 instead of returning. It is excluded as a warm media-return comparison. Subsequent trials start from that first selected category, and retain navigation identity reports alongside raw measurements.

Nikhil Series is captured in `original-nikhil-series-entry.png`: WORLD CUP CRICKET 2023 HIGHLIGHTS starts with ICC Cricket World Cup 2023 Highlights. Its first traversal also primes the old category/control path and ends on Dance Deewane (Colors Hindi), so `original-nikhil-series-r1` is excluded. Each accepted r2-r4 navigation report confirms the same title/category at both endpoints.

| Original profile/surface | Accepted samples | Frame p95 per sample | Deadline misses / frames |
|---|---|---|---|
| Nikhil Movies | r2, r3, r4 | 34, 38, 36 ms | 90 / 1,053 |
| Nikhil Series | r2, r3, r4 | 34, 34, 32 ms | 75 / 1,056 |
| Wio Movies | r2, r3 | 36, 32 ms | 50 / 690 |
| Wio Series | r1, r2, r3 | 32, 34, 32 ms | 82 / 1,057 |

These samples use `down, down, right, right, left, left, up, up` at a requested 400 ms shell cadence. The original presenter's Search / All controls affect focus paths. This is a retained original-build reference, not an ABBA comparison or focus-latency measurement. The first current exposure is not a clean cache or network baseline.

Wio Movies starts with Continue Watching Spider-Man: Brand New Day / Bringing Dad Home and ENGLISH | LATEST MOVIES 4K. The refreshed r1 traversal also drifted, from The Mosquito Bowl (LATEST CAM) to Barbie in the Nutcracker (NETFLIX), and is excluded. Only two fresh Movies repeats have verified return identity. Wio Series starts with Friends, Taarak Mehta Ka Ooltah Chashmah and Yeh Jo Hai Zindagi; its warm samples return to Lingo UK in ENGLISH | SERIES after a separately captured priming traversal. These profiles' category/title sets differ from the Fire TV's and cannot be substituted for it.

## Candidate installation

After the original captures/samples above, installed `movies-viewall-fixed-arm64.apk` with data retained. SHA-256: `c9e1d54f60ca1346f29ac26c8bebdcab12d35846762b80bce532f07913f258e9`. Installation succeeded. Initial normal launch showed the existing profile chooser (`candidate-first-entry.png`).

The test-only header extension derives the existing tablet `!phone` layout policy from Configuration, including portrait. The debug fixture host has no `configChanges` override, so test-scoped lifecycle callbacks remount the same fake VM/presentation after Activity recreation. The orientation case checks title/action visibility and bounds, retained carousel identity and original Play/Info callback items across portrait/landscape/portrait; these callbacks only append to local lists. The fixture's requested orientation is restored before finishing.

The first regression build passed in 54 seconds. Immutable APKs and SHA-256 values are recorded in `tablet-responsive-build-artifacts.json`. The first requested-Activity-orientation attempt failed while waiting for portrait; the header was visibly rendered in landscape. Read-only diagnostics show API 36, free rotation, default fixed-to-user rotation and `ignoreOrientationRequest true`. Requested Activity orientation is ignored by this large-screen policy; that attempt is retained as an unsupported method, not a demonstrated production layout failure.

Physical portrait→landscape→portrait subsequently **passed** using the explicitly authorized scoped display-rotation driver with the later `movies-entry-final-regression-arm64.apk` / matching tests. The host records natural physical size, refuses unknown original rotation modes, verifies the fake regression host owns foreground before each mutation, and restores the original command in a host `finally` block. Actual rotations were 0/1/0; original `free` was restored to `free`, `restoredExactly=true`, `error=null`. No ignore-orientation policy, app preferences, data or caches changed. Evidence: `header-physical-orientation.log` and `header-physical-orientation-restore.json`. The earlier failure remains `header-orientation.log` / `header-orientation-failure.png`.

## Controlled tablet renderer comparison

`CatalogRendererPerformanceTest` passed with `localArtwork=true`, A-B-B-A order, a separate first traversal plus three warm trials per block. It renders the actual old/new row components with identical local 14x30 content and one shared neutral poster image, preserving the tablet's animation and tile-layer policy. It has no provider calls or personal data. No screenshots, UI hierarchy capture, tracing, recording, builds or other-device actions occur during timing.

| Warm metric | Original | Recycled |
|---|---:|---:|
| Trials | 6 | 6 |
| Input to focus p95 | 78.21 ms | 6.41 ms |
| Input to first highlight drawing pass p95 | 101.47 ms | 14.10 ms |
| Maximum highlight drawing pass | 120.82 ms | 18.70 ms |
| Per-trial frame p95 range | 19.95–30.82 ms | 16.24–20.20 ms |
| Median per-trial frame p95 | 23.67 ms | 17.76 ms |
| Maximum UI-phase sum | 80.55 ms | 6.68 ms |
| Frames with UI-phase sum >50 ms | 11 | 0 |

The highlight callback measures positive-opacity legacy tile drawing versus focused native-view visibility plus its immediate drawing pass. It is not physical display latency. UI-phase sum covers input/animation/layout/draw, not an independent whole-thread stall trace. Local artwork lacks real provider image diversity, metadata/cache arrivals, full shell navigation and the header. Evidence: `renderer-highlight-abba.log`, `.json`, and `-summary.json`; no Fire TV values are substituted.

## Functional fixture findings

`NativeDiscoveryRowsDeviceTest` ran four tests: its two remote-only variants passed; both touch-capable variants failed at line 210, "Remote reentry restores the tile selected by touch." Physical touch had already activated item0 once, but subsequent keyboard-mode entry did not restore that item. Evidence: `native-input.log`. This older native-island component is distinct from the current recycled Movies component, so the failure must be investigated rather than automatically attributed to optimized Movies. Its baseline/preexisting status is unproven. Current recycled results follow; real-app touch checks remain pending.

`RecycledDiscoveryDeviceTest` passed all 16 tests (`recycled-functional.log`): large-feed recycling; header entry/reversal; held-key release separation; pressure cleanup; rapid reversals; both viewport axes on details return; reduced motion; long titles and local larger-font Configuration contexts; row actions, tail controls and late items.

Skeleton/collection batch: four passed and one opt-in performance case skipped (`loading-and-collection.log`). Passed cases cover noninteractive placeholders and stable resolved geometry, failed/stale artwork, reserved progress space, and fresh View All delayed 14→80 cache arrival entering item0 with selected-item return after details/cache reset. The runner's "OK (5 tests)" includes the assumption skip and is not reported as five executed passes.

Provider hero action/rotation batch passed two tests (`provider-hero-actions.log`). Fake callbacks verify original provider items/queues, immediate favorite icon changes without a provider page emission, manual carousel identity and lifecycle/focus rotation gating. "Play" in these fixtures only appends to an in-memory list; real playback/audio remains unexercised.

## Wio real-app same-binary comparison

All accepted blocks used normal non-debuggable Dev `movies-viewall-fixed-arm64.apk` (SHA-256 above), retained caches and requested 400 ms shell cadence. A is Previous Movies, B is optimized Movies. Each starts at Beware Boiúna in ENGLISH | LATEST MOVIES 4K, traverses the next LATEST CAM category (The Mosquito Bowl, Wild Horse Nine, Wildwood) and returns to the starting poster. Original A visits Search / All controls and requires eight keys; B requires six. They therefore have intentionally different event/frame counts.

| Block | Per-trial frame p95 | Missed deadlines / frames |
|---|---|---|
| A1 | 34, 40, 38 ms | 84 / 1,027 |
| B1 | 13, 18, 16 ms | 25 / 708 |
| B2 | 16, 16, 17 ms | 24 / 709 |
| A2-category | 34, 38, 36 ms | 80 / 1,045 |

Median per-trial p95 is 37 ms original versus 16 ms optimized. Totals are 164 / 2,072 versus 49 / 1,417 deadline misses/frames; different paths/frame generation preclude attributing a causal miss-rate improvement from those totals alone. All twelve endpoints are verified. A has semantic title/category identity; B uses independently inspected screenshots and tolerant closed-border artwork hashes because nested native descendants are absent from tablet UIA. Artwork evidence is not a runtime media-ID assertion. These are gfxinfo frame measurements, not input-to-visible-focus latency.

An initial A2 block returned to Continue Watching Spider-Man, rather than the selected category, after route state reuse. Although its endpoints matched each other, it is **excluded** from the matched comparison. Corrected A2 explicitly asserts Beware Boiúna before starting timing; accepted files use `app-matched-wio-A2-category-*`. A shared build wait occurred between B2 and A2; each block is exclusive and warm-primed, but the sequence was not uninterrupted. Evidence: `app-matched-wio-summary.json`, accepted block files and origin screenshots; the excluded `app-matched-wio-A2-*` remains for audit.

Across the six optimized warm samples, PSS is approximately 226.9–228.3 MiB and graphics about 100 MiB. In-process route switches retain shared image/cache state, so these are not independent cold-memory attributions or a long-run bound.

## Nikhil real-app same-binary comparison

Same c9e1 normal Dev binary, cache policy, exclusive blocks and requested 400 ms cadence as Wio. All accepted blocks start at Wonder Woman 1984 in ENGLISH | 4K; the next category is ENGLISH | FHD 2026 (Tox, Sleepwalker, Coexistence, My Ass!). A requires eight events; B requires six. Screenshots confirm the same visible origin and artwork for B, while A has semantic title/category return evidence.

| Block | Per-trial frame p95 | Missed deadlines / frames |
|---|---|---|
| A1 | 32, 32, 34 ms | 79 / 1,061 |
| B1 | 17, 14, 16 ms | 28 / 695 |
| B2 | 17, 13, 17 ms | 25 / 704 |
| A2 | 32, 36, 36 ms | 81 / 1,067 |

Median per-trial p95 is 33 ms original versus 16.5 ms optimized. Totals: 160 / 2,128 versus 53 / 1,399 deadline misses/frames, with the same differing-path/frame-count limitations as Wio. All twelve accepted endpoints match. The independent component fixture supplies focus/highlight drawing-pass measurements; these real-app gfxinfo values do not measure input-to-focus latency.

An initial A1 exposure drifted to Tox. The new expected-title guard rejected the origin **before starting timing**; `app-nikhil-A1-exposure-drift.png` preserves it. A warm/remap back to Wonder Woman preceded the accepted A1. A2 likewise has an explicit expected-title guard after warm/remap. There were shared functional/build gaps between blocks; no competing work ran during the exclusive timed blocks. Evidence: `app-matched-nikhil-summary.json`, block files and origin screenshots.

Optimized warm PSS was approximately 253.5–257.3 MiB and graphics 104.1–104.5 MiB. Both profiles share process/image caches during these switches; no independent cold-memory attribution is implied.

## Pending gates

- Latest normal-build real touch/D-pad, category boundary, View All/details return, category manager and Home regression checks for both profiles.
- At least 15 active minutes of navigation/background-return memory observation, excluding shared-lock pauses.
- Real-app first-usable-tile/artwork readiness and actual provider request counts are not quantified by these gfxinfo/component tests. Fake-source fixtures verify their own request behavior only.
- Older native-island touch-to-keyboard failures remain separately unproven; current recycled component passes its 16 cases.

This report is in progress and does not yet claim tablet validation passed.

## Subsequent real-device findings and scoped corrections

After ABBA, installed the known-good View All normal Dev build `movies-entry-final-dev-arm64.apk`, SHA-256 `455dbe791d6529924bbf1fc8ef0426dede09afb3a243a5d05b139906a5938447`, retaining data. Root's later packaging retry and final source corrections are tracked separately; the partial outputs from its failed package task were never installed on the tablet.

Wio held-D-pad popup opened the correct movie actions; Back restored the same Beware poster and exact bounds (`functional-wio-popup.report.json`, popup screenshot). No popup action was activated.

Physical mixed-input sequence exposed a default-route issue: D-pad selected tile1, touch selected tile0/Beware and opened the correct details, then Back preserved the viewport in touch mode. The next Right selected a partly clipped far-right tile, rather than entering from the touched movie. The initial host check's absent touch-mode border alone was not considered proof of a bug; the independently inspected first-Right screenshot demonstrates the wrong, clipped entry. Evidence: `functional-wio-touch-neighbor-{open,return,first-right}.png` and report.

Crossing seven categories reached content beyond the six-category window (`functional-wio-boundary-beyond-six.png`), but returning left an entire skeleton overlay above already-bound first-category cards. It remained after more than five minutes; poster edges were visible underneath (`functional-wio-boundary-settled-return.png`). The overlay depended on a fresh card-attach callback that unchanged cached holders need not receive.

Root authorized corrections in `RecycledDiscoveryRows.kt` and regression coverage in `RecycledDiscoveryDeviceTest.kt`. Both corrections have optional flags defaulting to false; Root opts them in only for optimized Movies, preserving Home's defaults:

- `restoreTouchSelection`: offers the island anchor for spatial entry, rejects a wrong native child before committing/highlighting it, and restores the saved item after a touch-mode return with an active window, a saved viewport and no competing root focus. The original entry arrow is consumed; a newer arrow proceeds. Queued correction respects newer requests, other controls and window ownership.
- `reconcileSkeletons`: when cached cards are already bound for the current row, clears the obsolete overlay on list commit/layout as well as on a fresh attach. It does not change provider loading, item limits or image concurrency.

Pre-fix local tests failed both entry and skeleton cases (`mixed-before.log`). An initial correction passed the skeleton case but still exposed clipped native entry (`mixed-after.log`); those logs remain. The actual-remount fixture uses a SaveableStateHolder and confirms saved `r0:i0` after remount. Subsequent modal setup failures were test-only Dialog construction on the instrumentation thread; the constructor was moved to the UI thread.

Final focused **three tests passed** (`mixed-verified.log`): physical touch with feed retained; physical touch/details with actual saved-state remount; and loading→same-cached-card overlay reconciliation. Both touch cases restore item0 on the first hardware arrow, assert full visibility, keep a separate modal's navigation focus, and continue the existing header/hero/details traversal. Immutable before/after APK metadata are in `tablet-mixed-*-build-artifacts.json`.

The **complete recycled device class passed all 19 tests**, zero failures (`recycled-functional-final.log`). Source/tests were frozen and handed back to Root. Root's final normal Dev APK is `movies-final-dev-arm64.apk`, SHA-256 `d8dd4a926ea8adda6f8fb2caaf129558f65bc9937303bb88781b8221db048a18`; final-build installation/retest is underway. Final real mixed-input and seven-category return checks, the remaining normal-app navigation checks, and the **15-active-minute** tablet navigation/background-return memory soak are explicitly **pending** for this Movies checkpoint. The local fixture passes and short warm ABBA results do not substitute for the pending real endurance gate.
