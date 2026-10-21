# Fire TV visible navigation review — 2026-10-21

## Scope and outcome

Physical-device follow-up to the discovery navigation work at commit `74636975`. The real-profile tests confirm remaining navigation stalls; they do **not** establish that the small candidate cleanup improves performance. No player changes were made in this follow-up.

Device: connected Fire TV `AFTKRT`, API 30, ARMv7, density 320, 60 Hz. Profile: Wio. Samsung and tablet were not touched. No commit or push was performed for this follow-up.

Artifacts are outside the repository under `D:/workspace/niktv-playback-tests/firetv-visible-20261021/`. Tables below name their `*-gfx.txt` files. These are Android `gfxinfo` frame-duration summaries, **not** input-to-focus, input-to-photon, route-opening or provider-response latency measurements.

## Method and limitations

- Timed runs had screen recording disabled. A bounded Java helper used persistent `InputManager` access and asynchronous injection; it did not spawn an ADB process for every tap. Event logs record accepted key events.
- The standard Home sweep used 20 Down/Up taps with requested 350 ms spacing; observed down-event spacing averaged about 379 ms. The rapid sweep averaged about 129 ms. Compare like input workloads.
- The measured baseline and initial candidate were debuggable builds. Runtime tooling and installation/startup warm-up can affect results. The existing `-PperformanceBuild=true` configuration is checked separately below.
- Runs were sequential, with changing artwork, caches, heap/GC state and profile work. Candidate warm-up was substantial. These are diagnostic measurements, not randomized repeated trials establishing causality.
- Whole-window histograms and the raw-frame ring buffer are different samples. For example, the first Home baseline contains 360 aggregate frames but only 120 raw frames spanning its final 2.452 seconds. Never describe raw-tail statistics as the complete run.
- Raw CPU analysis uses flag-zero frames from the nonempty visible root. Three trailing GPU timestamps in the first Home tail precede their corresponding CPU completion timestamps and were excluded from GPU calculations.
- Small route-transition samples are particularly sensitive to individual frames. A frame p95 of 700 ms does not mean Back took 700 ms.

## Home baseline and candidate

Whole-window summaries:

| Artifact stem | Frames | p50 | p95 | Deadline misses |
| --- | ---: | ---: | ---: | ---: |
| `home-warm-before` | 360 | 22 ms | 53 ms | 58 |
| `home-warm-before2` | 351 | 22 ms | 48 ms | 55 |
| `home-warm-before3` | 361 | 22 ms | 46 ms | 52 |
| `home-warm-after1` | 191 | 32 ms | 200 ms | 81 |
| `home-warm-after2` | 316 | 23 ms | 93 ms | 67 |
| `home-warm-after3` | 338 | 22 ms | 61 ms | 63 |
| `home-warm-after4` | 341 | 22 ms | 53 ms | 66 |
| `home-fast-before` | 101 | 26 ms | 105 ms | 30 |
| `home-fast-after` | 107 | 26 ms | 117 ms | 34 |

The candidate removes redundant image descriptions from discovery posters and cast portraits. Visible title/subtitle text and actions remain. The progressively improving candidate runs demonstrate warm-up; the final warm p95 merely matches the first baseline and exceeds the later baselines. Rapid-input p95 also did not improve. **No performance win is demonstrated.** The cast change does not affect a Home-only sweep.

`candidate-labels.xml` verifies that “Friends (English)” and the episode subtitle “The One Where Estelle Dies” remain in the native accessibility tree. Their action ancestor remains `clickable=true`, `long-clickable=true` and `focusable=true`. This supports retained labels and activation semantics; it is not a complete screen-reader interaction test.

## Where the time goes

The first baseline's 120-frame raw tail has actual UI-work p95 20.78 ms and maximum 71.63 ms. The Animation phase dominates that UI work: mean 8.29 ms, p95 15.49 ms, with 52.51 ms and 64.81 ms spikes. Compose recomposition/measurement can execute in animation callbacks; tiny platform traversal timings do not rule out Compose layout cost.

The same tail also shows rendering pressure: RenderThread CPU averages 9.24 ms; buffer-dequeue wait averages 2.99 ms and reaches 14.41 ms. The valid post-CPU GPU/fence interval averages 12.23 ms, p95 19.88 ms. Those intervals overlap pipeline work and must not all be added as independent CPU costs.

`home-method-sample2.trace` and `home-method-analysis2.txt` provide a separate instrumented method sample with no overflow: 5,159.781 ms Main CPU within a 9.265-second trace. Independent stack accounting found:

- Accessibility-processing union: 2,081.524 ms, or 40.3% of sampled Main CPU.
- `getCurrentSemanticsNodes`: 2,073.420 ms inclusive; 1,077.015 ms is already nested inside `scheduleScrollEventIfNeeded`. Do not add those inclusive totals.
- Discovery's scroll-animation lambda: 851.215 ms inclusive, separate from that accessibility union, including lazy-list measurement/layout rather than just the lambda's arithmetic.

Method tracing adds overhead and these CPU shares are not uninstrumented frame-duration measurements.

A temporary diagnostic disabled the Home-handler accessibility service, then restored it. `home-handler-inactive-diagnostic-gfx.txt` reports 404 frames, median 22 ms, p95 40 ms and 24 deadline misses. The unchanged median and better tail suggest accessibility contributes, while sequential cache/GC differences prevent treating this as isolated proof. Disabling the Home handler is **not** the proposed product fix.

The sibling `SimpleTvLauncher` source already requests only Amazon window-state events, with `canRetrieveWindowContent=false`. Cached Compose sources activate their accessibility bridge when any service is enabled; they do not gate that work on the service's package/event/content-retrieval restrictions. Removing duplicate decorative image semantics may reduce tree work without removing title/action semantics, but the candidate measurements above show no proven speedup.

## Movies, series and real playback

Baseline whole-window samples establish coverage and remaining stalls:

| Artifact stem | Frames | p95 |
| --- | ---: | ---: |
| `movies-entry-before` | 39 | 150 ms |
| `movies-warm-before` | 305 | 85 ms |
| `movies-horizontal-before` | 109 | 65 ms |
| `movie-detail-before` | 39 | 133 ms |
| `movie-return-before` | 25 | 200 ms |
| `series-entry-before` | 93 | 53 ms |
| `series-warm-before` | 356 | 61 ms |
| `series-detail-before` | 62 | 73 ms |
| `series-episode-reversal-before` | 29 | 53 ms |
| `series-return-before` | 5 | 105 ms |

The actual IPTV movie played on its first attempt and Back returned to its IPTV detail page. `movie-playback-return-gfx.txt` contains only 16 frames with p95 700 ms; this is a frame sample, not measured navigation latency.

The second movie opening **did not play** and timed out after 25 seconds. Back from the error returned to IPTV details; `movie-error-exit-gfx.txt` contains 20 frames with p95 400 ms. A quiet/low-cost loading screen is not evidence of successful playback. No provider or player fix was made, and this failure remains unresolved in this review.

`player-return-method.trace` is **invalid for exit-path attribution**: the tested Back press only hid controls while playback was connecting. Do not use its method costs to explain returning from player to details.

## Checks and outstanding work

- Selected discovery regression tests: 109 passed.
- Candidate APK build: passed; log `decorative-semantics-build.log`.
- Lint: failed with 26 existing errors, 330 warnings and 3 hints; the first reported error is `NikTubeDownloads.kt:203` / `WrongConstant`. See `decorative-semantics-lint.log`. This is not a lint pass.
- `git diff --check` passed. No generated captures, APKs, method traces or provider credentials were added to Git.

### Performance-build follow-up

The existing flag disables Android debugging for the same debug variant, package and signing identity. It does not enable R8, change buffers, remove artwork, shorten animations or disable accessibility. Both builds include the two-line decorative-image cleanup. Update distribution continues to use the `.debug` package identity; saved credentials and settings remain intact. Debugger attachment and `adb run-as` token provisioning are unavailable in this configuration. A normal Android Studio Run can replace it with an ordinary debuggable build.

Exact build/check commands, using JDK 17:

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' :app:assembleDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx5g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:lintDebug --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx5g -Dfile.encoding=UTF-8'
.\gradlew.bat :app:assembleDebug -PperformanceBuild=true --no-daemon -PskipLocalGithubToken=true --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx5g -Dfile.encoding=UTF-8'
```

The focused test/build command passed in 52 seconds (109 tests, zero failures/errors/skips). Lint failed in 2m55s as reported above. Performance build passed in 3m59s; log `physical-performance-build.log`. Preparation tasks were inspected; integrated source files did not change. Frozen normal/performance APKs are `decorative-debug.apk` and `decorative-performance.apk` in the external artifact directory. Both were installed with `adb -s 192.168.2.19:5555 install -r <apk>`, preserving app data.

Whole-window physical measurements with recording off and the normal Home-handler service enabled:

| Artifact stem | Frames | p50 | p95 | Deadline misses |
| --- | ---: | ---: | ---: | ---: |
| `home-performance1` | 299 | 23 ms | 125 ms | 50 |
| `home-performance2` | 399 | 21 ms | 34 ms | 26 |
| `home-performance3` | 411 | 13 ms | 28 ms | 16 |
| `home-performance4` | 419 | 18 ms | 26 ms | 10 |
| `home-performance-fast` | 142 | 23 ms | 57 ms | 19 |
| `movies-performance` | 377 | 22 ms | 61 ms | 36 |
| `series-performance` | 399 | 18 ms | 38 ms | 21 |

The fourth warm Home sweep is 26 ms versus 53 ms for the fourth ordinary candidate sweep; rapid-input p95 is 57 ms versus 117 ms. These are approximately 51% lower frame-duration tails in these sequential comparisons. Movies/Series sweeps also improved relative to their earlier debuggable samples (85→61 ms and 61→38 ms). These results support using the nondebuggable configuration for everyday TV acceptance tests, but do not isolate every runtime/cache/GC difference or prove all of the difference was caused by debugging. Movie playback testing changed the first two Continue Watching items' order before the performance run. No universal percentage gain is promised.

Home/Movies/Series menu entry, rapid input and direction reversals, horizontal row navigation, movie/series details and Back destinations were checked on the physical screen. The normal build's held-key and episode-entry reversal tests reached the expected destination; no additional focus-race fix was justified. The performance configuration's movie and series detail returns restored the originating Info control (`movie-performance-detail-return-after.png`, `series-performance-return-after.png`). Their five-frame samples have p95 109/69 ms and remain too small for robust latency claims. Player startup was not retested under the performance configuration; its earlier timeout remains unresolved. No claim covers every engine, cold network, screen reader, phone or tablet.

### Remaining scope and trade-offs

1. Preserve accessibility and the custom Home handler while investigating Compose's repeated semantics geometry reconstruction. Disabling that service would change remote Home behavior. Clearing all text semantics would damage reading/navigation; synthetic consolidation also loses native text layout/translation features. Neither was used as a fix.
2. Profile playback exit separately. The successful first return's raw frames show about 223 ms before traversal and 305 ms from draw start to synchronization, followed by substantial Vsync backlog; GPU p95 is only 15 ms. Exo's synchronous `release()` and reconstruction of the catalog plus provider-detail Dialog are source-grounded candidates, not individually proven causes. VLC/mpv already queue native cleanup; blindly moving player APIs onto IO would violate thread requirements. Preserve the detail presentation and measure release, first draw and focus separately before changing this path.
3. Startup/cache hydration and retained artwork remain additional profiling targets. No cache-size, image-quality, provider-feature or animation trade-off was introduced. Warm Home p95 still exceeds one 60 Hz frame, and rapid input remains much worse. This is not zero-stutter acceptance.

### Final device state

The tested nondebuggable performance APK is left installed on Fire TV, on Wio Home with Home focused. Package flags were verified without `DEBUGGABLE`. Test playback is stopped. The original media mute state was restored to **false**, retaining speaker/HDMI/default levels **1/15/3**. The original Home service string `com.nikhil.tvlauncher/com.nikhil.tvlauncher.HomeService` and `accessibility_enabled=1` were verified restored. Animation scale was not changed. The temporary `/data/local/tmp/niktv-dpad.jar` helper was removed. Samsung and tablet remain untouched.

Only `DiscoveryUi.kt` and this report/planning references changed in this follow-up. The user subsequently requested committing these changes. No push is requested. The regular build defaults remain debuggable.

## Getting production-grade performance

### Available now

Fire TV already has the tested nondebuggable performance build. There is no additional device setting to enable. To build the same configuration again from `D:/workspace/android/nikTv`, use JDK 17 and:

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
.\gradlew.bat :app:assembleDebug -PperformanceBuild=true -PskipLocalGithubToken=true
```

For this Fire TV, install the resulting `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` with `adb install -r` against its selected serial. Do not uninstall or clear data. It retains the existing debug package/signature when built with the same signing configuration. Ordinary Android Studio Run builds without that property are debuggable again; keep normal development runs separate from performance acceptance tests. The performance flag is not a complete release-optimization pipeline.

### Engineering work still required

1. **Correctness first:** diagnose the 25-second repeat-play startup failure independently of UI animation. Attribute playback-exit stalls to player release versus catalog/detail reconstruction, then preserve the needed presentation state without violating player thread affinity or retaining active streams.
2. **Scrolling:** reduce repeated semantics/layout work while preserving native accessibility and custom remote Home behavior. Use fresh traces of the nondebuggable configuration where supported; the earlier 40.3% share was measured in the debuggable process.
3. **Release optimization:** configure and validate R8 code optimization/shrinking and appropriate resource shrinking. The app's current release block does not explicitly enable them. Test integrated Flow/SmartTube, native players, serialization and reflection-dependent behavior before using the optimized APK. No keep-rule changes or release build are implemented by this commit.
4. **First-run performance:** add NikTV-specific Baseline and Startup Profiles for profile entry, Home/Movies/Series, D-pad scrolling, details and playback return. The app build currently has no dedicated profile-generation/Macrobenchmark setup. Library-provided profiles do not replace profiling these app journeys.
5. **Release gate:** address the 26 lint errors, add repeatable physical benchmarks for cold/warm starts and rapid navigation, and validate the same optimized artifact on Fire TV, tablet and Samsung. Include artwork readiness, memory over repeated navigation, native-player entry/exit and provider failures. Keep recordings/tracing separate from timed runs. A 60 Hz frame budget is about 16.7 ms; current tails still exceed it.

Android's [Compose performance guidance](https://developer.android.com/develop/ui/compose/performance) recommends release builds with R8 and app-specific profiles. [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview) precompile important journeys to reduce reliance on runtime warm-up. Those are next steps for this app, not measured improvements already delivered here.
