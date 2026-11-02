# Home 2 Phase 7 — tablet comparison, 2026-11-01

Phase 7 is implemented only in Home 2 and was subsequently committed as `0f763045`. Original Home/Movies/Series remain on CONTROL. No Fire TV interaction, installation, release build or push occurred. Ranking numbers are deliberately excluded, as requested.

## Changes

- **7A — contained captions:** title and subtitle move over the artwork with a dark readability gradient. Existing progress stays below the caption at the tile's bottom. Title space remains two lines; subtitle remains one line.
- **7B — borders and shadows:** adds the app's rounded border finish and a restrained 4 dp elevation on the focused tile. Focus animation remains the Stage 6 animation.
- Stage 6, 7A and 7B remain independently selectable in Home 2's performance menu. Stage 1 remains the default baseline.
- Width, row height, spacing, content limits, callbacks and image request/decode size remain constant for this comparison. Artwork fills the former caption band in 7A/B, so its crop changes. Matching the original Home's exact poster aspect/density is a separate geometry experiment, not silently included in these numbers.
- The renderer keeps recycled holders, stable identities, cancellation, capped image work and existing navigation/detail routes. No blur, ranking labels, new provider searches or concurrent streams are introduced.

## Method

Xiaomi Pad 5, serial `6889f3db`, Wio profile, landscape 2560×1600, normal **Dev** package `com.nikhil.niktv.debug`. The physical display reports 120 Hz; this is not a claim that the app renders 120 FPS or that every frame has the same scheduling budget. No system animation/refresh settings or personal caches were cleared.

Primary comparisons use one installed APK: SHA-256 `b1e8f53b81e163f5e55f91e30587b659c6fc478451289e4dbcd6c6f404ef8003`. Earlier `stage6-pre-valid-*` samples are contextual pre-change observations and excluded from the primary table.

Screenshots verified the selected first Continue Watching card (Spider-Man: Brand New Day), the same movie/series discovery rows and return identity. Measurements used cached real content, without recording or tracing. Vertical D-pad: three Down/three Up. Horizontal: four Right/four Left through the five Continue Watching items. Each key targets 250 ms cadence; actual host timestamps are retained. Touch: two fixed 710-pixel upward swipes and two downward swipes, 450 ms each. First measured traversals are **not** controlled cold-cache tests.

The initial optional Java input process exited 137; its cause was not established. The incomplete `stage6-pre-v1` run is excluded. Every accepted sample uses standard ADB input commands, explicitly addressed to the tablet. The Fire TV driver's STOP guard remains in place.

## Global frame results

Higher p95 means slower frames. Each list contains three separate run p95 values; the median column is the median of those run percentiles, not a pooled frame percentile.

| Case | p95 per run (ms) | Median p95 (ms) | Deadline misses / frames |
| --- | --- | ---: | ---: |
| Stage 6, vertical | 14, 13, 15 | 14 | 7 / 557 |
| 7A, vertical | 17, 18, 15 | 17 | 8 / 567 |
| 7B, vertical | 17, 16, 16 | 16 | 8 / 557 |
| Stage 6, later vertical control | 16, 13, 12 | 13 | 8 / 552 |
| Stage 6, horizontal | 11, 11, 16 | 11 | 6 / 743 |
| 7B, horizontal | 13, 14, 16 | 14 | 4 / 737 |
| Stage 6, first touch group | 14, 14, 13 | 14 | 85 / 906 |
| 7B, touch | 19, 17, 17 | 17 | 40 / 958 |
| Stage 6, later touch control | 15, 13, 13 | 13 | 47 / 956 |

The measured styling overhead is approximately **2–4 ms vertical**, **3 ms horizontal**, and **3–4 ms touch**, using these small-sample median comparisons. The contained-caption/artwork treatment accounts for the visible difference in this experiment; the border/shadow addition has no separately resolved extra cost. 7B's lower median than 7A is within variation and is not evidence that shadows accelerate rendering.

Touch deadline misses improve considerably after the first group, including in the later Stage 6 control. Cache warming, traversal and adaptive scheduling remain confounders; do not attribute the lower 7B miss count to the styling or describe it as a general performance improvement. p95 and miss counts tell different parts of the story. Initial samples also contain p99 frame times around 53–61 ms, so this is not a zero-stutter result.

Global gfxinfo counters cover each reset interval. **Raw framestats rings are partial in all these runs**, so their phase samples cannot identify the cause of every slow frame or establish full-interval UI maxima. No pixel input-to-focus latency, controlled thermal behavior or fresh SmartTube comparison was measured.

After the second/third vertical passes, total app PSS is approximately 280 MiB at Stage 6 versus 283 MiB at 7B. Touch traversals retain more warmed data (roughly 288–295 MiB across later samples). These are short process snapshots including caches and graphics, not isolated allocation measurements or a memory-leak soak.

## Checks

- **199 targeted unit tests passed**, including original-route protection and exactly one configuration change between stages.
- **Three tablet instrumentation checks passed in 10.994 s:** original Home isolation with the legacy preference enabled; 3,200-record baseline recycling/navigation; styled recycling/navigation. Both recycled cases check attached-view bounds, rapid reversals, full focused-card visibility, header re-entry and detail return.
- Normal Dev APK and isolated target/test APKs assembled successfully. Lint failed with the same **22 existing errors**; each error source line was checked against HEAD. This is not a full lint pass.
- Secret scan: zero findings. Whitespace checks passed. Temporary tablet regression packages were removed afterward. No personal app data was cleared.
- Actual Wio TMDB movie (A Story of Derry) and series (Dexter: Resurrection) detail pages were opened without Play. Settled Back state returned to their fully visible focused tiles; screenshots are `phase7-movie-return.png` and `phase7-series-return-settled.png`. The immediate series-return capture (`phase7-series-return.png`) shows blank artwork and provisional first-row focus before restoration completes. This transient return/loading flash remains an unresolved polish issue; its duration and Stage 6 parity were not measured. Do not describe the actual series transition as glitch-free. The tablet is left in styled Home 2.

Commands, with JAVA_HOME set to the installed JDK 17:

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
adb -s 6889f3db shell am instrument -w -r -e class com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest,com.nikhil.niktv.ui.DiscoveryExperimentDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
gradlew.bat :app:lintDebug -PskipLocalGithubToken=true --console=plain
python tools/check-source-secrets.py
git diff --check
```

Evidence: `D:/workspace/niktv-playback-tests/phase1-tablet/`. `phase7-summary.json` aggregates the unfiltered global results and memory snapshots; raw gfxinfo, input timestamps, display context, screenshots, scripts, APKs and test/build logs remain beside it. No captures or private catalogue records are committed.

## Decision and remaining work

Keep 7A/B in Home 2 for review. The styling has a measurable small rendering cost on this tablet; it has not established production parity or Fire TV suitability. Original Home remains unchanged.

Before migration: investigate the transient series-return flash, repeat on Fire TV only after authorization, validate Nikhil's feed, run longer memory/thermal tests and measure visible focus latency. If the added cost is too high on Fire TV, compare a caption-only gradient region and reduced overdraw before dropping functionality; that optimization is a hypothesis, not a tested improvement in this delivery. Exact poster density/aspect, missing-artwork decoration and hero features remain separate work.
