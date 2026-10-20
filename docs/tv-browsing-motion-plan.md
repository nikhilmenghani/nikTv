# NikTV TV browsing and motion — working plan

Status: **Isolated Home 2 / Movies 2 / Series 2 trial implemented; verification results and remaining limits recorded.**

Last updated: 2026-10-20

This document retains the original exploration notes below. The user accepted the implementation plan on 2026-10-19; the accepted decisions in this section supersede pending proposals in the original notes. Keep recording verification and future refinements here.

## Accepted implementation decisions

- Preserve the original Home, Movies and Series screens. Home 2 is the default after profile selection or switching (updated 2026-10-20); Movies 2 and Series 2 remain separate entries, sharing a compact top bar.
- Include new full movie and series detail pages, responsive phone/portrait layouts, touch gestures and D-pad navigation.
- Discovery includes released TMDB titles only (unknown/future dates excluded). IPTV lookup happens only after an explicit Play/episode/download action; no automatic match counts.
- Home 2 puts Continue Watching and New Episodes before daily rankings and personalized/configured discovery rows. Trial customization is profile/surface specific and independent of classic settings.
- Use bounded public metadata caching (six-hour feeds, 24-hour details, 10 MiB disk limit) and existing artwork cache/storage ownership. No Room.
- Hero rotation is eight seconds while visible and idle; focus, touch, scrolling, popups and lifecycle changes pause it.
- Focus uses contained borders and modest scaling; row scrolling cancels obsolete movement. Prepared backdrops crossfade with gradients, without animated blur or parallax.
- Reuse existing favorites, parental authorization, on-demand provider selection, playback engines and resume behavior. Do not change stream buffering.
- Baseline pending work was committed as `165d3460` after 16 focused Android tests, source-secret scanning and Tizen lifecycle checks passed. No push. Trial implementation remains uncommitted.

### Delivery and validation record

See [the complete trial verification record](discovery-trial-verification.md) for changed files, exact commands, device coverage, frame/memory measurements and remaining checks. Original destinations and default startup remain available. The implementation is uncommitted; only the baseline commit was created, with no push.

- 492 app unit tests passed, including 33 discovery tests. Final Fire TV and tablet fixtures passed 3/3 each. On reconnection Samsung also passed 3/3; two additional phone layout fixes are installed, with an enlarged-font follow-up passing 2/3 (carousel touch failed). A subsequent phone-specific header/Live TV revision passed 38 focused unit tests; the updated app is now installed on Samsung and Fire TV. Final 130% text checks were 2/3 portrait and 3/3 landscape, with intermittent portrait row-focus loss still unresolved (see verification record).
- Full lint still reports 25 pre-existing errors outside discovery; no suppression was added.
- Fire TV measurements caught a substantial initial rendering regression. Baking static backdrop gradients off-thread and removing unfocused poster shadows reduced warm scroll median from 44 ms to 9 ms (8 ms on the third cycle). See the verification record for comparison caveats and remaining long frames.
- Portrait, 130% text and reduced motion were checked on tablet; phone-width emulation was also captured. Temporary device settings were restored.

- Initial Kotlin compilation and debug APK build passed.
- Discovery cache/policy tests passed; expanded ViewModel tests cover late detail/feed/season responses, profile-specific configuration and supplementary-section failures.
- First tablet rendering confirmed hero artwork, compact rows, full details, runtime/genres/certification, related titles and cast from TMDB.
- Tablet Back testing exposed a lazy cast-row race during detail teardown. The row now captures an immutable cast list; the isolated device fixture includes populated cast to exercise that path.
- Final device results, lint/build commands and performance measurements are recorded in the verification document. No zero-stutter claim is made.

## Goal

Make browsing feel fluid and deliberate, using Brodys TV as a visual reference while retaining NikTV's theme, navigation and functionality. Improve how selected media information is presented without sacrificing compact browsing or responsiveness.

## Reference observed on the tablet

Brodys TV (`tv.brodys.free`) was operated on the Xiaomi Pad 5 using D-pad input. The observed screen was a movie detail page for Spider-Man: Brand New Day, followed by its collection, related movies, cast and reviews.

- A large movie backdrop sits behind the content, with dark gradients supporting readable text.
- The initial view presents a title/logo, actions, year, runtime, genres, certification, rating, quality and synopsis.
- Moving down shifts the content upward as a coordinated page, bringing the next section into view.
- The backdrop remains visible behind lower sections.
- Focus uses a clear border; the visual emphasis does not depend on aggressive bouncing.
- Lower sections include a wide collection banner, horizontal related-movie posters, cast portraits and review cards.
- The app also showed an idle screensaver with movie artwork, title, time/date and a prompt to continue. This is a separate possible feature, not part of scrolling itself.

These are visual observations, not a review of Brodys TV's source or a measured performance comparison. Exact easing, duration, rendering technique, caching and frame timing are not yet established. Parallax and crossfading should not be assumed from these observations.

## NikTV starting point

NikTV already has tile focus animations and animated row reveal. Recent local work keeps focus animation in drawing/layer updates, avoids unnecessary focus delays and adds compact TV Live TV tiles. Those changes should be preserved and evaluated alongside this proposal.

The proposed improvement adds coordinated page composition and section movement; it is not simply a larger tile zoom or a longer animation. See [existing navigation measurements](../tools/NAVIGATION_PERFORMANCE.md) for previous results and their limitations.

## Proposed work

### 1. Movie and series detail presentation

- [ ] Design a TV detail layout with a large backdrop and readable gradient overlays.
- [ ] Establish consistent positions for title, primary actions, metadata and synopsis.
- [ ] Present only available metadata; handle missing artwork, long names and descriptions gracefully.
- [ ] Organize relevant lower sections, such as episodes, collections, related media and cast, using existing data where possible.
- [ ] Decide how much information stays visible as focus moves into lower sections.
- [ ] Keep NikTV's existing button styling, focus borders and established actions.

### 2. Coordinated scrolling and focus

- [ ] Scroll sections into a predictable position with a smooth start and stop.
- [ ] Keep focus and scrolling coordinated through repeated Down/Up input and direction reversals.
- [ ] Allow new input to interrupt or retarget movement; avoid building a queue of animations.
- [ ] Preserve focus identity when data loads, pages append or metadata changes.
- [ ] Prevent border clipping, unwanted layout shifts and focus jumping to Profile.
- [ ] Preserve Back behavior and restore the originating tile and scroll position.
- [ ] Choose motion duration and easing through device testing rather than copying unmeasured values.

### 3. Home and category browsing

- [ ] Explore the same section transition for Home's horizontal media rows.
- [ ] Decide whether selecting a movie/series tile should update a shared information area, and how much screen space it may use.
- [ ] Keep large Live TV/category collections compact and easy to scan; do not automatically apply the full movie-detail layout to them.
- [ ] Preserve search, filters, favorites, pinning, long-press menus and automatic pagination.
- [ ] Preserve live programme title, timing and progress information where available.

### 4. Artwork and performance

- [ ] Size decoded artwork for the display area and bound image cache/prefetch work.
- [ ] Keep existing artwork visible while replacement artwork loads; avoid empty flashes.
- [ ] Consider crossfades only where they improve the experience without adding frame drops.
- [ ] Keep provider/network work independent of focus and animation frames.
- [ ] Reuse cached metadata; do not introduce a provider lookup for every focus movement.
- [ ] Avoid changing playback, stream buffering or provider connection behavior for this UI work.
- [ ] Support reduced motion and readable larger text without clipping.

### 5. Optional: idle screensaver

- [ ] Decide whether NikTV needs a movie-artwork screensaver with time/date.
- [ ] If included, define idle timeout, artwork source and dismissal behavior.
- [ ] Ensure it never covers active playback, opens media on dismissal or loses browsing position.

## Open decisions

| Question | Current proposal | Decision |
| --- | --- | --- |
| First implementation scope | TV movie/series details, then evaluate Home rows | Pending |
| Tablet behavior | Support touch and D-pad; decide whether TV presentation is an option or automatic | Pending |
| Mobile behavior | Keep a layout suited to the smaller screen; do not copy the TV layout wholesale | Pending |
| Information on focused browse tiles | Consider a shared preview area; compare density and readability | Pending |
| Scroll alignment | Use consistent section placement, with exceptions near page boundaries | Pending |
| Artwork movement | Persistent backdrop first; evaluate crossfade/parallax separately | Pending |
| Additional metadata | Prefer existing data; agree on sources and caching before new fetching | Pending |
| Screensaver | Separate optional feature | Pending |
| Motion settings | Decide whether reduced-motion/system preferences are enough or a user setting is useful | Pending |

## Validation before accepting the design

- [ ] Capture comparable before/after recordings on Fire TV and tablet using the same content and navigation sequence.
- [ ] Measure frame timing, missed deadlines, input-to-focus delay and memory under warm and cold artwork conditions. Record build mode and device refresh rate.
- [ ] Test single presses, held/repeated D-pad input, rapid reversals, Left/Right movement and Back restoration.
- [ ] Test loading, pagination, unavailable artwork/metadata, long titles, large fonts and constrained network conditions.
- [ ] Test touch scrolling on tablet and ensure mobile remains usable.
- [ ] Confirm focus effects do not resize neighboring items or obscure text.
- [ ] Confirm normal playback entry, player exit, parental checks and existing tile actions still work.
- [ ] Run focused regression tests, applicable lint and a build; document blocked checks honestly.

Do not claim zero stutter or a performance improvement from appearance alone. Set measurable acceptance targets after collecting a baseline.

## Suggested implementation sequence — pending scope agreement

1. Finalize the target screens and sketch the information hierarchy.
2. Establish recordings and performance baselines on the target devices.
3. Prototype one movie detail page with existing data and artwork.
4. Refine scroll/focus coordination and test rapid input.
5. Extend the accepted pattern to series and, if approved, Home rows.
6. Validate behavior, performance and device layouts before broader rollout.

## Decision log

| Date | Decision / note |
| --- | --- |
| 2026-10-19 | Created this living draft after observing Brodys TV on the connected tablet. User requested a Markdown note to refine before finalizing implementation scope. No redesign implementation is authorized by this document alone. |

### Phone navigation follow-up (2026-10-19)

Phone presentation is independent of tablet/TV navigation, using the device's smallest width (under 600 dp, excluding TV) so rotating a phone does not select the tablet header. A compact utility row contains Back, NikTV 2, Search and Browse. A separate full-width row evenly distributes Home, Movies, Series and Live TV with minimum 48 dp targets. Trial identity remains in accessibility labels; shorter visual labels avoid unnecessary crowding. Tablet/TV retain the original compact header and gain an explicit Live TV action. Both Live TV actions reuse the existing channel collection and playback routes; no Live TV collection redesign or provider lookup is added to discovery browsing.

Regression coverage checks all phone destination labels without horizontal scrolling, switches each trial surface by touch and verifies Live TV dispatch without starting a provider stream. Record final device results in `discovery-trial-verification.md`.

Samsung follow-up: phone destination visibility and touch switching were checked at 130% text in both orientations; Live TV dispatch was verified without starting a provider stream. Updated normal APKs are installed on Samsung and Fire TV. Final landscape fixtures passed 3/3; the portrait repeat passed 2/3 and exposed intermittent row-transition focus loss that must be investigated before claiming a clean regression run. Display settings were restored and temporary fixture packages removed.

Fire TV horizontal-scroll correction: a real Left/Right sequence displaced the current row vertically by 230 px. Discovery now uses the shared minimum-reveal focus viewport instead of the Android TV pivot; the explicit vertical spring is triggered on row entry rather than every tile focus. Horizontal edge scrolling remains enabled. A 20-input cross-edge/reversal regression checks that the vertical row anchor stays fixed.

### Vertical navigation and action-menu follow-up

Use a single cancellable owner for vertical reveal/alignment/focus, including rapid direction reversals. Do not start another alignment from the focus callback for that active destination. Track row entry by stable identity. Discovery long press uses the shared remote handler and opens after consuming release; its anchored popup reuses legacy actions and restores the originating item. Fire TV fixtures passed the four affected cases across separate final-source runs; 46 focused units passed. Normal APK assembly succeeded, but lint remains blocked by 25 pre-existing errors. Detailed results and limits are in `docs/discovery-trial-verification.md`; no new frame-performance claim is made.

Early-focus refinement: partially visible destinations receive focus once fully revealed, concurrently with row alignment rather than after spring completion. A Fire TV fixture asserted focus during active scrolling (191 ms in one run); all five device cases passed. Cancellation tests and both APK builds passed. See verification document for measurement limits.

Tablet observation: minimum-reveal relocation could still compete with explicit vertical alignment and produce a small reverse movement. During an active explicit transition, suppress implicit relocation only for the vertical list; restore the standard minimum-reveal policy inside every horizontal discovery row. Focus composed destinations immediately. Tablet motion fixture measured 12 ms and no rebound; warm actual-page samples were modestly better but do not establish zero stutter. A separate popup-action regression failed and remains explicitly recorded rather than counted as navigation success.

### IPTV adoption

User approved replacing the series detail presentation and adding IPTV movie details using the discovery design. Provider categories reached from the trial use the compact adaptive layout; Live TV/player UI remain outside this change. Enrichment is public cached TMDB metadata; selected provider identities, queue contexts and episode controls remain authoritative. See discovery-trial-verification.md for focused cache/privacy/return-focus results and explicit limitations. Installations for this step are limited to Samsung and tablet.

## Modern Home as profile landing — 2026-10-20

Home 2 is now the default after selecting or switching a profile, superseding the
earlier decision to retain the original startup Home. The profile chooser remains
the cold-launch entry. Explicit original Home navigation still opens the original
screen, and Movies 2 / Series 2 remain separate destinations. Both authenticated
and cached-session profile entry reset the discovery route to HOME and dismiss
destination overlays so a previous screen cannot hide the new landing page.
