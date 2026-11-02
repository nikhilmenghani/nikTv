# Phase 11 deadline investigation

2026-11-01. Baseline HEAD 07d9f938. Phase 12 remains paused. Diagnostic changes are uncommitted. Original Home and Fire TV were not changed or accessed. Only tablet 6889f3db (Xiaomi Pad 5), Wio, Dev was used. No personal cache/data clearing or media playback.

## Gradual isolation

1. Start with Phase 10: backdrop fade, text hero title, no hero metadata preparation.
2. Add metadata preparation/publication only, with logo rendering disabled.
3. Add full Phase 11 title logos. Same APK, controls before and after the candidate; same Dexter hero, same Next action then Previous reset. Eight individual Next windows per variant. Two Down x3/Up x3 scrolling runs per block. No touch flings or recording during measured windows.
4. Separate worker APK: keep full Phase 11 and add an optional worker-dispatched preparation variant. Compare full/worker/worker/full, eight individual Next windows each. Phase 10 was also checked on this APK but its one block is not pooled with the earlier APK.

Single-action captures use 500 ms input spacing plus 200 ms tail rather than two-action long windows. The raw frame rings remain incomplete even in these short captures; global counters are authoritative, not full-run function attribution. Legacy helper origin labels refer to a first row, but screenshots establish the actual hero/first-row starting viewport. Inputs and reset actions are retained in scripts.

## Results: metadata vs rendering (first APK)

| Variant | Median of eight run p95 values | Deadline misses / rendered frames |
|---|---:|---:|
| Phase 10 | 11 ms | 14 / 808 (1.73%) |
| Metadata without logo | 15.5 ms | 23 / 785 (2.93%) |
| Full Phase 11 | 11.5 ms | 20 / 797 (2.51%) |

Adding logo rendering was not consistently worse than metadata-only. Extra misses occurred without any logo rendered. This narrows suspicion toward preparation/publication or scheduling rather than the logo alone; it does not establish causal attribution. Metadata-only p95 changed substantially between passes. Small samples, sequential execution, retained caches and background/device variation still matter.

Steady-title scrolling medians were Phase 10 17.5 ms (16/734 misses), metadata-only 15.5 ms (12/743), full 17 ms (11/725). There is no repeatable steady scrolling regression in this comparison. Earlier touch results were not matched closely enough in viewport/fling/content to assign their difference to logos.

## Worker experiment (second APK)

| Variant | Median of eight run p95 values | Deadline misses / frames |
|---|---:|---:|
| Existing full Phase 11 | 12.5 ms | 23 / 803 (2.86%) |
| Worker preparation plus logo | 13.5 ms | 20 / 794 (2.52%) |

This is mixed evidence, not a measured fix. One fewer ms or a few missed frames cannot be inferred from moving work off Main. No production behavior was switched to the worker variant.

Code inspection did identify that fresh detail response-to-model mapping can run in the ViewModel's Main coroutine, although disk read/decode is already on IO. The optional worker variant moves repository preparation to Default; cached callbacks, generation checks and publication stay on Main. Existing Home calls retain the original default path. A new regression verifies the optional repository call executes off the calling thread. Cold network/model-mapping cost was not isolated: these tests preserve public caches, so the worker result does not rule out cold-path benefits.

## What is known and what is not

- The original deadline increase is not sufficient evidence that logo drawing is the cause. Phase 10 also misses deadlines, and warm steady scrolling did not get worse consistently.
- Metadata-only still updates heroPresentation in the screen-level state. The parent currently observes that state even when the logo is disabled. This can cause screen-level recomposition and is a specific next isolation candidate, not a proven root cause.
- Retained frame tails show small ordinary draw/layout spans and occasional start delays, but cannot assign global misses to Kotlin functions. Display reports 120 Hz; p95 pipeline latency is not input latency, FPS or a fixed deadline budget.
- A controlled metadata-publication-off comparison and recomposition/FrameTimeline trace are still needed to distinguish state publication work from scheduling. No blanket removal of logos or buffer/cache changes is justified yet.
- No cold-cache, portrait, Nikhil profile, long thermal/memory or Fire TV diagnosis is claimed. No SmartTube parity claim.

## Delivery and verification

Installed `worker-dev.apk` on tablet. Left Home 2 on Phase 10 baseline. Metadata-only and worker modes remain selectable under Performance experiment. Original Home, Movies, Series and playback behavior were not migrated. Phase 12 is still paused. No commit/push.

202 targeted unit tests passed, zero failures/errors/skips. Normal Dev APK build passed. Lint failed with 22 errors, 347 warnings and 3 hints; all 22 error source lines exist in HEAD (lint-audit.json). No suppression. Source credential scan zero findings and whitespace check passed.

Commands (JAVA_HOME JDK 17):

```text
gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' -PskipLocalGithubToken=true --console=plain
gradlew.bat :app:lintDebug -PskipLocalGithubToken=true --console=plain
adb -s 6889f3db install -r D:/workspace/niktv-playback-tests/phase11-isolation/worker-dev.apk
python D:/workspace/niktv-playback-tests/phase11-isolation/isolate.py
python D:/workspace/niktv-playback-tests/phase11-isolation/worker-compare.py
python tools/check-source-secrets.py
git diff --check
```

Evidence root: D:/workspace/niktv-playback-tests/phase11-isolation/. First APK diagnostic-dev.apk with build.log; second worker-dev.apk with worker-build.log and worker-lint.log. summary.json contains aggregate values. Raw per-run gfx/JSON, timestamps, screenshots and scripts remain outside the repo.


## Default-off metadata switch

Added a persistent Home 2 Performance experiment switch, "Hero title artwork and metadata", default false. It gates both preparation (including metadata-only and worker diagnostics) and logo presentation. Disabling cancels/invalidate in-flight preparation through the existing generation path; already-cached presentation is not shown. Phase 11/diagnostic stages must be selected to use this optional feature. Original Home and explicit Info/detail requests remain unchanged. Public discovery feed/title/backdrop loading is not disabled.

Installed switch-dev.apk on tablet. Switch-off tests on Stage 11: scrolling p95 24/15/16 ms (median 16; 13 deadline misses/550 frames), single Next p95 11/15/13/13 ms (median 13; 9/391 missed). First-exposure spike remains; these are not proof that metadata is the sole source of stalls or a speed improvement against another APK. Caches retained, no recording. Scripts switch-check.py, switch-off-* metrics and screenshots in the same evidence root.

Skipping preparation avoids its optional network/cache-read/decode/state-publication work. Caching helps when enabled by avoiding repeated HTTP requests, but still requires reads/model decoding and publication; it does not make an unused lookup free. Opening Info still fetches needed details normally. Current UI default and delivery state are off.

Build and 202 targeted tests passed (switch-build.log). Applicable lint rerun in switch-lint.log; report its result separately rather than treating the successful build as a lint pass. Secret scan and whitespace passed. No commit or push.

Switch validation: UI check confirmed default unchecked, enabling checked and displayed logo, disabling restored text title; delivered off. Final switch lint failed with 22 errors, 348 warnings and 3 hints. No lint pass claimed.
