# Hero transition and Home tile comparison

2026-11-02. Xiaomi Pad 5 (6889f3db), Wio, Dev, landscape. No Fire TV access, release builds, personal data clearing, playback or commits. Original Home implementation remains the normal path; new behaviors are diagnostic Home 2 modes. Metadata switch stayed off.

## Changes tested

1. Isolated hero updates: the feed parent collects state with hero index/presentation changes removed while no detail page is open; the header collects the actual current hero. A rotation therefore need not invalidate the entire feed. Profile, feed, detail and navigation changes remain reactive. Tests verify rotations are filtered but profile/detail changes are retained. Normal Home still collects its original StateFlow. The timer repeats while eligible because the filtered parent index stays constant. Actions and carousel use the actual header identity.
2. Home-like native proportions: an independent diagnostic retains RecyclerView, sources, progress and focus policy, but shortens rows from 220 dp at normal font scale to 167 dp and scales caption fonts from 12/10 sp to approximately 8.7/7.4 sp. This approximates Home's 2:3 poster and caption ratios at the native 104 dp width. It is not an exact port of Home's renderer, badges, unbounded text or responsive sizing.
3. Next-image preparation: another independent diagnostic prepares only one adjacent 1280 x 720 shaded backdrop after one idle second, using the shared two-request semaphore, memory-pressure guard, cancellation and Bitmap.prepareToDraw. No metadata/provider lookup or extra stream is introduced. Extra image/texture memory and public artwork traffic are trade-offs; no measured PSS delta is claimed. This option is not recommended as a default because it did not consistently improve the measured transitions.

## Transition result: still unresolved

First APK, baseline/isolation/isolation/baseline order, same starting hero via route reset, ten-second idle windows:

| Mode | Worst retained pipeline frame | Missed deadlines / frames |
|---|---:|---:|
| Existing rotation, first | 148.350 ms | 4 / 40 |
| Isolated hero, first | 126.648 ms | 2 / 40 |
| Isolated hero, repeat | 115.732 ms | 3 / 38 |
| Existing rotation, repeat | 96.739 ms | 2 / 38 |

The ranges overlap; this is not proof of a consistent transition improvement. Steady navigation remains broadly similar. Keep the earlier 172 ms observation as historical evidence, not a matched before/after comparison.

Second APK, isolation/prepared/prepared/isolation order:

| Mode | Worst retained pipeline frame | Missed deadlines / frames |
|---|---:|---:|
| Isolated control A | 120.783 ms | 3 / 37 |
| Prepared next artwork A | 118.380 ms | 2 / 39 |
| Prepared next artwork B | 106.617 ms | 2 / 38 |
| Isolated control B | 87.848 ms | 2 / 37 |

Preparing the next bitmap is insufficient. An independent 32.876-second sampled method trace includes approximately 65 ms exclusive CPU in native measured-text construction and 93 ms in renderer synchronization. These are aggregate trace samples, not per-transition timings or proof of a single root cause. Text shaping and rendering synchronization warrant targeted work; image loading alone does not explain the hitch. Profiling perturbs execution and is excluded from timing results. Trace stop flushed asynchronously: the initial pulled file was empty, then a later pull yielded a valid 790 KB trace without overflow.

## Tiles: what differs

| Feature | Home | Home 2 |
|---|---|---|
| Renderer | Compose lazy rows/posters | Recycled native rows/cards |
| Artwork | 2:3 posters; separate phone activity-card layout | Fixed 104 dp card width; tall contained-caption footprint |
| Text | Size scaled to poster width; variable title/subtitle text | Two title lines, one subtitle line; fixed reserved space |
| Source indicators | Dedicated overlays/badges | Inline source prefix |
| Focus | Device-dependent Compose animation/border | Native 1.025 scale, contained border, 320 ms motion |
| Data | Original feed/presentation policy | Trial feed/window/request policy |

Home and Home 2 already share the broad visual treatment: artwork, bottom gradient, captions inside the tile and progress indicators. Copying the visual style does not require replacing the recycled renderer.

First-APK scrolling results (Down x3/Up x3 at requested 250 ms cadence; no recording):

| Variant | Per-run p95 | Median of run p95 |
|---|---|---:|
| Isolated compact cards, A | 22 / 15 / 16 ms | 16 ms |
| Isolated compact cards, B | 23 / 15 / 15 ms | 15 ms |
| Home-like native proportions | 26 / 19 / 17 ms | 19 ms |
| Isolated compact cards, repeated C | 20 / 19 / 14 ms | 19 ms |
| Original Home full screen | 101 / 117 / 97 ms | 101 ms |

Home-like native proportions measured 0-4 ms higher than the nearby control medians; across all nine compact control runs, median p95 is 16 ms versus 19 ms for the three candidate runs (+3 ms). These small samples and ordering differences do not establish a fixed styling cost. Shorter rows also expose more content at once. Larger fonts/portrait and exact visual parity remain untested.

The original Home samples started on its verified first Continue Watching card. Native samples started at hero Play, with different viewport geometry and feed presentation. Thus the 101 ms Home result is a whole-screen scenario comparison, not an isolated cost of tile styling or a prediction that copying tile appearance costs 80 ms. Home's full renderer, layout, artwork/presentation behavior and other content differ. Recommend retaining native recycling and evaluating visual changes individually.

## Verification and delivery

- 203 targeted unit tests passed, zero failures/errors. New coverage protects feed isolation across hero changes, profiles and details. No new device-instrumentation run for these diagnostic variants; prior Phase 12 fixture results are prior evidence only.
- Final Dev build installed on tablet, isolated hero mode selected, compact tiles retained, metadata and extra image preparation off. Existing Home, playback engines and defaults were not migrated.
- Final source differs from the timed prepared APK only in one-time initial Flow snapshot capture, moved to a ViewModel snapshot API and remembered instead of directly reading StateFlow.value during composition. Ongoing updates still come from the collected Flow. This fixes a new lint error without suppression. Final build/tests rerun afterward; final lint result recorded separately.
- Secret scanner: zero findings; whitespace check passed. No commit/push. Long-running memory/thermal behavior, controlled cold-cache behavior, Nikhil, phone, Fire TV and large fonts remain unmeasured.

Exact command patterns (JDK 17):

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:lintDebug -PskipLocalGithubToken=true --console=plain
adb -s 6889f3db install -r D:/workspace/niktv-playback-tests/hero-isolation-tiles/final-dev.apk
python D:/workspace/niktv-playback-tests/hero-isolation-tiles/compare.py
python D:/workspace/niktv-playback-tests/hero-isolation-tiles/prepared-compare.py
adb -s 6889f3db shell am profile start --sampling 1000 com.nikhil.niktv.debug /data/local/tmp/niktv-hero.trace
adb -s 6889f3db shell am profile stop com.nikhil.niktv.debug
python tools/check-source-secrets.py
git diff --check
```

Evidence root: D:/workspace/niktv-playback-tests/hero-isolation-tiles/. diagnostic-dev.apk is the first comparison; prepared-dev.apk the second; final-dev.apk the final snapshot correction. Raw counters, screenshots, scripts and sampled trace stay outside git. Failed host-script attempts (missing import/bracket, profile-selection state, old coordinate guard) were not counted. Original Home measurement followed manual first-row focus verification; no invalid coordinate-guard run is included.

Final lint: 22 existing errors, 348 warnings, three hints; all error source lines verified against HEAD, no suppression. The newly introduced direct StateFlow-read error is gone.
