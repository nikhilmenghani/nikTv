# Local catalog and device backups

NikTV stores provider categories, catalog items, pagination checkpoints, and episode-season caches in `catalog.db` using Room. Existing JSON catalogs and episode preferences are imported automatically. Favorites, history, downloads and account settings retain their existing storage and backup behavior.

## Loading and playback

Settings → Backup and restore → **Prefer local catalog** defaults to enabled. Cached catalog data remains usable even when old; missing categories/pages use the provider. Returning profiles can open with their saved session while authentication refreshes in the background. First connection still authenticates. **Scan and update this profile** queues a full background scan for the selected profile; the existing per-screen refresh controls still bypass the local cache. Disable **Prefer local catalog** to request fresh provider listings.

Xtream playback URLs are assembled from current profile credentials and the saved stream/episode ID and extension. Stalker commands are retained locally, but final stream URLs are always resolved through the portal. A missing/unusable local item falls back to provider lookup. Downloaded/offline media does not require these network requests.

Provider scans are explicitly queued per profile, or scheduled per device at 6h, 12h, daily or weekly intervals (Off by default). Opening a profile no longer queues an exhaustive scan. Each completed page is checkpointed. Workers yield after a two-minute batch and enqueue a continuation, retaining the profile/type cursor. A new scan resets pagination while preserving existing searchable records. Scans and automatic catalog backup pause while a player is open. Android may defer or interrupt background work; a later run resumes unfinished categories. Series listings are scanned; episode details are populated when series are accessed, rather than fetching every episode of every series at startup.

Only a scan that starts at page one and reaches the provider's end can mark missing items unavailable. Interrupted, capped or repeating pages never delete items. Removal markers are included in snapshots so an older device cannot restore a removed record during merge. Scan freshness is provider-observed device time; accurate device clocks are important for resolving concurrent changes.

## Opt-in backup

**Back up catalog from this device** is OFF by default and is a device-only preference. Configure and save GitHub settings first. The backup password is optional: leave it blank for password-free uploads, or use at least 12 characters for encryption. Existing encrypted snapshots still require their original password to restore.

Enabled devices upload approximately every 12 hours and after a successful full profile scan; **Back up catalog now** queues an immediate run. Each installation writes only its own snapshot file at:

`catalog-v1/<account-hash>/<media-type>/<device-uuid>.niktv`

Files are compressed logical snapshots of Room records, not copies of an open SQLite file. With a blank password, a versioned gzip/base64 envelope is uploaded without encryption; with a password, the existing encrypted format is used. Playback commands can contain account-specific information readable by anyone with repository access in password-free mode. These snapshots exclude personal history/favorites and device settings. Unchanged snapshots are skipped. Existing settings/profile backups remain separate.

**Merge catalogs from GitHub** is available on any device, including devices with uploading disabled. It imports snapshots for matching saved profiles, unions distinct records and selects the newest observed version of conflicting records. It does not replace the live database or personal preferences. Reopen the profile after an import to reload in-memory screen state. Each device has its own upload file, so two devices cannot overwrite each other's snapshots. Imports are explicit; enabling upload does not silently replace or merge data on another device.

Catalog payload version 1 is independent of the Room schema. Unsupported versions or mismatched profile/type identities are rejected. Restores run in database transactions. Backups are currently limited to 20 MiB uploaded / 80 MiB expanded per profile and media type; exceeding the limit fails visibly instead of silently truncating data.

The old periodic GitHub search-index uploader is removed and its scheduled work is cancelled on upgrade. Old remote search-index files are left untouched; they are not full catalog backups and are not imported by the new snapshot action.

## Verification

`gradlew testDebugUnitTest assembleDebug` runs in-memory Room tests covering legacy migration, profile/type isolation, multi-device merge, removal markers, complete-category routing, episode merging, and current-credential Xtream URLs. GitHub upload/restore and provider scanning still need device-level acceptance testing with configured accounts.

## Dated checkpoints and new devices

Uploads also save immutable compressed logical Room checkpoints under `catalog-checkpoints-v1/<account-hash>/<timestamp>-<device-uuid>.niktv`. Each checkpoint includes all three media catalogs and episode information already cached. It records whether a complete profile scan was finished when exported. Checkpoint imports validate the profile, schema and every catalog and merge all three in one transaction; malformed imports roll back. Newer local records survive a merge, so this is not a destructive point-in-time rollback. Unchanged checkpoints are skipped. The combined checkpoint currently has the same 20 MiB upload / 80 MiB expanded safety limits as individual files; oversized backups fail visibly.

On a new device, add or import the same IPTV profile, save GitHub settings (password optional except for previously encrypted files), and choose **Restore a catalog checkpoint** for that profile. Choose the date/device and confirm the merge. Search can use imported records immediately; reopen the profile to refresh dashboard caches. No automatic full scan is triggered by opening the profile. Set a schedule afterward if that device should refresh directly from the provider. Older per-device snapshots remain importable with **Restore IPTV catalog**.

## Search fallback

Typing uses debounced local search over Room and cached results first. If there are no matches, no completed profile scan/checkpoint, or a refresh is incomplete, the provider is queried automatically and results are merged. A completed catalog with local matches avoids an extra provider call. **Search provider** remains an explicit override. Editing the query cancels outdated work. Provider failures retain local results and display a nonblocking message. Completion refers to catalog listings at the last scan; it does not guarantee provider availability, episode-level indexing or freshness beyond that date.
