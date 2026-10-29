# Sports hub: Live Events, Sports and Categories

Implementation baseline: `91d27511`; changes are local and uncommitted. Reference audit: [Cricfy TV parity audit](../cricfy-tv-parity-audit-2026-10-28.md).

## Navigation and design

The existing Sports tile still opens `SportsActivity`. Its header now offers **Live Events**, **Sports** and **Categories**, with the same `DiscoveryAction` controls used by NikTv's modern profile navigation. The active tab is red, focus has a white border, and tiles/dialogs use NikTv's rounded dark surfaces and shared action buttons. Smaller widths place tabs on a second row instead of compressing their labels.

- Initial entry and explicit tab changes focus the selected tab. Selection requires OK; moving between tabs does not activate them.
- Down enters the current content; grids preserve row boundaries and handle an incomplete last row. Moving to virtualized tiles scrolls the grid.
- Source selection, channel navigation, category browsing and playlist groups remain distinct operations.
- Returning from a detail/collection restores the originating tile, scroll position and local search. Returning from playback restores the source chooser and selected source.
- Held tiles open a favourite action dialog. Held event filters retain the existing Set as default flow. Dialog dismissal restores the initiating control.
- Search is local. Typing, focus movement and catalogue loading never start playback.

## Features

**Live Events:** service-defined sport filters and artwork, status/filter counts, team badges, featured metadata, local schedule, a one-second countdown/elapsed clock, favourites and fresh detail-based source choices. Recent is the finished-event predicate: the APK's `u04.f` compares current time to the scheduled end, without a separate recent-time window. Existing Finished defaults migrate to Recent. UI clock updates do not cause one-second catalogue retries; freshness checks remain at least 30 seconds apart.

**Sports:** the separate channel catalogue with logos/text labels, full source choices, favourites, related channels and explicit previous/next channel actions in the detail header. The existing player's next/previous source actions keep their original meaning.

**Categories:** built-in collections and external playlists route separately. The current `cats.txt` determines destinations; sentinel strings such as N/null/ok use built-in paths. External URLs are never hard-coded from the audit snapshot. Collections include local search, explicit refresh, empty/error states, playlist groups and favourites.

**Your playlists:** URL import and system file selection, private local copies of imported files, removal confirmation and searchable group selection. Sports favourites/imports use their own `sports_library_v1` preferences/files and never access IPTV profile storage. Signed playlist links refresh by stable provider/item identity rather than by an expiring media URL.

## Backend and isolation

`SportsCatalogue` now parses categories, filter definitions, channel previews and full details, retaining formats, event artwork/featured metadata, related channels and navigation pointers. Preview choice counts do not determine the source list. Category, preview, format, detail and stream availability are applied at their respective browse/detail boundaries.

`SportsPlaylist` supports ordinary M3U, JSON arrays/`channels` objects, catalogue-compatible encodings and the legacy playlist AES envelope. Per-item User-Agent/Referer/Origin/Cookie and DRM options survive parsing; options reset between M3U entries. Duplicate source occurrences remain separate, including across signed-link rotation. HTML/error objects are rejected as unavailable responses rather than successful empty playlists, and error bodies are not cached as valid external playlists.

Limits: 4 MiB per response/import, 20,000 playlist items, sixteen short-lived cached catalogue bodies. `CFP1.` native envelopes remain unsupported and produce an explicit message; no sample of this format was available in the audited external responses. A provider's HTML/403/404, empty response or expired source remains an upstream result, not an indication that every listed service is playable.

Playback continues through the unchanged `SportsStreamResolver`, `SportsPlaybackController`, `SportsPlayer` and sports-specific player implementation/settings. No IPTV player, decoder policy, profile model, repository, UI or settings source file was changed. Private playlist URL/header/licence values are not included in verification logs or the checked-in audit inventory.

## Verification

Evidence directory: `D:/workspace/niktv-playback-tests/sports-hub-20261028/`; build/unit logs also use the `sports-hub-` prefix in its parent directory.

The first isolated tablet run passed seven instrumentation tests: the new hub navigation/group/live-source checks, the existing Sports UI/held-filter/gesture checks, and the existing Sports/IPTV preference isolation test. The live test returned **45 events, 57 Sports channels, 74 categories and 162 SAMSUNG TV playlist channels** and received the first rendered video frame from Willow through `SportsPlayer`. Audio volume was restored in `finally`.

The Sports JVM suite passed **52 tests**, covering existing resolver/playback/regional/isolation behavior and new category routing, preview/detail discrepancies, event metadata/clocks, M3U option isolation, duplicate identities, JSON/error classification, legacy playlist decoding, region precedence, persistence and grid movement.

Final verification on 28 October 2026:

- Normal Dev APK build succeeded. The final Sports JVM suite passed **52/52** tests.
- Final isolated Xiaomi Pad 5 instrumentation passed **8/8** tests, including an empty-directory D-pad case. Real Willow playback rendered a frame again.
- Installed the normal ARM64 NikTV Dev APK in place on the tablet, preserving its app data. The normal Sports activity was opened and reviewed visually: event artwork/clocks/counts, six-column Sports logos, source choices/related channels, five-column category tiles and the playlist group popup.
- Opened DISTRO TV in the normal app: its response, classified as opaque in the initial audit, decoded through the legacy adapter and displayed **75 channels** with group selection. No claim of playback for these 75 channels is made. Some of its channel logos use the fallback icon.
- `nik_tv_profiles.preferences_pb` and `player_preferences.preferences_pb` SHA-256 hashes matched before installation and after the normal-app navigation review. Both stores are byte-for-byte unchanged.
- The favourite/context, search and group flows were tested with isolated fixtures; no favourites or custom playlists were saved to the user's normal app during visual review. No media was played in the normal app. The user is left on Categories with playback stopped.

Screenshots: `tablet-live-events-settled.png`, `tablet-channels.png`, `tablet-willow-detail.png`, `tablet-categories.png`, `tablet-distro.png`, `tablet-groups.png`. The initial `tablet-live-events.xml` captured the loading state; use the settled screenshot for artwork verification. Logs: `tablet-final-tests.log`, `iptv-stores-before.txt`, `iptv-stores-after.txt`.

Fire TV was not installed or tested for this task; D-pad checks use actual key events on the connected Xiaomi Pad 5. Current checks do not establish playback of every third-party source or every DRM/cast/engine combination.

## Disney playback follow-up, 28 October 2026

Investigated the user's failing **Kids / disney** channel in NikTV, then compared the same channel in the newly updated **Cricfy 7.3 (53)** on the same tablet and network. Fresh channel metadata was unchanged from the audit. Both apps exhausted the four advertised choices: **4K returned HTTP 404, HD returned HTTP 403, JR and XD failed DNS resolution**. Cricfy's player reported `Connection Failed`. These sources currently fail in the reference app too; this comparison does not establish a missing request-header or player feature in NikTV.

The NikTV failure popup did have a layout defect: an unconstrained shared action button expanded to fill the dialog and hid the reason. The Sports-only popup now uses bounded NikTV primary/secondary actions, shows the failure reason, and offers **Refresh and retry** and **Choose another source**. Failed switching can also retain the previous stream. No resolver, player, or IPTV code changed for this fix.

An isolated tablet instrumentation test passed, verifying visible text/action bounds, initial retry focus, and D-pad activation of source recovery. Sanitized comparison evidence is in `disney-comparison-summary.json`; the focused test log is `disney-error-device-test.log` in the evidence directory above. Raw diagnostic logs remain local outside the repository.

The follow-up normal Dev build succeeded and all **52/52 Sports JVM tests** passed again (`disney-error-final-build.log`). The updated ARM64 APK was installed in place on the tablet, preserving app data.

Reproduced the exhausted Disney failure in the installed normal app and visually verified the readable popup (`disney-fix-result.png`). Activated fresh retry, then used D-pad Down/OK to return to the four source choices (`disney-fix-recovery.xml`). Playback is stopped and the tablet is left on that chooser. The IPTV profile and player preference hashes still match before/after (`disney-iptv-before.txt`, `disney-iptv-after.txt`). Temporary regression packages were removed.

## Working-source comparison and availability flags

Selected **Sports / Willow / WILLOW - HD** in Cricfy 7.3 on the tablet. Video rendered, and Cricfy's source controls confirmed that **WILLOW - HD** remained checked. Then selected the identically named first source in NikTV's Willow detail. Video rendered there too and continued across two captures. Both showed the Willow Sports cricket broadcast. This is a positive playback comparison for this source; it does not establish every resolver type or DRM combination.

Evidence: `cricfy-willow-playing-1.png`, `cricfy-willow-playing-controls.xml`, `niktv-willow-playing-1.png`, `niktv-willow-playing-2.png`. Cricfy browser ads were dismissed with Back; no subscription or browser settings were changed.

Availability flags apply only inside the Sports/Cricfy hub. Channel tiles (including Live Events, favourites, related channels and category/playlist results) show an amber border and a text marker. Category folders themselves are not flagged or filtered. Hold a tile for **Flag as unavailable** / **Remove flag** alongside the existing favourite action. Details show the observation time and allow removing the flag.

**Previously failed** records a locally exhausted resolve/playback attempt, not a claim that the provider permanently retired a channel or that Cricfy also fails it. A rendered video frame from the matching active playback attempt clears only automatic markers. **Flagged unavailable** records an explicit user choice and remains until removed. Stale callbacks and pending source replacement cannot clear flags. Selecting a source is still allowed for flagged channels; no background catalogue-wide probing is performed.

The **Availability** chooser offers **All channels**, **Hide flagged** and **Flagged only**, and remembers the selection across app restarts. It combines with search, event filters, playlist groups and favourites. Removing the final filtered result returns D-pad focus to the availability control. Flags save only stable channel identities, reason and timestamp in `sports_availability_v1`; no stream links or IPTV profile data are stored there. IPTV Live TV tiles remain outside this change, per the user's requested scope.

Verification: **56/56 Sports JVM tests passed** (`availability-final-build.log`). Final isolated tablet run passed **11/11 instrumentation tests** (`availability-tablet-final-tests.log`), including hold/release/D-pad flagging and removal, persisted filter selection, empty-result focus recovery, shared event/category identities, existing hub/favourite/group navigation, live video clearing an automatic marker, stale-frame rejection, preservation of manual flags, and Sports/IPTV preference isolation. The initial two new UI tests lacked a wait between opening the chooser and selecting an option; after adding that synchronization the full run passed.

In the normal installed app, repeated Disney's actual exhausted playback to create an automatic flag, then visually verified its amber **Previously failed** tile (`availability-normal-flagged.png`). **Hide flagged** removed Disney and changed Kids from **10 to 9 channels** (`availability-normal-hide.xml`). Its saved flag contains only `disney`, `PLAYBACK_FAILED` and an observation timestamp (`availability-normal-flags.xml`).

Installed the final normal Dev APK (`availability-installed-build.log`, successful build), preserving app data. The saved Hide flagged selection survived reinstall/restart. Held Willow's tile by touch, manually flagged it, verified **Flagged unavailable**, opened its detail and removed the flag. Willow's marker was removed; Disney's automatic marker remained. Restored **Hide flagged**, reopened **WILLOW - HD**, and confirmed video again (`availability-final-willow-playing.png`). The tablet is left playing this source.

IPTV profile and player preference SHA-256 hashes matched before/after (`availability-iptv-before.txt`, `availability-iptv-after.txt`). Final saved availability data contains only Disney's automatic marker and `HIDE_FLAGGED` (`availability-final-saved.xml`). Temporary regression packages were removed. No IPTV source files changed and no IPTV tile flag feature was added.
