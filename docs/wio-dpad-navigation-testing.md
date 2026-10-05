# Wio D-pad navigation verification

The pending player-queue and phone-category changes were committed first as
`2d6433c`. The subsequent navigation fixes were tested on the connected Xiaomi
tablet using Android D-pad, OK, long-press OK, and Back key events. Touch was used
to set up some secondary screens; navigation and Back were then checked with
key events.

## Behaviour implemented

- Hardware TVs and phones/tablets receiving keyboard/D-pad input use the same
  entry-focus policy, while keeping their own screen layouts.
- Fresh IPTV and TMDB collection visits start at the first media tile. Returning
  from playback retains the selected item and viewport.
- Category hubs select their first tile on entry and the opening category on
  Back. Lower-page category return waits for the target category to exist before
  restoring the viewport.
- Episode loading keeps focus inside the series pane, then selects the top
  displayed episode. Fresh series entry clears old playback-return markers;
  actual playback return retains the played episode and its position.
- Back follows the visible pane rather than overlapping handlers. Match lists,
  downloads, settings, search, favorites, series details, and collections each
  close before their underlying destination.
- Direct fullscreen playback leaves on one Back press, including when ordinary
  controls are visible. Menus, programme guides, and the media overlay close
  before leaving the player. Embedded playback keeps its existing controls flow.
- Search focuses its query control. My List and Downloads focus their first
  media item, or Back when empty. Home focuses Continue Watching when available.
- Manual collection pagination uses the same focus keys and grid indices as its
  tiles; appending/replacing results can recover a recycled focused tile without
  jumping to the sidebar.

## Device checks

| Route | Observed result |
| --- | --- |
| Profile chooser → Wio | D-pad selection opened the Wio Home screen. |
| Home entry with watch history | First Continue Watching card focused and retained focus after cancelling Exit. |
| Live TV → English 24x7 Movies | First category and first channel focused. |
| English 24x7 Actors | Denzel focused on entry; Down selected Cillian in the same column. |
| Cillian playback → Back | Selected Cillian tile restored, including with player controls visible. |
| Actor grid below the first viewport → playback → Back | Vin Diesel selection and scrolled viewport restored. |
| Actors → Back → reopen | Category tile restored; reopening started at Denzel. |
| Movies → English Latest Movies 4K | First movie, Cowboy Killer, focused. |
| Bringing Dad Home playback → Back | Second movie selection restored; fresh category re-entry selected Cowboy Killer. |
| All Movies automatic pages | 14 → 28 → 42 loaded; focus remained in the media grid rather than falling to Profile. |
| Series → Hindi Web Series | First series, Yeh Jo Hai Zindagi, focused. |
| Open Yeh Jo Hai Zindagi | Top displayed episode E67 focused after loading; Down selected E66. |
| E66 playback → Back | E66 and its viewport restored. |
| Leave and reopen the same series | E67 focused again rather than the last-played E66. |
| Episode long-press menu → Back | Only the menu closed; episode focus retained. |
| Lower-page Malayalam TV Shows → Back during loading | Scrolled category tile restored; focus stayed inside the loading collection before Back. |
| Category management → Back | Returned to its header icon without applying changes. |
| Settings → Back | Returned to the previous browse pane. |
| Search on tablet D-pad | Query control focused; Back returned to the previous browse pane. |
| Empty My List / Downloads | Local Back icon focused; Back returned to the prior browse pane. |
| Populated My List | A temporary Cillian favorite was focused on entry and removed after testing. |
| Home → Back → Back | Exit confirmation opened and was dismissed without closing the app. |
| VLC playback failure → Back | Returned to the Seinfeld tile. |

## Validation and limits

290 unit tests and Android lint passed; the debug APK was built and installed on
the tablet. The original Auto playback-engine setting was restored after the
VLC check. No FireTV installation or playback interruption was performed.

The sampled Seinfeld stream failed in VLC, so that device check validates the
failure-screen navigation, not successful VLC decoding. Downloads was empty;
populated downloads/recordings could not be exercised with the user's library.
Provider requests can still delay playback or episode availability; focus waits
inside the destination during loading. These changes do not alter programme
request parameters or introduce Room storage.
