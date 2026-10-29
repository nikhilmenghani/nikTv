# IPTV profile Live TV availability

Cricfy work was committed separately first as `0ac16cc4`. This change adds the same local flagging model to IPTV profile Live TV, without changing provider catalogues, credentials, pin order, favourites or decoder/recovery policy.

## Behaviour

- Live channel cards show an amber border and **Previously failed** (automatic) or **Flagged unavailable** (manual). The existing hold menu offers **Flag as unavailable** / **Remove flag**, alongside programme refresh, My List and pin actions. Flagged menu content includes the observation time and explains that retry is still allowed.
- The **Availability** chooser uses NikTV's dark rounded modal, shared buttons and selected-option D-pad entry. **All channels**, **Hide flagged** and **Flagged only** persist separately for each profile. Search, programme search, source-category filters and pins combine with it. Category folders and movie/series collections are not filtered.
- Category channel collections, Recently Played and My List use the same profile flag/filter state. Unflagging the final filtered tile returns focus to Availability; it does not remove the channel from the provider, history or favourites. Player queue tiles expose the same marker and hold action. A collection's filtered playback context also excludes hidden flagged channel IDs when later provider pages append.
- Filters describe the channels loaded so far. Load more / search can still fetch more catalogue metadata. No background stream probing or catalogue-wide classification is performed.

## Storage and playback

`iptv_channel_availability_v1` is separate from Cricfy, profile, player and pin preferences. A SHA-256 of the profile cache key namespaces each flags map/filter; identical channel IDs in different profiles remain separate. Values contain only channel ID, reason and observation time; no stream URL, media object or credentials are written to this store.

Player callbacks observe the existing final Media3/MPV or VLC error after recovery. The authorization-refresh message is excluded while its first automatic retry is pending; decoder recovery and paused/background failure callbacks are not treated as new terminal observations. The existing startup timeout remains unchanged. A real rendered video frame clears only an automatic marker. Manual markers remain until explicitly removed.

The ViewModel accepts an observation only when profile, active playback attempt and channel ID still match. Movies, offline media, alternate-source playback and stale/closed sessions cannot change Live TV flags. No sports source/player implementation is changed in this commit.

## Verification

Evidence files are local under `D:/workspace/niktv-playback-tests/`, with the `iptv-availability-` prefix. Tablet tests run in the separate `.regression` package with synthetic profile identities; generated local video/404 fixtures do not contact an IPTV provider or alter the user's saved profiles.

The Sports availability, new IPTV availability, existing player queue, live recovery, guide, tile presentation and DVR/window JVM checks passed **64/64** tests (`iptv-availability-final-build.log`).

The initial tablet run exposed category grid focus reclaiming after filter changes. The category now waits for its asynchronous filter result and foreground window before restoring Availability, and suppresses grid entry until explicit Down navigation through search. The isolated hold/filter/unflag/empty-result D-pad test then passed (`iptv-availability-focus-verified.log`). Viewport restoration after playback remains unchanged.

Final Xiaomi Pad 5 run passed **10/10 instrumentation tests** (`iptv-availability-verified-tablet-tests.log`): five new IPTV availability tests, four existing player queue navigation tests, and Sports/IPTV player preference isolation. The new tests cover real D-pad hold/release, flag/unflag and filter focus, empty results, profile changes, absence of the control in VOD, My List/history consistency, actual Media3 and VLC failure callbacks, actual rendered local video clearing automatic flags, and preservation of manual flags.

The final normal Dev APK built successfully and was installed in place, preserving app data (`iptv-availability-verified-installed-build.log`). Opened **wio → Live TV → SPORTS | CRICKET**, temporarily flagged the first tile, visually reviewed its amber border/label, and verified **Hide flagged** reduced the 28 loaded channels to **27**. **Flagged only** showed the one marked channel. Held it again and removed the flag; the empty filtered view retained a working Availability control. Restored **All channels** and left the tablet in this category, without starting an IPTV stream. All IPTV flags are empty after this review.

Screenshots/XML: `iptv-availability-normal-flagged.png`, `iptv-availability-normal-hide.xml`, `iptv-availability-normal-only.xml`, `iptv-availability-normal-empty.xml`. `iptv-availability-normal-final-store.xml` confirms an empty flags map and `ALL` filter. Temporary regression packages were removed.

The general player preference store and Cricfy availability store hashes remained identical. The profile DataStore hash changed during the normal-profile/category visit; this verification does **not** claim that entire store remained byte-for-byte unchanged. No profile editor, credential, pin, favourite or playback-setting action was invoked during that review. Raw app data and diagnostics remain outside the repository.
