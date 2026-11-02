# Recycled discovery rows — 2026-11-01

Status: **Home 2 only**. Phases 1–7 are committed as `0f763045`; Phase 8 is experimental and uncommitted. The user requested that familiar Home remain unchanged until migration is ready. The global recycled-renderer preference and its original-tab controls have been removed. Original Home, Movies and Series always choose the original presenter, even if a previous APK saved the old opt-in preference. Shared navigation remains unchanged.

Fire TV testing remains stopped. A host-side STOP guard prevents the existing input driver from running. Do not resume interaction or install the rollback there without the user's instruction. The installed Fire TV APK remains the previous candidate until a later authorized installation; changing source code does not alter the running app. No skeleton UI was added; the user explicitly deferred it.

## Current incremental Home 2 rebuild

Home 2 starts with Stage 1. Its Options → Performance experiment menu exposes these cumulative stages. Existing original-renderer presets remain separately labelled comparisons; none can change the normal Home route.

| Stage | Single addition relative to preceding stage |
| --- | --- |
| 1 — baseline | Recycled rows, four rows × eight cards, cached posters, title/subtitle and immediate focus border; static background; no source prefix, progress rendering or focus animation |
| 2 — more cards | Increase preview cards from eight to 30; retain four rows |
| 3 — more rows | Include the full selected feed; retain existing 30-card preview limit and Search / All |
| 4 — source labels | Add IPTV/alternate/TMDB source text |
| 5 — watch progress | Render the existing watch progress values |
| 6 — focus animation | Add the small focus enlargement animation |
| 7A — contained captions | Put title/subtitle over artwork with a readability gradient; no ranking numbers |
| 7B — borders and shadows | Add the border finish and restrained focused-card elevation |
| 8 — hero layout/actions | Scrollable hero text and existing actions; artwork disabled. Final tablet median p95 +~1 ms versus 7B; first-pass cost still needs investigation |

Tests require exactly one configuration difference between successive stages and require every experimental selection to resolve to CONTROL outside Home 2. Trial metadata preferences and route state remain separate from original Home. Playing, details, long-press actions and full collection callbacks retain the existing implementation.

For each addition, freeze the APK, profile, content, geometry and input cadence. Collect first-exposure and repeat results separately; retain p95, maximum/outliers, deadline misses, image readiness and memory. Compare to the preceding stage and to the frozen baseline, not to the best result from unrelated runs. Stage 5 measures progress **rendering**: existing progress-data lookup remains shared across stages. Stage 4 is source text, not the original decorative badge overlay. A slow stage should be traced and revised or rejected before migration.

These stages share underlying models and existing loading infrastructure. Rendering comparisons alone cannot assign the full cost of shared metadata work to a feature; trace that work separately and distinguish deterministic cached-fixture results from real-feed loading. Establish a fresh Stage 1 baseline rather than reusing the previous bounded-native measurements.

Phase 7A/B are implemented and measured on tablet: see [Phase 7 findings](home2-phase7-performance.md). Ranking numbers are excluded by user request. Remaining features after the Phase 8 hero layout: investigate poster density/aspect separately, then static hero artwork, crossfade and focus-driven backgrounds. No skeleton implementation or production-tab replacement is included. Fire TV performance measurements remain paused.

Phase 8 now adds hero layout/actions within Home 2: [measured costs and verification](home2-phase8-performance.md). Static hero artwork is Phase 9; dynamic effects remain later. Per-phase figures are local comparisons on their respective APKs and must not be summed as a precise cumulative budget.

Isolation verification (`home2-isolation-checks.log`): **199 targeted unit tests passed**, including the route guard and single-change-per-stage assertions. Two Xiaomi Pad 5 tests passed in **7.623 s** (`home2-isolation-tablet-tests.log`): the fixture enabled the legacy global preference, used the Home 2 baseline and complete detail data, then verified original Home has no experimental settings; the second test covered the 3,200-record recycled feed, header/detail returns and rapid-reversal full visibility. Both ran in the isolated regression package with fake data and no provider or playback/audio calls. No new performance timing claim is made.

Normal Dev APKs assembled successfully (`home2-isolation-dev-build-lint.log`). The combined command then failed at lint with the same **22 baseline errors**; all error source lines were rechecked against HEAD (`home2-isolation-lint-audit.json`). The APK was inspected for the new stage labels and absence of the removed original-tab toggle. Both ABI artifacts are ready outside the repository as `home-restored-home2-staged-tv-dev.apk` and `home-restored-home2-staged-tablet-dev.apk`. Neither was installed on Fire TV. Tablet fixtures were removed afterward; regular tablet NikTV and its data were untouched. Secret scan and whitespace checks passed. No commit or push.

## Previous rollout attempt (superseded; retained for evidence)

The earlier candidate applied a reversible TV/tablet presenter to Home, Movies and Series using a vertical RecyclerView and horizontal recycled rails. That original-tab integration has now been removed. Its full-feed presenter is retained inside Home 2, keeping the existing preview limit of 30 media cards per ordinary rail and complete collections through Search / All. It does not download an entire catalogue into every rail.

The earlier **Existing tabs** preference is no longer read and its toggle is removed. The tested Fire TV still runs the previous APK while the user watches; restoring its UI requires the later authorized Dev installation. Phones continue to use their original layouts.

The experimental presenter deliberately omits the hero, dynamic backdrop and decorative poster overlays. It keeps artwork, reserved two-line titles, source text, progress bars, click/long-press actions, recommendation dismissal, category configuration, full collections and existing playback/detail callbacks. The playback engines are unchanged. Reintroducing omitted presentation features needs separate measurements.

Rendering changes:

- Background list diffs compare visual fields without allocating comparison lists or treating callback object changes as visual changes. Actions resolve against the newest row model.
- Stable row/item identities, a small shared card pool, four cached vertical neighbors and one cached horizontal neighbor keep the attached view count independent of catalogue size.
- Image work uses the shared Coil loader, two concurrent requests and cancellation on recycle/release. Progress-only updates retain their image.
- Idle work prepares at most 16 empty card holders. Detached focus animations are cancelled so transient animation state does not block recycling.
- The latest follow-up prepares at most three empty row holders when idle and sets nested initial prefetch to nine cards, using RecyclerView's deadline-aware prefetch. These holders do not request media streams or artwork until bound. Its performance effect has **not** been measured on Fire TV.
- Focus moves directly to attached targets; otherwise a native anchor retains focus until the requested recycled holder exists. Request epochs reject obsolete deferred moves. Deferred lookup re-resolves the row identity after asynchronous list updates.
- Native focus bookkeeping does not publish Compose state on each key. The visible-row demand signal waits 400 ms and cancels obsolete notifications.
- Provider metadata binding follows a six-logical-category window. Up to 100 nonempty row previews are retained for revisits; the complete selected category list remains available for navigation and full collection searches. A logical category can expand into multiple provider source categories; six is not a hard source-request count. Existing provider request serialization and disk-cache bounds remain.

No cache/account data was cleared. Normal browsing can warm caches. No release APK was installed or changed.

## Physical Fire TV measurements

All measurements below are **Dev**, not release. Inputs were three Down then three Up, 250 ms apart, without video recording or method tracing. Screenshots verified first-card identity before and after each listed group. These are frame-time p95 values, not FPS or pixel input-to-focus latency.

| Profile / actual destination | First measured pass | Repeat | Deadline misses / frames, first → repeat |
| --- | ---: | ---: | --- |
| wio Home | 48 ms | 26 ms, then 21 ms | 7/84 → 3/87 → 1/96 |
| wio Movies | 48 ms | 23 ms | 4/90 → 3/90 |
| wio Series | 40 ms | 9 ms | 3/89 → 1/96 |
| Nikhil Home | 65 ms | 21 ms | 10/76 → 2/94 |
| Nikhil Movies | 69 ms | 22 ms | 6/83 → 1/91 |
| Nikhil Series | Not measured | Not measured | User stopped TV testing |

First measured pass does not mean a controlled cold disk/image cache. No caches were cleared. Nikhil's provider rails included missing artwork, so they are not equivalent to fully image-loaded content. Some provider preview content refreshed during the wio Movies checks. Equal key counts returned to the same card, but the vertical pixel offset changed to reveal the focused card.

wio's listed runs used `recycled-delivery-dev.apk`. Nikhil used `recycled-diff-fix-dev.apk`, which additionally fixes two new lint findings in comparison logic and re-resolves deferred row indices by identity. The subsequent bounded nested-row prefetch change is absent from both measured builds. Do not describe this matrix as measurements of the final prefetch source.

Earlier full original-screen p95 references were wio Home/Movies/Series 150/150/200 ms and Nikhil Home/Movies/Series 69/125/125 ms. The constrained four-row native trial measured approximately 9–12 ms. SmartTube's separate previous reference was 14–24 ms vertically and 12 ms horizontally. These are useful context, **not** matched identical-content A/B tests: geometry, features, source versions and loading conditions differ.

An intermediate full-feed recycled prototype reached 44/11/10 ms after idle card-pool warming. That result must not replace the slower actual-tab results above. Original-tab integration restored progress/actions and uses different row contents. Existing-tabs exploratory runs before the final entry-focus fix (`wio-existing-recycled-home-*`) have uncertain origins and are excluded from this table.

Individual 100–150 ms pipeline/UI outliers remain in some real-tab samples despite lower p95. Legacy janky-frame counters can also remain much higher than deadline misses. SmartTube-level first exposure and sustained smoothness are **not** established.

## Investigation and correctness

A separate sampled method trace, excluded from timed results, showed native key/reveal work, repeated card construction, Android style inflation, traversal/drawing and hardware-layer work. Approximately 29 ms inclusive main-thread CPU was attributed to card construction in that short trace. Inclusive spans overlap and cannot be summed; this is not proof that a single animator or network request causes all stalls. It motivated moving bounded holder construction to idle time and cancelling detached animations.

Real Fire TV checks verified first-tile entry on all three wio tabs and Nikhil Home/Movies, header transfer, direction changes, and opening a wio provider series detail page without Play. Back returned to the same fully visible series tile. These checks do not cover every popup, playback return, touch mode, accessibility service or full catalogue pagination path.

The deterministic native fixture has 80 rows × 40 cards (3,200 records). The pre-prefetch Fire TV run passed in 7.616 s, covering horizontal/vertical traversal, fewer than 100 tagged attached views, detail removal/return and header re-entry. This attached-view count is not a measurement of total allocations or retained heap. The strengthened fixture additionally checks full card visibility and rapid direction reversals; its latest result is recorded in the verification section.

After moving tests to Xiaomi Pad 5, the strengthened fixture initially **failed**: rapid reversal returned focus to `r0:i15`, but the card was clipped. The renderer now stops an obsolete vertical reveal and requests visibility even when the target card already owns focus. That corrects the case where `requestFocus()` succeeds without triggering a new reveal. The same tablet fixture then **passed in 3.898 s**, including full returned/reversed-card visibility. Fire TV has not received or validated this final correction while the user is watching.

## Verification and evidence

Repository preparation tasks were inspected: mpv C++ runtime extraction and Android dnsjava filtering generate build outputs; no integrated Flow/SmartTube source rewrite is needed for these builds.

Commands (PowerShell, JAVA_HOME set to the installed JDK 17):

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests '*DiscoveryScrollExperimentTest' --tests '*ProviderDiscoveryTest' -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
gradlew.bat :app:assembleDebug -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
gradlew.bat :app:lintDebug -PskipLocalGithubToken=true --console=plain
adb -s 6889f3db shell am instrument -w -r -e class com.nikhil.niktv.ui.RecycledDiscoveryDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
python tools/check-source-secrets.py
git diff --check
```

The exact discovery-package suite passed **197 tests**. The earlier broad `--tests '*Discovery*'` command selected an unrelated method named `VidSparkSourceResolverTest.publicClientDiscoveryReturnsExactTitleAutoAndMuxedQualities`: that command failed 1 of 180 tests (expected four requests, actual twelve). Both its test and resolver are unchanged in this work. The narrower command is a targeted pass, not a claim that the broad run passed.

Lint initially found two new `DiffUtilEquals` errors; both were fixed by field comparisons. The subsequent report still fails with **22 existing errors, 347 warnings and three hints**. No suppression or unrelated lint repair was added. Latest lint/device results are retained with the external evidence.

The 22 remaining error source lines were independently checked against HEAD `de0922b6`; all already exist there (`recycled-lint-audit.json`). The latest tablet target/test APK builds passed. `git diff --check` passed and the source credential scanner reported zero findings. No release build or release installation was performed.

Final-source verification (`recycled-final-verification.log`) assembled the normal Dev APKs and passed the same **197 targeted tests**. The combined command failed at lint with the same 22 baseline errors. Latest normal artifacts are `recycled-latest-dev.apk` (ARMv7) and `recycled-latest-tablet-dev.apk` (ARM64); neither was installed on Fire TV after the user stopped testing. The temporary tablet regression packages were removed after the passing test. Fire TV's temporary regression packages remain pending cleanup when interaction is authorized again.

Evidence is outside the repository at `D:/workspace/niktv-playback-tests/`: `recycled-*.log`, `recycled-method.trace`, `recycled-method-summary.txt`, APKs, and `firetv-home2-20261101/` JSON/input/gfxinfo/screenshot files. Avoid committing captures, provider data or credentials. No new implementation commit or push.

## Resume checklist

1. After explicit permission to resume Fire TV, remove only the host input-driver STOP guard. Do not wake, restart or install while the user is watching.
2. Test the latest bounded nested-row prefetch and rapid-reversal reveal correction on Fire TV, comparing against the recorded first-pass and repeat runs. Revert prefetch if it increases idle stalls or memory without benefit.
3. Complete Nikhil Series measurements and rerun all surfaces on one final APK, keeping missing-artwork and first-exposure results separate.
4. Test full collection Search / All, long-press action restoration, recommendation dismissal, category changes, actual movie/series playback returns, touch-to-D-pad re-entry and accessibility exposure.
5. Add longer memory/thermal runs and actual input-to-visible-focus measurements. Do not infer either from frame p95 or attached views.
6. Keep all experiments in Home 2. Do not migrate original Home, Movies or Series until the user approves a tested result. Skeleton loading remains deferred by the user.


## Phase 9 static hero artwork (2026-11-01)

Phase 8 committed as `3036f1f4`. Added a separately selectable static hero image to Home 2 only. Warm tablet p95 median 15 ms control versus 16 ms with artwork; small sample, not a proven regression. No crossfade, rotation or focus-driven background. See [Phase 9 evidence and limits](home2-phase9-performance.md). Original screens and Fire TV untouched.


## Phase 10 prepared artwork fade (2026-11-01)

Phase 9 committed as `00742171`. Added a separately selectable, identity-scoped 300 ms fade-in after hero image readiness in Home 2. Same-APK tablet paging median p95 stayed 11 ms; scrolling was 15 ms versus 17 ms repeated control (small samples, not a proven speed improvement). No old/new two-image crossfade, rotation or focus-driven backgrounds. See [Phase 10 measurements and limitations](home2-phase10-performance.md). Original screens and Fire TV untouched.


## Phase 11 hero title artwork (2026-11-01)

Phase 10 committed as `a6730b87`. Added optional cached title artwork only in Home 2, with text fallback, a 200 ms selection delay and existing metadata generation guards. Warm tablet D-pad scrolling median p95 stayed 15 ms; touch was 16 to 17 ms with more missed deadlines, so additional cost is not ruled out. 200 targeted tests passed; 22 existing lint errors remain. See [Phase 11 measurements and limitations](home2-phase11-performance.md). Original Home and Fire TV untouched.
