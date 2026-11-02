# Home 2 Phase 8 — hero layout and actions

Phase 7 and its measured costs were committed as **`0f763045`** before starting this phase. Phase 8 remains a separate, uncommitted change. Nothing was pushed. Original Home/Movies/Series remain on CONTROL; Fire TV was not contacted, installed or tested.

## Implementation

Home 2's new **Stage 8 · hero layout and actions** builds on 7B. The existing app hero composable supplies the title, available year/genres/rating, two-line synopsis, Play, My List, trailer, Info and manual previous/next controls. It is hosted in a distinct recycled header view type and **scrolls away with the feed**, instead of permanently consuming half the media viewport.

The nested Compose view inherits the app's composition context/theme. Header pooling is bounded and compositions are released with RecyclerView pooling/root disposal. Existing playback, profile-scoped favorites, trailer and detail callbacks are reused. Display artwork is stripped only from the presentation copy; actions retain the complete original media record and artwork metadata.

Hero posters, backdrops and title logos remain disabled. No new hero metadata requests, automatic rotation, provider matching, media preparation or streams are added. Phase 9 will measure static hero artwork separately. Existing caption artwork in the media rows remains enabled as in Phase 7.

Real testing found that the saved row cursor could bypass the hero after menu navigation. Entry policy now distinguishes a fresh menu entry (hero Play) from a detail return (saved row/button). The menu preference is consumed when hero focus arrives, retaining later touch/D-pad row restoration. Top navigation → hero → media → hero → top navigation and Info → details → Info are verified. Native callbacks reject provisional focus while another target is pending; feed diffs do not reset an already focused hero action.

The default experiment remains Stage 1. This phase has not replaced or modified the familiar Home UI. Other trial source modes without featured TMDB data are not part of the validated hero coverage; Movies/Series adaptation remains later work.

## Final delivery comparison — tablet Wio Dev

Xiaomi Pad 5, landscape 2560×1600, display reports 120 Hz. No recording, tracing, system animation/refresh changes or cache clearing. Inputs: first Continue Watching tile (Spider-Man: Brand New Day), three Down then three Up, targeting 250 ms cadence. Raw host timestamps, display context, memory snapshots, screenshots and gfxinfo are retained.

Both final groups use the same delivered APK, SHA-256 **`3a72c368bf5681ff41b708485e62260fe3ea848bbdaf77f589cf865c287f0cce`**. Table values are global frame-counter percentiles, not input latency or FPS. Higher is slower.

| Final-build case | Run p95 values | Median of run p95 | Deadline misses / frames |
| --- | --- | ---: | ---: |
| 7B control | 20, 17, 16 ms | 17 ms | 8 / 553 |
| Stage 8 hero | 17, 19, 18 ms | 18 ms | 14 / 559 |

**Observed added cost: approximately 1 ms at the median, around 2 ms in the two warm repeats.** Ranges overlap and there are only three samples per mode. Deadline misses rose from roughly 1.4% to 2.5% in these short groups; the hero is not free and this is not proof of performance equivalence.

The first traversal begins with a visible hero, changing initial geometry and scroll distance. Later returns place it offscreen, where the media viewport matches 7B more closely. Initial and warm results must remain distinct. These are first measured traversals after a stage switch, not controlled cold-cache trials.

An earlier near-final candidate recorded Stage 8 p95 **29, 18, 16 ms**, followed by **17, 18, 18 ms** on a repeat group, against a 17/17/17 ms 7B control on that same candidate. The 29 ms first pass was not reproduced by the final sample; its cause remains unproven. Do not claim the entry correction eliminated all first-pass stalls.

On that earlier candidate, whole-page touch swipes measured 7B **16/17/18 ms** (37 misses / 971 frames) and Stage 8 **16/17/17 ms** (51 / 969). Those tests have different visible content mixes and scroll distance through the hero, so they are scenario checks rather than an isolated rendering delta. Final-build touch smoke captures confirm the hero actually scrolls offscreen (`delivery-hero-touch-mid.png`) and can be scrolled back to the page top (`delivery-hero-preview.png`).

Global counters cover the reset intervals, but raw framestats rings are **partial**. They cannot establish full-interval phase maxima or identify a Kotlin function as the cause of every stall. No fresh SmartTube, Fire TV, Nikhil-profile, pixel input-latency, controlled cold-cache or long thermal/memory measurements were performed.

Final PSS snapshots vary from about 287–289 MiB in the 7B group to 278 MiB in Stage 8. Collection/cache trimming can explain differences; this is **not** evidence that adding a hero saves memory. Allocation isolation and a sustained soak are still required.

## Verification

- **199 targeted unit tests passed**, including original-route protection and single-feature stage configuration.
- Four isolated tablet checks passed in **15.594 s**, covering styled/baseline 3,200-record feeds, native/Compose hero focus handoff, full tile visibility, direction reversals, detail return, forced menu entry overriding a saved media cursor, preserved row columns and original Home isolation. Final-source run is in `delivery-device-tests.log`.
- Actual hero Info opened Dexter: Resurrection's details, and Back restored Info (`hero-info-return.png`). Manual Next changed the title and metadata to Verity while retaining Next focus (`hero-next-title.png`); Previous returned to Dexter. Play/My List/trailer handlers are reused, but actual playback, favorite mutation and trailer launch were not exercised in this performance pass.
- Normal Dev and isolated fixture APK builds passed. Final lint still fails with **22 existing errors**, all source lines checked against committed HEAD (`lint-audit.json`). No suppression or unrelated lint edits.
- Secret scan and whitespace checks passed. Temporary tablet fixtures are removed after testing. Regular app data is preserved. No release build or Fire TV command.

Representative exact commands (JAVA_HOME is the installed JDK 17):

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s 6889f3db shell am instrument -w -r -e class com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest,com.nikhil.niktv.ui.DiscoveryExperimentDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
gradlew.bat :app:lintDebug -PskipLocalGithubToken=true --console=plain
python tools/check-source-secrets.py
git diff --check
```

Evidence root: `D:/workspace/niktv-playback-tests/home2-phase8-tablet/`. `delivery-stage7-*` and `delivery-stage8-*` are the primary final-APK runs. `stage7-control-*` precedes the entry fix and is contextual only. `stage7-final-*`, `stage8-rows-*`, `stage8-repeat-*` and the touch groups use the preceding entry-fixed candidate (SHA `868986dc…c99e915`). Keep version qualifications with the results.

## Next

Keep the hero isolated in Home 2. Validate on Fire TV only when the user explicitly makes it available, investigate first exposure and the earlier Phase 7 return/loading flash, and cover accessibility and longer resource pressure. Add **static hero artwork as Phase 9**, measuring decode/upload and row navigation separately before enabling crossfades or focus-driven backdrops. Do not add ranking numbers or skeleton UI, and do not migrate original destinations yet.
