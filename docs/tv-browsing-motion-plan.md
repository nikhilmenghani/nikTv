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

See [the complete trial verification record](discovery-trial-verification.md) for changed files, exact commands, device coverage, frame/memory measurements and remaining checks. Original destinations remain available; Home 2 is now the profile landing screen. Trial work was committed in `ad44e626`, followed by refinements in `514adde1`. The Samsung reference audit below is documentation only.

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

## Samsung phone reference audit — 2026-10-20

### Evidence and scope

Inspected Brodys TV (`tv.brodys.free`, settings version `v1.0.17 (18)`) on the
connected Samsung SM-S948W. The display reports 1440 × 3120 pixels at 600 dpi:
approximately 384 × 832 dp. This is a real phone layout, distinct from the earlier
tablet/TV reference. Measurements below come from screenshots and accessibility
bounds, not source constants. Font scale and font metrics can change apparent sizes.

Explored All, Movies and Series feeds, vertical/horizontal scrolling, Search with
and without results, My List, More/settings, two movie detail pages, series episodes,
cast/actor pages, and a channel entry. The Anime filter was visible; anime rows were
inspected in All. No claim is made that every setting or playback feature works.

Screenshots, UI hierarchies and a short motion clip are outside Git in
`D:/workspace/niktv-playback-tests/brodys-samsung-ui-2026-10-20/`.
Important evidence:

| Evidence | What it records |
| --- | --- |
| `01-current-home.png` | Original portrait hero, filters, Continue Watching, bottom navigation |
| `02-home-scrolled.png`, `03-home-rankings.png` | Progress card, channel cards, ranked and ordinary poster rows |
| `05-movies-selected.png`, `10-series-home.png` | Type-specific feeds and different hero artwork/logos |
| `11-series-details.png`, `12-series-episodes.png` | Detail actions, season chips, episode rows |
| `13-search-empty.png`, `15-search-results.png` | Search field, helper state, three-column result grid |
| `16-my-list.png` | Continue Watching plus saved count and empty-state instruction |
| `17-more.png`–`19-more-bottom.png` | Playback, subtitle, app and account settings |
| `27-channel-open.png`, `28-channel-return.png` | Channel loading artwork and return to Home |
| `29-discovery-lower.png`, `30-discovery-bottom.png` | Additional editorial and anime rows |
| `31-browse-motion.mp4`, `31-motion-contact-sheet.png` | Short touch-scroll reference; not a frame benchmark |
| `34-movie-full-details.png`–`39-actor-filmography.png` | Settled movie detail and actor biography/credit sections |
| `40-final-home.png` | Samsung left in the phone Home/All feed |

Early captures `06` and `08` include transition frames; they must not be treated as
the final page opacity or layout. Settled captures are the stronger design evidence.

### Navigation and page structure

- The root phone shell has four persistent bottom destinations: Home, Search,
  My List and More. Each includes an icon and caption; the selected destination has
  a broad lighter capsule with bright icon/text. Inactive controls are grey.
- The navigation bar floats above content, inset from the edges, with rounded ends,
  a thin bright border and a translucent/blurred-looking surface. Content colours
  remain visible behind it. The rendering technique and blur radius are unverified.
- Home has branding and a profile initial/avatar at the top, then a separate
  All / Movies / Series / Anime segmented selector. These are feed filters, not four
  more bottom destinations. A white selection capsule establishes
  the active filter; inactive text remains light grey.
- The header and type selector scroll away with the page. Bottom navigation stays
  in place during feed scrolling. Tapping the already-selected Home tab did not
  scroll the sampled feed back to the top.
- Selecting Movies removes the mixed Continue Watching/channel sections from the
  sampled top portion and shows movie discovery rows. Series shows a series hero,
  Continue Watching and a "Because you watched…" row when history exists.
- Detail and actor pages remove the bottom navigation and feed header. A circular,
  translucent Back control remains near the top-left while details scroll.
- Back from the sampled channel loading destination returned to the top of Home,
  rather than its previous scrolled channel position. The second movie/actor return
  also ended at the top of Home. NikTV should preserve its stronger return-state
  behavior rather than adopt this reset.

### Hero and artwork composition

- Phone Home is deliberately artwork-heavy: full-bleed portrait composition behind
  the header, with the subject centred and an increasingly dark gradient towards
  the actions and the black feed below. It is substantially taller than a landscape
  tablet/TV hero; the first content row is visible near the lower part of the screen.
- The stack is artwork → ranking label → large title artwork → compact metadata →
  action group. Examples use actual designed title logos, not a generic bold title
  pretending to be a logo. A text-title fallback is still necessary in NikTV.
- The home ranking label is small, uppercase and letter-spaced. Metadata uses muted
  white/grey text with dot separators: year and a few genres. Home does not duplicate
  the full synopsis or all detail metadata.
- Home actions are centred: circular Add, a wider white Play button with black
  icon/text, and circular Info. Secondary actions have thin borders and translucent
  dark fills. The primary action is obvious without covering the artwork with panels.
- Movie details use a similar tall image/logo header. The metadata adds runtime and
  a small outlined certification badge. Play becomes almost full width; Add,
  thumbs-up and thumbs-down sit below it. A full readable synopsis follows.
- Series details substitute season count and an episode-aware primary label such as
  Play S1:E1. A long genre line was truncated, even though the certification remained
  visible. NikTV should wrap or limit the metadata deliberately.
- The settled actor page has a darkened film backdrop, a large centred circular
  headshot, profession, actor name, birth/place information, a shortened biography,
  then Known For, Movies and TV Shows horizontal poster rows.
- There are no generic HD/4K stickers on the inspected phone discovery posters.
  Artwork quality must remain separate from verified provider playback quality.

### Different cards for different jobs

| Card type | Observed presentation | Useful NikTV adaptation |
| --- | --- | --- |
| Ordinary discovery | 2:3 poster, rounded corners, subtle edge, roughly three visible; usually no separate caption | Compact browsing, with accessible title semantics and a readable fallback when artwork lacks a title |
| Ranked discovery | Smaller 2:3 poster beside a large white rank aligned near its bottom; roughly two full entries and a third hinted | Keep rank distinct from the poster so it does not hide title artwork |
| Continue Watching | Wide 16:9 image, thin progress inside its bottom edge, bold title below, muted S/E and episode name below that | Retain NikTV red progress and consistent image bounds; use real resume metadata |
| Channel | Wide image, small white 24/7 chip at top-left, channel name beneath, "Now: …" beneath name | Separate channel identity from current programme; do not pretend the sample proves EPG freshness |
| Featured/New Releases | Wide image with title and year/genre caption below | Selectively use for editorial rows, rather than making every movie a wide card |
| Search result | Three-column 2:3 poster grid, evenly sized, no separate captions in this sample | Fast visual scan; retain title accessibility and readable fallback/long-press information |
| Cast | Circular portrait, name below, quieter role below name | Stable card sizes, bounded readable labels and actor navigation |
| Episode | 16:9 still at left with overlaid Play circle; number/title, runtime and about three lines of synopsis at right | Preserve NikTV download, watched, resume, sorting and long-press actions while improving composition |

Ordinary poster rows do not all need a permanent title/subtitle box. Brodys relies
heavily on titles embedded in poster art. NikTV has provider artwork without good
typography, long channel names, and missing images, so copying that choice blindly
would lose information. The existing new-UI compact captions remain a useful fallback.

### Approximate phone geometry

These are measurements from this display, not universal values to hard-code.

| Element | Observed size/spacing at ~384 dp width |
| --- | --- |
| Feed side inset | ~16 dp; details often ~20 dp |
| Ordinary horizontal poster | ~118 × 177 dp, 2:3 |
| Search poster | ~111 × 166 dp, three full columns |
| Poster gap | ~10 dp |
| Ranked poster image | ~104 × 156 dp, plus a separate rank gutter |
| Wide Continue Watching/channel image | ~236 × 133 dp, 16:9; next card visibly peeks in |
| Cast portrait | ~76 dp diameter |
| Home primary Play touch bounds | ~119 × 48 dp |
| Type selection touch bounds | 48 dp high in the sampled All selection |
| Bottom destination touch bounds | ~86 × 60 dp each, four evenly distributed |
| Settings row | ~56 dp tall, label left/value right |

Corners are noticeably rounded and borders restrained. Black page backgrounds,
bright headings, medium-weight titles and dimmer metadata create hierarchy without
many nested cards. Section headings are larger than tile metadata; spacing separates
sections while tightly repeated poster geometry keeps rows orderly.

### Feed inventory and gestures

Observed sections across the combined/type-specific samples include Continue Watching,
Channels, Top 10 Movies Today, Top 10 Shows Today, Trending This Week, New Releases,
Popular Movies, Popular Series, Popular Anime, Anime Airing This Season, Top Rated,
and history-based recommendations. Empty Continue Watching disappeared after the
user cleared app data; the feed then proceeded directly to Channels.

Vertical touch scrolling moves the page as one feed; horizontal row swipes move
only that rail. Partially visible next posters advertise horizontal scrolling.
The captured gesture sequence shows immediate direct manipulation and continued
settling after release. It does not establish exact easing, animation duration,
frame rate, image prefetch strategy, main-thread cost or comparative performance.
No exaggerated focused-tile zoom is needed for touch interaction.

A horizontal swipe across the sampled phone hero did not change the featured title.
No phone hero arrows or page count were visible. This does not prove the app has no
other carousel mode. NikTV's existing accessible carousel controls should be retained.

Loading uses reserved artwork-shaped areas and actor initials in early states;
settled actor/poster images fill those areas. Opening a channel showed a full-screen
portrait image, title artwork and spinner. It was exited without confirming actual
stream playback, controls, seeking or channel-guide behavior.

### Search, library and settings

- Search has a large page heading, a full-width dark outlined rounded field,
  descriptive placeholder, and an explicit Clear action after text entry. The empty
  state explains that users can search by movie/show title. Friends results appeared
  during typing without pressing a separate Search button. Network source, debounce,
  caching, page-loading policy and measured search latency were not established.
- Results are tightly spaced, equal 2:3 posters in three columns. The keyboard can
  be dismissed without losing the entered query/results. Root navigation remains
  visible. This is a different composition from horizontal discovery rows.
- My List contains Continue Watching, followed by Saved with a count. Its empty state
  explains the Add action. Add/like/dislike were observed as controls; they were not
  toggled during this inspection and persistence/sync was not verified.
- More starts with a large avatar, viewer name, sign-in status, optional cloud-save
  explanation and Switch Profile. Settings are grouped under small uppercase headings.
- Playback options visible: autoplay next episode; intro/recap behavior; preferred
  audio/subtitle languages; subtitle size, colour, background and position; a visual
  subtitle preview; volume leveling; and Trakt linking.
- App options visible: interface mode, status-bar visibility, version/update entry,
  community/help link. Account offers sign-in/create-account for cloud saves.
- These are a feature inventory, not authorization to add account integrations or
  change NikTV's playback engines. Their runtime behavior was not validated.

### What to adopt, and what to improve

1. Treat mobile as a dedicated composition. Keep the tablet/TV top navigation and
   D-pad behavior separate from a compact phone shell. A proposed NikTV mapping is
   four bottom actions (Home, Live TV, Search, My List), with Profile/More on the avatar
   and Movies/Series as Home feed filters. This preserves first-class Live TV access;
   it is a proposal to review, not an implemented navigation change.
2. Give the phone hero its own portrait artwork/crop policy, title-logo area and small
   metadata/action stack. Do not stretch the tablet hero or stretch 16:9 art vertically.
   Use a gradient-backed crop/fallback when portrait artwork is unavailable.
3. Use separate poster, ranked-poster, wide-resume, channel and episode components.
   Each needs a stable ratio, consistent text slots where needed, and loading states
   that do not resize the feed. Preserve NikTV's theme and red progress styling.
4. Keep full details as a focused page with Back, one primary action and progressive
   lower sections. Cast should lead to actor discovery. Season/episode identity must
   still use the selected provider series and existing playback/download logic.
5. Preserve NikTV's stronger functionality: parental checks, player/variant choices,
   downloads, mark watched, episode ordering, profile isolation, pinning, refresh,
   long-press actions, and return scroll/focus. A cleaner appearance must not remove them.
6. Reserve bottom-bar and status-bar/cutout insets. The reference bar overlaps lower
   content and the transparent status area can overlap scrolled labels. The reference
   also truncates some metadata and biographies. Avoid those defects rather than
   reproduce every pixel.
7. Keep motion local and cancellable: independent horizontal rails, one vertical
   feed scroll owner, stable keys and reserved image sizes. Prepare artwork off the
   critical path; retain the current image during a bounded crossfade. A translucent
   static treatment is a valid low-cost alternative if real-time blur is expensive.
8. Do not infer a cache/network architecture from quick visual loading. Retain bounded
   NikTV metadata/image caches and on-demand IPTV resolution. Measure cold/warm runs,
   frame deadlines and memory before selecting blur, animation or prefetch settings.

### Limitations and device state

The session was interrupted by a Samsung system dialog attributing a Brodys crash
to WebView. Interface-mode interaction/relaunch and user activity overlapped around
that time, so this audit does not establish the crash cause. No WebView updates were
uninstalled. The user independently cleared Brodys app data and reopened it; this
explains differences in watch-history content between early and later captures.

An unintended capture outside the target app was deleted. Later capture helpers
check the foreground package before and after capture. Temporary recording on the
phone was removed after copying it locally. Samsung was left on the portrait All/Home
feed with no playback running. Fire TV and tablet were not operated.

No NikTV application code, dependencies, NikTV preferences or OS settings were changed
for this audit. Brodys briefly relaunched in its landscape TV presentation after the
Interface interaction; the original phone presentation was confirmed after the user's
reset. Settings-option behavior, successful playback, remote-focus traversal, larger
fonts, rotation, network/cache internals and precise animation timings still need
separate validation. Collections/reviews observed in the earlier tablet reference
were not present in the two inspected phone movie pages. No APK build or app tests
are needed for this documentation-only update, and no commit/push was made.

## Phone implementation, first pass — 2026-10-20

The user authorized starting implementation from the Samsung reference and explicitly
requested an option to return to the original UI. This first pass implements the
phone Home/discovery shell and shared portrait detail header; it does not claim every
Brodys feature has been ported.

- Phones are identified by smallest width below 600 dp and exclusion of TV devices.
  Portrait uses the new composition; tablet/TV headers, rails and focus coordinator
  remain on their existing paths. Short landscape phone screens keep a compact header.
- Home has a scrolling logo/profile-options header and All/Movies/Series selector,
  portrait-cropped backdrop, separate title logo/text fallback, compact wrapped metadata,
  Add/Play/Info, and accessible previous/count/next controls. Hero swipes are restricted
  to artwork, use a density-aware threshold, and do not share Play/Add hit targets.
- Source artwork retains its ratio. Device inspection caught duplicated title lettering
  when using a poster behind a title logo; backdrop artwork is now preferred, with
  poster fallback. Static vertical gradients are baked off the main thread. No live blur
  or parallax was added.
- Public, cached TMDB hero metadata supplies logos/genres without IPTV matching.
  Generation, profile, surface and current-hero checks reject stale work. Feed refresh
  can retarget metadata without waiting for canceled obsolete requests. Existing two
  request concurrency, image pressure checks and discovery cache bounds remain in use.
- Ordinary/ranked posters are compact; Continue Watching/New Episodes use wide 16:9
  artwork and separate readable captions. Red resume progress stays inside the image.
  Caption slots reserve equal heights scaled for fonts, including when text is absent.
  Existing tile actions, watched/download paths and explicit IPTV lookup remain intact.
- The phone shell keeps Home, Live TV, Search and My List visible at root destinations.
  Profile/settings/customization and original destinations remain in the header menu.
  Library retains Downloads and Storage. Details hide the bottom bar; content reserves
  space for the bar and system insets. Phone touch entry does not request remote focus
  and pull the restored feed back towards the header.
- Bottom Search is explicitly global; original search buttons retain catalog/category
  scope. Phone movie/series results, including their tabs in global results, use three
  poster columns. Live results and existing result actions/pagination remain intact.
- "Use original UI" is available in the new Browse menu. Settings → Device & display →
  Modern discovery UI enables/disables the experience. The independent device preference
  defaults on and controls profile startup as well as the current screen. Turning it
  off never clears profiles, playback state, caches or separate trial customization.

Samsung inspection found and prompted fixes for poster/title duplication, uneven wide
card caption heights, a too-small visible logo within the existing padded asset, and
the bottom Search entry inheriting Live TV scope. App data was retained. Verification
logs and screenshots are under
`D:/workspace/niktv-playback-tests/niktv-phone-ui-2026-10-20/` and adjacent
`phone-discovery-*.log` files. Exact final checks/device results are recorded separately
after the final-source build.

Further reference work remains: a full actor biography/filmography page, the phone
episode-row composition, and broader Library/settings visual redesign. Existing actor
results, series episode controls and settings remain functional through their current
components. Playback engines, Live TV collection design, account integrations and
provider scheduling are outside this first phone presentation pass.


### Series details usability pass — 2026-10-20

Implemented the Samsung audit findings: safe system-bar spacing; a shorter phone hero and labelled My List action; an expandable synopsis; episodes before cast/recommendations; sticky season/sort/jump controls; boundary-triggered pagination with a manual fallback; accurate loaded counts; corrected profile-scoped progress identity; smaller repeated thumbnails; equal-height cast cards; and related poster captions below artwork. Recommendations use original language and shared genres from cached TMDB metadata, omitting unsuitable results without background provider searches.

Phone controls use a compact season dropdown while tablet/TV retain season chips and wider episode artwork. Full details, playback/return callbacks, episode context actions and downloads are preserved. Samsung received the final build; 37 focused tests and assembleDebug passed. Samsung touch/remote entry and progress were checked on the first iteration; final compactness, tablet/TV devices and performance metrics still need visual/device validation. See discovery-trial-verification.md for commands and limits.


### Series details follow-up — 2026-10-20

User feedback refined the controls: remove Latest and Last watched shortcuts; keep Resume and My List on one line; wrap Search/Cast/Related instead of horizontally clipping controls; always expose the phone season picker. Remaining time belongs in a metadata badge beside the episode air date, not over its artwork. Keep the highlighted resume row and the thumbnail progress bar. Top actions should sit on the same artwork with a dark top fade as well as a bottom fade.

Found a functional season-index defect: subsequent portal pages contain only their own season, but both screen pagination and player-queue pagination replaced the full index with that partial response. Merge known seasons and preserve an explicit validated-index marker; repair affected legacy caches in the background without clearing episodes. Explicit Refresh seasons remains available in the picker.

Upload/add time is separate from air date. Retain optional explicit provider timestamps (Unix seconds/milliseconds or timestamps with an offset) in episode metadata and display them in the device's local date/time format. Do not infer a clock time from a date-only air date, duration, generic timestamp field or timezone-ambiguous string. Existing caches can omit this optional field.


### Inline cast and aligned episode metadata — 2026-10-20

Cast on the provider series screen now toggles an inline row immediately below the toolbar rather than jumping to the end of the episode list. Reveal/collapse uses top-aligned size and opacity transitions (180/140 ms, disabled for reduced motion). Focus remains on Cast after toggling; D-pad Down enters the row, Up returns to Cast, and Down from the row enters the first episode. Existing actor pages and focus restoration remain attached to their source cards. Movies retain their existing always-visible cast section.

The lazy list keeps an inline-cast slot even when collapsed: hero=0, toolbar=1, inline cast=2, first episode=3. Paging/return-focus offsets now share these constants. Related stays after episodes.

Episode identifiers, air dates and remaining-time statuses now use equal-height badges in fixed columns, with an empty status slot when missing. Phone artwork has a consistent 80dp width so text columns line up between unique and fallback thumbnails. Larger text uses consistent two-line metadata cells. Upload time remains a separate labelled value when supplied. Season selector and sort share one row; count is below both, removing their previous vertical mismatch.


Related now follows the same inline toggle as Cast: both start hidden, open immediately below the controls, and close when their active button is pressed again. One panel is shown at a time; changing buttons replaces the panel with a short fade and animated height, respecting reduced motion. The permanent inline slot remains index 2, so episode indices and paging boundaries are unchanged by opening, switching or closing panels. There is no duplicate Cast/Related section after the episodes. D-pad Down enters the selected panel, Up returns to its own button, and Down from either row enters episodes. Movie detail sections keep their prior layout.


### Related availability and cross-language genres — 2026-10-20

Keep the provider-series Related button visible even when its results are empty. Its inline panel distinguishes loading, request failure and “No relevant recommendations available.” The empty panel is a D-pad target with Up returning to Related and Down entering episodes. Cross-language titles, including English, are now allowed when genres match; original language is a ranking preference rather than an exclusion. Known genres must overlap; unknown source genres retain TMDB's recommendation ordering. Existing released/adult/source/duplicate filters remain.

A read-only inspection of Samsung's public TMDB cache found 20 released recommendations for Taarak Mehta, all sharing at least one source genre. No IPTV request was needed to validate their eligibility under the revised rule.


### Inline episode Search and full-width phone detail artwork — 2026-10-20

Search now uses the same inline panel as Cast and Related. Search button/Back closes it, D-pad Down enters the field, and TV text editing still requires Select. Tapping the field on touch devices opens the native keyboard. IME Back also closes the inline panel; an explicit keyboard Search action dismisses the keyboard while retaining filtered results. Closing or switching away from Search restores the full list while retaining typed text for reopening. Counts and pagination use only the active filter. The former floating search overlay is removed and the shared inline slot keeps episode indices stable.

Wide backdrops on compact phone provider detail headers now fit the container width, aligned top-centre, rather than being enlarged to fill its height. This avoids cropping away the sides of group artwork without stretching it. Portrait-poster fallbacks, discovery heroes and tablet/TV artwork sizing retain their prior behavior.

### 2026-10-20 — selected IPTV discovery collections and phone captions

- Modern Home/Movies/Series can show combined **Your IPTV Movies / Series** poster rows from explicitly selected provider categories. Existing individual category shortcuts and the original UI remain available. The modern customization menu and category footer expose the existing category chooser; the row's Search / All action opens a responsive combined collection.
- Reuse the current profile/type-specific browse and full-search metadata caches before requesting the provider. Retain available provider artwork/commands when the newer search index contains only title metadata. Keep this private data in the existing clearable catalogue cache, separate from public TMDB discovery data; no Room.
- Store page position, completion and freshness per category. Use the configured catalogue refresh interval, display cached content immediately, preserve it on a transient request failure, and reject canceled or obsolete profile/category results. Refresh invalidates the provider client's category response as well as the collection freshness.
- Provider work is visibility/demand driven: at most two category-page requests per batch, one network request at a time across movie and series rows. Rows preview 30 titles; the collection loads more near the end. Typing performs debounced local keyword matching on Dispatchers.Default and never initiates provider search. When the cache is incomplete, show that more titles are available and retain an explicit Load more / Retry action, including during a search.
- Bound retained metadata to 12,000 records per type and report the limit in the collection rather than silently claiming all titles were searched. Users can narrow selected categories; individual category browsing is unchanged. No speculative media connections, decoders, IPTV-to-TMDB matching, or history updates are introduced.
- Keep provider identity and existing parental/detail/playback routing. Preserve profile-scoped My List, long-press actions, local queue membership, series opening, and recent-progress display. Collection query, selection and scroll position are saved independently; tablet/TV use six/seven columns and phones use three.
- Phone TMDB/IPTV discovery captions now sit within a shaded tile, inspired by My List. Preserve the existing overall width/height (including former external caption space), two title lines, smaller subtitle, match badge and red progress bar. Placeholders reserve the same footprint. Tablet/TV discovery poster styling remains unchanged.
- Follow-up validation also covers memory release when leaving the collection route. Nested TMDB detail screens bind an independent provider collection state, preventing their surface from changing the underlying Home/Movies/Series collection. Long-press Play and movie Download use the existing profile/parental/provider action paths rather than treating Play as another Info action.

### 2026-10-20 — compact glass navigation from the tablet screenshot

- Increase the visible NikTV mark and place it before Back as the leftmost menu item. Use actual measured action labels (including font scaling) to size the menu panel to its buttons, with a horizontally scrolling middle section when constrained. Unused width is outside the menu panel, while the profile panel stays right aligned.
- Both menu and clock/profile panels share the same explicit 56dp minimum height, adapting upward for larger fonts. Increase the profile avatar to 40dp; preserve the existing active-tab entry/Down-to-Play policy, tab scrolling, Back action and focus outlines.
- Apply translucent dark glass, restrained highlights and lighter button fills. Behind both panels sample the same screen-aligned backdrop, softened by a cached, off-main three-pass blur of a 96px-wide image. This works without new libraries or Android 12-only live blur, keeps text/focus crisp, and avoids continuous full-screen effects. Missing artwork or resource pressure uses the translucent treatment without the image.
- Keep the distinct portrait-phone header/navigation. The screenshot-driven menu changes target the shared tablet/TV landscape header.
- Tablet validation found that estimating widths from text omitted actual button touch/layout bounds. The final menu instead wraps a self-measuring Row with a non-filling, horizontally scrolling middle section; all labels fit in the captured tablet layout. Menu/profile heights and glass appearance were visually checked, plus a short D-pad horizontal smoke check. Final build installed on tablet and Samsung; Fire TV remains unchanged.
### 2026-10-20 — IPTV category rails and scrolling tablet/TV navigation

Movies and Series now show each selected IPTV category as its own poster rail, alongside the TMDB rails. Default visible categories are honored when no explicit selection is saved; the implicit All category is omitted when real categories exist. Home retains explicit category selection. Each rail opens its own cached, searchable collection rather than a combined category-chip footer.

The tablet/TV glass menu is a regular first item in the vertical feed. It scrolls with the page; D-pad Down enters the hero, Up from hero restores the active menu tab. Row indices include this header consistently for movie and series/detail layouts. Phone navigation remains separate.

Category previews are visibility-driven, limited to 30 records per category and two provider requests per batch, with one active request per preview ViewModel. Separate preview/full cache keys prevent clipped previews from overwriting complete paged collections. Total retained metadata is bounded to 12,000 records per type/cache. Existing cache storage reporting and clearing apply. Shared catalogue requests reuse the main session/retry path; title selection cancels pending discovery metadata so it does not hold the provider request gate ahead of playback.

Tablet Wio smoke tests confirmed actual movie and series poster rows plus menu-to-hero/return navigation. This is not a frame-time benchmark or complete Fire TV remote regression pass. See discovery-trial-verification.md for exact checks and limitations.

### 2026-10-20 — direct IPTV category customization

Add a Categories action beside the new navigation's Browse menu (icon on phones). Movies/Series open their own chooser directly; Home offers Movies, Series and Live TV. Reuse the existing profile/type category filters, so the original UI and discovery rows remain consistent. Closing the chooser restores the originating Categories action; the hero pauses while either category popup is open.

Replace the shared chooser with a compact dark/red panel, searchable single-line checkbox rows, selection count, search-scoped bulk selection/clear, and fixed full-row Cancel/Save actions matching the app's shared action buttons. Use two readable columns on larger screens and one on phones/large-font constrained widths. Explicit D-pad grid movement preserves horizontal row boundaries, handles incomplete final rows, reveals offscreen categories and restores the previous category from footer actions. Initial remote focus prefers the first selected category. Drafts survive category refreshes and are discarded by Cancel. Search summons the keyboard only on touch/Select, with resizing and compact controls while the keyboard is visible.

Fire TV must remain untouched during this follow-up, as requested. Validate using the tablet; do not install or navigate on Fire TV.

### 2026-10-20 — landscape series episode presentation and remote paths

TV/tablet IPTV series now use a horizontal episode rail instead of stretching the phone's episode rows across the screen. A shared DiscoveryEpisodeTile presents both TMDB and provider episodes: 200dp width, 16:9 still, separate reserved two-line title, episode identity/date, optional progress and remaining time. Provider download/removal/description options continue to use the existing episode actions. Mobile retains its current vertical layout.

Provide explicit hero → season → sort → tools → episode navigation, reverse paths, horizontal row navigation, season selection and load-more return. The tablet/TV episode toolbar scrolls with the page; the phone's sticky toolbar remains. Restore provider playback focus using separate vertical-slot and horizontal-episode positions; base pagination on the episode rail's visible boundary. Cancel pending focus moves when series/season/search/order changes, and restore the episode after options/description dialogs close. TMDB's season strip and episode rail also have explicit Up/Down links. Do not install or test on Fire TV during the user's viewing session.

Follow-up supersedes the missing offscreen Up behavior: reveal lazy sections before transferring focus, include a More info stop between hero actions and season controls, and move the landscape Load more action into the final horizontal episode position. Retain bounded landing previews, focus and prepared artwork across detail returns. Link stable IPTV tile focus to cached public TMDB presentation while retaining provider playback/favorite/detail actions. The user subsequently authorized Fire TV installation and asked for incremental installs as each fix becomes ready; the combined verified build was installed and remote controls were left for their testing.


### 2026-10-20 - header focus entry and category density refinement

Route first and directional header focus-group entry directly to the selected tab before the leading Back control can paint a transient focus state. Handle header recreation after scrolling, retaining explicit popup-origin restoration and normal Left access to Back.

The user prefers the old chooser's density and requested further compaction. Restore up to four category columns on wide screens, adapting to fonts and narrower devices; reduce TV rows to 40 dp while retaining 48 dp touch targets. Use 4 dp vertical gaps, compact checkbox/title padding, a combined header/count, and one toolbar row for search/bulk actions where space permits. Preserve the current dark/red theme, single-line titles with focused marquee, search, bulk actions, draft/Cancel/Save and remote boundaries. Install incremental fixes on Fire TV for user acceptance; do not claim frame performance without measurements.


### 2026-10-21 - bounded row movement and explicit menu entry

Fire TV qualitative reproduction was followed by a temporary geometry trace, with no screen recording running. Visible rows used an explicit critical spring; offscreen rows used LazyListState's separate default animateScrollToItem path. The trace showed the visible-row spring holding the move through its one-pixel tail, with one sampled downward destination lasting about 1.03 s under the diagnostic workload. Earlier speculation that a second automatic focus scroll caused every correction is not established by that trace. Keep the vertical focus-reveal suppression and give both visible/offscreen destinations one 240 ms finite easing timeline, updating the remaining distance as lazy geometry becomes available. Give this explicit scroll user-input priority so lower-priority implicit relocation cannot cancel it; real touch and newer navigation can still cancel. Preserve immediate focus transfer and rapid-input cursor behavior. The temporary per-frame production probe is removed after diagnosis.

Separate menu selection from detail/playback return using a navigation-entry identity. Explicit Home/Movies/Series entry starts at the header and active tab; returning from details continues to restore the originating tile. The category dialog intercepts first focus entry before Search can receive focus and starts the grid at the selected category. Remove decorative category icons. Move IPTV category access out of the top navigation and beside the hero carousel controls, adding TMDB categories alongside it. Use wrapping controls on constrained widths; retain profile/type category filters, separate trial TMDB preferences and popup-origin restoration.

### 2026-10-21 — discovery work on focus and the sleeping-TV constraint

The next audit found avoidable work beyond the finite scroll curve: every presentation/focus invalidation rebuilt all rails and provider progress entries, saved-focus bookkeeping itself invalidated the screen, and metadata/backdrop publication started after 150 ms while the 240 ms vertical move was still running. Memoize content construction by its real inputs, retain current action callbacks independently, and keep restore destinations as saveable event bookkeeping. Defer presentation work until both row and vertical scrolling have stopped, then apply the existing 150 ms stability delay. Highlight focus immediately. Keep navigation, recommendations, progress, parental checks, popups and playback actions.

Selected metadata/actions must never wait for image preparation. Retain the previous background while the next backdrop prepares, while the title and Play/Info actions use the selected identity immediately. Remove the redundant detail-return focus request; the route's existing saved-origin restoration owns this transition.

ProfileStore JSON readers previously decoded unrelated caches on every preference edit in the collector's context. Filter unchanged raw preference keys before decoding; move decoding and decoded equality off Main. Enqueue atomic writes in the caller's order, dispatching only their JSON transforms to Default. Preserve keys, serialization, migration, profiles and cache clearing. Header glass memory-pressure IPC also moves off Main, and its intermediate image decode is reduced to 192×108 for the unchanged 96 px blurred texture.

The user prohibits waking Fire TV or making noise. Do not use existing activity fixtures, shell key events, playback, screen recording or a physical-display fallback. An opt-in isolated regression fixture uses only an app-owned private, nonfocusable Presentation, generated artwork and fake metadata; it checks asleep power state before/during/after. Unsupported offscreen rendering must be reported as unsupported. Virtual focus/frame timings cannot establish physical-panel smoothness. Tablet testing is permitted only if necessary for correctness that cannot be checked another way; its faster hardware is not representative of Fire TV. Detailed commands, results and remaining limitations belong in `discovery-trial-verification.md`.

Further regression checks exposed clock-default timestamp omission in persisted models. Always encode those timestamp fields, retaining compact omission for unrelated defaults and compatibility with older records. Do not invent missing historical timestamps. Pause hidden hero/prefetch/backdrop work behind IPTV movie details; actor popups also pause hero rotation. Explicit menu entry keeps priority until the active tab actually receives focus; a lazy row restoring focus must not scroll that header away first.

Closed tile menus also scanned the whole favorites list during composition; the TMDB predicate constructed a fresh media/favorite object for each candidate. Build exact-key membership once per favorites change and precompute each TMDB entry's media/key once per rail build. Keep a separate profile/media-ID index for the hero's existing broader check. Preserve menu composition, trailer state, source dispatch and duplicate provider occurrences; do not discard closed menus to hide their cost.

Bound simultaneous preference decoding/JSON transforms to two Default workers. Keep DataStore enqueue order and atomicity; this limits metadata CPU contention rather than changing navigation or stored content.

The private-display regression reproduced a separate header-entry failure after a full Home/Movies/Series cycle, including with the same outer saveable surface boundary as CatalogScreen. Diagnostic events showed explicit scroll-to-header reaching index 0, followed by automatic focus relocation to index 1 before the selected tab could receive focus. The coordinator now suppresses that automatic relocation for the complete explicit menu-entry operation, including readiness and focus transfer, and releases ownership on success, failure or cancellation. Detail restoration also protects its exact saved offset while its captured destination takes focus. Nested/canceled entry ownership has a targeted regression.

Keep sampled-method runs separate from final timing runs. FrameMetrics ANIMATION includes the fixture's semantic-focus observer, and private-display TOTAL also includes virtual rendering/queue costs; neither is a direct physical-panel deadline measurement. Report matched fixture improvements and remaining limitations without describing the screen as lag-free.

The bounded Java sampling run found additional main-thread work in provider progress presentation: repeated recent-history scans, progress-prefix scans and regular-expression compilation. Build a per-profile immutable derived progress index off Main when recents/progress change; keep exact prefix matching, original timestamp-tie order, first-recent/null suppression and provider-series aliases. Reuse one normalization Regex and avoid temporary filtered lists. No additional disk cache, provider calls or removal of progress bars.

The strict second-tile detail-return check also reproduced an origin race: recreated lazy content could publish automatic first-tile focus before the return effect read its destination. Capture the intended return target before composing lazy content, protect its bookkeeping/offset during restoration, and request an attached ready target immediately. Release protection on success/failure/cancellation; explicit directional or touch input wins. Touch-only phones must not acquire a remote restoration guard.

String route identity alone is insufficient when returning to an earlier page: guard asynchronous focus work with a composition ownership token as well as the current ViewModel route. An earlier fixture that forced route changes while a tile was focused exposed Compose pinning an already-disposed lazy parent during automatic Android focus reentry. Before explicit remote route replacement, park focus outside the changing lazy/saveable content, then let the existing destination restoration take over. Retain parking eligibility until actual focus loss; making the active target ineligible can itself clear native owner focus. The transient target must not appear in accessibility or normal directional traversal. The final fixture uses the real menu callback, including this production handoff, rather than forcing state or adding a test-only native workaround.

Final validation used only the isolated private display on sleeping Fire TV. The final three current runs passed 735 measured navigation inputs, 28 movie/series detail opens/returns and 14 fake Play identity checks. Default first-cycle UI-phase p95 was 48.88ms versus 173.13ms on the exact baseline; subsequent current cycles were 38.79/37.63ms. The two-frame semantic-focus target and zero >50ms UI samples are not achieved, and virtual metrics do not establish visible-screen polish. Unique-artwork stress showed initialization memory growth followed by decline, not steady growth over three cycles. All 606 normal app unit tests passed, both ABI APKs built, and lint remains failed with 26 errors in unchanged files. Full commands, provenance, failed-run distinctions, memory samples and remaining coverage are recorded in `discovery-trial-verification.md`. No tablet/phone interaction, regular Fire TV app installation, commit or push occurred.

### 2026-10-21 — authorized visible Fire TV follow-up

The user subsequently authorized using the Fire TV screen. Actual Wio Home/Movies/Series navigation, held input, direction reversals, details and a real playback return confirm remaining frame stalls. Sampled Main CPU identifies repeated Compose accessibility-tree processing as a major contributor, even though the custom Home service does not retrieve window content. Keep that service and accessible text/actions. Remove only redundant descriptions on decorative poster/cast images; this cleanup did not demonstrate a measurable speed gain.

The existing nondebuggable performance build produced lower warm frame tails in sequential real-profile comparisons: Home 53→26 ms, rapid Home 117→57 ms, Movies 85→61 ms and Series 61→38 ms. Keep these separate from the earlier private-display measurements and from input-to-focus latency. Startup warm-up, artwork/heap state and Continue Watching ordering differ, so no universal gain or zero-stutter claim follows. The performance APK is left on Fire TV for user acceptance; default Android Studio builds remain debuggable. Playback exit remains a separate measured stall and one repeat startup timed out. Full commands, failures, caveats, final device/settings restoration and the remaining production-performance work are in [the visible navigation review](firetv-visible-navigation-review-2026-10-21.md). The user subsequently requested committing this follow-up; no push is requested.

### 2026-10-21 — visible row entry and edge clipping

Reproduced on Wio Home: Down from Hindi Old Collection revealed English Series but left keyboard focus on the movie row above. Right then moved within that old row and automatic reveal left the enlarged poster clipped at the screen's top edge. Row entry previously requested a saved/first tile without materializing an offscreen horizontal destination. Make entry suspendable within the vertical coordinator's cancellable job: reveal the saved tile (or first current tile), then request focus. Retain visible selections' horizontal offsets and distinguish the provider Search/All header from an appended TMDB View All tile. Cancellation cannot later focus an obsolete destination.

Horizontal focus reveal now reserves the row's existing edge padding for the scaled poster and border. Fully visible tiles stay stationary; oversized tiles do not oscillate while attempting impossible margins. Tile dimensions and animation timings are unchanged. Changes apply to modern discovery's shared rows, without altering the legacy Live TV collection or players.

Targeted verification: 16 unit tests passed (10 row-entry/edge regressions, three existing coordinator tests, three existing viewport tests). The performance APK built successfully in 2m58s and was installed on Fire TV with data preserved. Actual Down into English Series showed visible focus; nine Right/nine Left returned the first tile with its full border. Up/Down returns, rapid direction reversals and re-entry after horizontal displacement retained a visible destination. The mixed touch/D-pad setup also caused normal native focus re-entry, so it is not an isolated offscreen-selection benchmark. Screenshots/events and the exact build log are outside Git at `D:/workspace/niktv-playback-tests/firetv-focus-crop-20261021/`. These are correctness checks, not a new performance comparison. No playback was started, no volume setting changed, and Samsung/tablet were untouched. The original Home accessibility service remained enabled and the temporary input helper was removed. Fire TV is left at Hindi Old Collection's first tile for the user's Down test. No commit or push for this fix.

### 2026-10-21 — reachable row actions and stable loading controls

The previous row-entry/edge fix was committed as `b315012a` at the user's request before this follow-up. Provider posters now route Up to their Search/All header. Up again enters the preceding row; Down or Left returns to the last poster in that row. Header and poster selection are remembered separately, and the existing cancellable vertical coordinator owns both transfers. Down through poster rows remains direct. Opening a full category and Back restore the header origin.

Append featured-title carousel controls after IPTV/TMDB category actions instead of inserting them between already-visible controls when trending finishes loading. Reserve counter width for the two-digit index. Apply the trailing order to phone controls too. Category actions do not wait for trending or make extra requests. The category manager always composes Select All/Clear All and keeps the same inline/stacked toolbar arrangement when IME visibility changes; only the grid resizes for the keyboard. Bulk actions are disabled for empty results without removing their layout space. Keeping the toolbar visible while typing consumes its normal row height on compact devices.

Targeted verification: 26 navigation, row-focus and category-policy tests passed, zero failures/errors/skips. Performance APKs built in 2m42s and installed successfully on Fire TV (ARMv7) and Xiaomi Pad 5 (ARM64), preserving app data. Fire TV screenshots verify Up to Search/All, Down to the remembered poster, opening the full category, and Back to the header. A popup recording shows both bulk controls in its first captured category-manager frame; carousel controls are confirmed after the category actions. This is a visual correctness check, not a quantified latency comparison or a guarantee against every layout shift. Tablet installation and profile-screen launch were verified; detailed tablet touch/D-pad acceptance is left to the user. No media played, selections were not saved, no audio settings changed, and the temporary Fire TV input helper was removed. Artifacts are outside Git at `D:/workspace/niktv-playback-tests/firetv-actions-20261021/`. These follow-up changes remain uncommitted; no push.
