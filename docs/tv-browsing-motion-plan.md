# NikTV TV browsing and motion — working plan

Status: **Draft for discussion; scope is not finalized.**

Last updated: 2026-10-19

Use this document to collect observations, proposals and decisions before implementing the redesign. Unchecked items are proposals, not approved requirements or completed work. Update the decision log as we refine the scope.

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
