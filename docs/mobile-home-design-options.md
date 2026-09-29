# Mobile Home design options

Proposal based on the connected Samsung Home screen, inspected on 29 September 2026. Option B episode rows and compact two-column destination tiles are now implemented on phone browse screens; the remaining concepts are proposals. Wireframes show hierarchy rather than exact pixel dimensions.

## What the current screen could improve

- The NikTV/profile header and large Home heading occupy two substantial rows. The Customize button takes more width than the new tablet/TV icon controls.
- New Episodes repeats the same tall series poster for each episode. Two cards occupy much of the first screen, while episode titles and dates compete with the series title over the image.
- Provider text such as “Sony Sab Hindi” is part of the primary title. It can be presented separately without removing information.
- Categories use full-width rows, requiring considerable scrolling.
- The selected bottom tab is much wider than the others, and the inactive tabs have no visible labels.

## A. Shared visual language — recommended

Use the tablet/TV colors, outlines, corner shapes and header icons, adapted to touch and a narrow screen. Keep the app/profile bar compact and show a few relevant quick actions alongside the page heading. Additional actions remain in the menu.

```text
┌──────────────────────────────────────┐
│ NikTV · wio          Search  Profile │
│ Home                     TMDB  Tune │
│                                      │
│ New Episodes                View all │
│ ┌────────────────┐ ┌───────────────┐ │
│ │ 16:9 artwork   │ │ 16:9 artwork  │ │
│ ├────────────────┤ ├───────────────┤ │
│ │ Taarak Mehta…  │ │ Taarak Mehta… │ │
│ │ S49 · E4827    │ │ S49 · E4826   │ │
│ │ The Truth…    │ │ The Plan…     │ │
│ │ 28 Sep        │ │ 26 Sep        │ │
│ └────────────────┘ └───────────────┘ │
│                                      │
│ Live TV                     View all │
│ ┌────────────────┐ ┌───────────────┐ │
│ │ TV · English   │ │ TV · English │ │
│ │ 24x7 Movies    │ │ 24x7 Actors  │ │
│ └────────────────┘ └───────────────┘ │
│ ┌────────────────┐ ┌───────────────┐ │
│ │ TV · English   │ │ TV · Hindi   │ │
│ │ Classic Series │ │ Entertainment│ │
│ └────────────────┘ └───────────────┘ │
├──────────────────────────────────────┤
│ Home   Live TV   Movies Series My List│
│  icon    icon     icon   icon   icon │
└──────────────────────────────────────┘
```

**Episode cards:** use episode artwork when available, otherwise the series image cropped carefully to landscape. Put text below the image on an opaque panel, with the series name, episode number, episode title and date in distinct lines. Preserve the complete title and description in the existing long-press/info action; long names can wrap naturally in the card. Keep provider/channel information available as secondary metadata rather than repeating it in the main title.

**Categories:** use two columns where the available width and font size permit readable labels. Each card keeps its language/provider label above its main category title, matching the existing TV hierarchy. Fall back to one column when text needs more space. On Home, present a short category preview with a clear View all action; the full catalog remains accessible.

**Navigation:** give all five bottom tabs equal width and a short visible label. Use the existing red accent for the active tab rather than enlarging its footprint. Preserve the current destinations and return-to-scroll behavior.

**Sizing guide:** 16 dp outside margins, 12 dp between cards, 12–16 dp corner radius, and at least 48 dp touch targets. Treat these as starting values; available window size and font scaling decide the final column count and height. Use a subtle pressed-state animation rather than moving the entire row.

**Why this option:** it preserves recognizable artwork and the existing dark visual style while bringing more useful information into the first screen.

## B. Compact episode list

Best when identifying and catching up with episodes matters more than browsing artwork.

```text
┌──────────────────────────────────────┐
│ NikTV · wio          Search  Profile │
│ Home                           Tune │
│                                      │
│ New Episodes                View all │
│ ┌───────┐ Taarak Mehta…              │
│ │ image │ S49 · E4827 · 28 Sep       │
│ └───────┘ The Truth Behind The Theft │
│ ──────────────────────────────────── │
│ ┌───────┐ Taarak Mehta…              │
│ │ image │ S49 · E4826 · 26 Sep       │
│ └───────┘ The Plan To Save Popatlal  │
│                                      │
│ Live TV                              │
│ [ English · Movies ][ Hindi · Drama ]│
│ [ English · Actors ][ Sports        ]│
├──────────────────────────────────────┤
│ Home   Live TV   Movies Series My List│
└──────────────────────────────────────┘
```

This provides more title width and room for episode metadata, especially with larger accessibility fonts. It shares the tablet/TV styling but changes Home's episode section from poster browsing to a list. Tap-to-play and long-press actions stay available on the entire row.

## C. One card per subscribed series

Best when multiple new episodes from the same series make Home repetitive.

```text
┌──────────────────────────────────────┐
│ NikTV · wio          Search  Profile │
│ Home                           Tune │
│                                      │
│ New Episodes                View all │
│ ┌──────────────────────────────────┐ │
│ │ Landscape series/episode artwork│ │
│ ├──────────────────────────────────┤ │
│ │ Taarak Mehta Ka Ooltah Chashmah  │ │
│ │ 2 new episodes · latest E4827    │ │
│ │ Latest: The Truth Behind…       │ │
│ │ Play latest       All episodes  │ │
│ └──────────────────────────────────┘ │
│                                      │
│ Live TV                              │
│ [ English · Movies ][ Hindi · Drama ]│
│ [ English · Actors ][ Sports        ]│
├──────────────────────────────────────┤
│ Home   Live TV   Movies Series My List│
└──────────────────────────────────────┘
```

This removes duplicate series posters and gives episode titles more width. It changes navigation: selecting a series card must expose all its new episodes, with a separate explicit Play latest action. The grouped count must reflect unwatched/undismissed episodes, and individual mark-as-watched, dismiss and download actions must remain available. This is a larger behavioral change than A or B.

## Recommendation and review checks

Option B is the current phone Home experiment. Destination tiles now use two columns across phone browse tabs when space and text size permit; longer names expand to a full row, and larger accessibility text uses one column. The compact header and equal-width bottom tabs shown in the wireframe remain proposals. Consider C later if repeated series entries remain a problem.

Before implementing a design, agree on the episode presentation and whether Home should preview categories or show the complete list. Validate the chosen layout on Samsung and Poco in portrait/landscape, on tablet, at enlarged font sizes, and with long titles. Existing search, customization, badges, progress, offline actions, pinning, watched actions and TV D-pad navigation must remain accessible. Tablet and TV should retain their current layout unless explicitly redesigned.
