# Fire TV Home 2 performance investigation — 2026-11-01

## Scope and current result

Physical Fire TV tests compare original Home/Movies/Series with the bounded native Home 2 trial and SmartTube. Both wio and Nikhil profiles were exercised. This is an extension of the [tablet experiment](home2-scroll-experiments.md), not a replacement of the original destinations.

The native trial substantially reduces the long UI-thread/frame-callback stalls observed on the original screens. Its short warm runs are in the SmartTube reference range. First-pass presentation costs remain visible, and the trial omits features and limits content, so these results do not establish equal functionality or production parity.

**Measured-source checks:** Nikhil native Series now populates four rows after bounded provider-preview binding and was visually traversed and measured twice on APK `14BE…F9C0`: 8 ms p95 with no deadline misses in each run. Category artwork was missing, so the cards used plain placeholders; this is not an artwork-loaded equivalent of the original screen. The same APK's wio Home smoke check, with four rows and cached artwork, measured 10 ms p95 with 1/113 deadline misses and 2/113 legacy janky frames. A subsequent return-only fix was built and installed as `6D53…1C3A3`, with targeted return visibility checks passing; the full timing matrix was not rerun on that final APK. Other matrix runs predate the preview-binding/viewport-clipping changes, as qualified below.

## Method and build context

- Physical Fire TV at `192.168.2.19:5555`, with user permission to use the screen. No playback was started for these navigation tests.
- NikTV normal Dev (`com.nikhil.niktv.debug`) versus installed production SmartTube (`org.smarttube.stable`). No release build was installed or replaced.
- Active physical output mode was 3840×2160 at 59.94 Hz, approximately a 16.68 ms refresh budget. This does not establish the application's actual render-buffer resolution.
- D-pad workload: six vertical directions at 250 ms cadence, and a horizontal reference run. Source, profile and first-card origin were verified with screenshots/hierarchy captures outside timed intervals. No recording or sampling trace ran during timed comparisons.
- Global `dumpsys gfxinfo` counters cover the reset interval. Its histogram p95 and retained-ring p95 can differ because of histogram buckets, quantile conventions and partial rings. These are rendering/pipeline timings, **not pixel input-to-focus latency or FPS**.
- This Fire OS dump lacks per-frame `FrameDeadline`. Global deadline misses are available; zero retained deadline rows means unavailable timestamps, not zero missed deadlines.
- Native mode uses a maximum of four rows and eight cards per row. It omits hero/carousel, changing backgrounds, ranking numerals, graphical progress overlays and the original Compose poster effects. Small cached focus motion remains. Different catalogue counts, geometry, request state, process/cache history and build types prevent a strict matched-content speedup claim.

## Verified vertical results

Values are global frame-time histogram p95 in milliseconds. Repeats are reported separately, including slower first passes.

| Profile / surface | Original p95 | Native p95, pass 1 / pass 2 | Original deadline misses / frames | Native deadline misses / frames |
|---|---:|---:|---|---|
| wio Home | 150 / 150 | 10 / 9 | 17/42, 17/34 | 1/112, 0/113 |
| wio IPTV Movies | 150 / 150 | 18 / 11 | 17/39, 15/35 | 2/111, 0/113 |
| wio IPTV Series | 200 | 10 / 9 | 17/32 | 2/114, 0/113 |
| Nikhil Home | 69 | 12 / 9 | 20/47 | 1/113, 0/113 |
| Nikhil IPTV Movies | 125 | 24 / 9 | 19/42 | 1/113, 0/113 |
| Nikhil IPTV Series | 125 | 8 / 8* | 11/38 | 0/113, 0/113 |

wio Home native measurements precede the subsequent native-to-header focus fix. Movies/Series fixed-native runs and Nikhil Home/Movies native runs include that fix, but precede the later provider-preview scope and viewport-clipping changes. Nikhil Series is measured on APK `14BE…F9C0`, before the final return-only adjustment. These results must not be silently described as a complete final-source acceptance matrix. *Nikhil native Series had four populated rows but missing category artwork/plain placeholders, reducing image work relative to an artwork-loaded screen.*

The additional wio Home run on APK `14BE…F9C0` (`wio-home-final-native-v1`) measured 10 ms p95, 1/113 deadline misses and 2/113 legacy janky frames. All 113 frames were retained; UI-work p95 was 1.227 ms and retained pipeline maximum was 19.923 ms. This is a smoke check on that measured source, not a rerun of every profile/surface on the final APK `6D53…1C3A3`.

The original Movies path includes intermediate Search/All header focus stops whereas the native trial goes directly between rows. Even with first-card origins verified, their paths do not represent identical scrolling work.

SmartTube vertical reference: **24 then 14 ms p95**, with 3/151 then 1/153 deadline misses. Horizontal reference: **12 ms**, 0/137 misses. Native wio Home horizontal: **10 ms**, 0/96 misses. SmartTube's retained frame rings are partial; original/native runs listed above retained all their global frames at this checkpoint.

### First-pass presentation costs must remain visible

Nikhil native Movies pass 1 recorded **92/113 legacy janky frames**, despite only one global deadline miss and a 24 ms p95. Retained UI-work p95 was 1.379 ms, while sync-wait p95 was 8.697 ms and retained pipeline p95 was 26.114 ms. Pass 2 fell to 9 ms with zero janky frames/deadline misses. The discrepancy between legacy jank and deadline counters must not be hidden or reduced to a single score. It points to a presentation/synchronization cost in that first pass; the exact cause remains unproven.

wio Movies also had a slower first pass: 18 then 11 ms, with sync-wait p95 7.336 then 0.472 ms. These are first measured passes, not controlled cold-cache trials; no app data or caches were cleared.

## What is consuming time

Original wio Home first-card repeats have long UI/start-delay tails. One repeat's UI-work p95 was 140 ms; maxima were approximately 157–160 ms. The separate pre-update baseline had three Choreographer callback spans above 100 ms. Its worst 205 ms frame included approximately 191 ms before UI handling began. Layout was very small and buffer dequeue waits were approximately 0.14–0.26 ms.

The broad `AnimationStart → PerformTraversalsStart` span includes frame callbacks and Compose work. It is not proof that a visual animator alone caused the stall. Start delay can reflect earlier main-thread work or scheduling; these dumps do not identify a Kotlin function or prove a provider/network cause. GPU p95 of 24 ms in that pre-update sample can exceed the refresh budget but does not explain the 100–190 ms CPU-side tails.

Native wio Home UI-work p95 was approximately 1.3–1.8 ms, versus SmartTube's 1.6–3.2 ms vertical reference. That supports the simpler retained renderer as an effective direction. It does not isolate which omitted feature can be restored safely. Trial content caps and changed geometry are part of the intervention.

## Functional issues found during real testing

1. **Native first-row Up could leave Android focus in the native island while Compose appeared to focus the header.** The narrow fix releases native descendant/root focus before transferring to the Compose host and header, and prevents queued native entry restoration from competing with that handoff. This preserves row selection for Down reentry.
2. **Preview loading still bound more provider categories than the renderer could show.** The trial now bounds its preview provider scope to its row budget while keeping the full selection for full collections and search. Persistent category selections and normal complete catalogues remain intact. A global 12,000-item cache budget could theoretically starve a later category, but it has not been established as the cause of the observed empty Nikhil Series trial.
3. **Native cards could draw above the scrolling viewport and over the header.** The final native scroll view uses its bounds as an outline and clips to that outline. An actual scrolled-to-bottom screenshot verified the header remains clear. The final Nikhil Series measurements include this fix; preceding matrix runs do not.
4. **Returning from movie details could restore focus but partially hide the card.** A posted saved `scrollTo(top)` could override the platform's focused-card reveal. The final return-only change skips that scroll restoration when a tagged native card already owns focus. Actual Back checks opened A Story of Derry movie details and IT: Welcome to Derry series details without pressing Play; both returned to visible originating cards. `actual-return-fixed-movie.png` shows the focused movie card fully visible. The remote fixture now requires the complete focused tile rectangle to fit inside the viewport, retaining focus-identity assertions.

These fixes are separate from playback engines. The user-requested pending intro/still-watching work was committed as `07faf468`; its focused test result must not be used as performance coverage for these navigation changes.

## Verification status

- Actual screenshots and D-pad testing verified profile/source identity and exposed the header handoff issue.
- The final normal Dev APK built, was installed on Fire TV and its installed signature was verified. SHA-256: `6D53842382E1FDDA44D3249BA8AE00D785AA356486C4077377658AF1E021C3A3`. Earlier timing smoke checks used `14BEFF1B1689939D232AC60B5AD90DD8990983D0196581722F131A22E5A8F9C0`, before the return-only adjustment.
- **44 focused unit tests passed.** Final lint still failed with the same **22 existing errors** (`return-final-lint.log`); the combined verification command is not a full pass. Earlier build/check logs remain in the evidence root.
- A clean remote-only native fixture passed **two tests** in 7.454 s (`remote-only-verified.log`), covering both native motion modes, header handoff/reentry and detail return. The strengthened final-source fixture also passed **two tests** in 7.348 s (`return-final-visibility-device.log`), additionally requiring the complete focused tile to be inside the viewport after returning. This is remote-only coverage, not a full accessibility or touch pass.
- Full native fixtures on Fire TV failed accessibility exposure and touch-to-remote reentry assertions. The separate experiment fixture failed an Options assertion while Fire TV UiAutomation exposed a background tree despite the actual dialog being visible and navigable. These checks remain failures; they are not replaced with a full accessibility pass.
- In-app TalkBack and touch behavior on Fire TV are unverified. Passing tablet fixtures do not establish Fire TV accessibility correctness.
- No personal app data, cache or account configuration was cleared. Browsing warmed ordinary caches. No release app was changed.

## Exclusions and reproducibility

Evidence root: `D:/workspace/niktv-playback-tests/firetv-home2-20261101/`. The external `exclusions-registry.json` explicitly classifies runs; transport completion is insufficient without correct screen/source identity.

- `wio-home-original-v1/v2`: began at the hero Play action, not the first tile. Exploratory; excluded from paired first-card results.
- `wio-home-original-h1`: origin not established in the verified key-run list; excluded from paired comparison pending verification.
- `wio-movies-original-v2`: start unverified after intermediate header stops prevented return to origin. Replaced by `wio-movies-original-verified-v2`.
- `wio-movies-native-v1/v2`: setup failed to open Performance experiment; actual source remained Home. Excluded as mislabeled Movies results. Replaced by `wio-movies-fixed-native-v1/v2`.
- `wio-existing-home-vertical`: valid separate pre-update baseline, 150 ms p95 and 19/47 misses; not mixed into current-APK repeats.

Native Home run labels are `wio-home-native-v1/v2`; fixed-source catalogue labels are `wio-movies-fixed-native-v1/v2`, `wio-series-fixed-native-v1/v2`, `nikhil-home-fixed-native-v1/v2` and `nikhil-movies-fixed-native-v1/v2`. Final-APK labels are `nikhil-series-final-native-v1/v2` and `wio-home-final-native-v1`; the latter's starting screenshot is `final-wio-home-start.png`. Original first-card Home labels use `wio-home-original-tile-v1/v2`; remaining accepted original/source labels are named in the external registry. Files retain raw gfxinfo, input timestamps, display context, memory samples and completion/foreground guards.

## Remaining work before replacing original screens

- Run timing repetitions on final APK `6D53…1C3A3`, beyond the earlier `14BE…F9C0` wio Home/Nikhil Series smoke checks. Recheck Nikhil Series with working artwork rather than treating placeholder rendering as an equivalent workload.
- Resolve or independently triage existing lint errors and full-fixture failures; the passing remote-only checks do not replace them.
- Measure actual input-to-visible-focus latency and first exposure of lower rows with a scheduler/frame trace.
- Repeat with controlled cold/warm conditions, realistic larger catalogues, long rapid-input sequences and memory/thermal soak.
- Reintroduce hero/artwork/progress features individually and measure their cost; the complete original UI has not been shown to reach SmartTube performance.
- Resolve in-app accessibility exposure and validate real TalkBack separately from remote-only focus.

The measured native trial is much closer to the SmartTube reference on this Fire TV. It remains a constrained comparison path with outstanding correctness and production validation, not a promise of zero stutter.
