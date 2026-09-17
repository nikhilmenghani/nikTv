# Local catalog and device backups

NikTV stores provider categories, catalog items, pagination checkpoints, and episode-season caches in `catalog.db` using Room. Existing JSON catalogs and episode preferences are imported automatically. Favorites, history, downloads and account settings retain their existing storage and backup behavior.

## Loading and playback

Settings → Backup and restore → **Prefer local catalog** defaults to enabled. Cached catalog data remains usable even when old; missing categories/pages use the provider. Returning profiles can open with their saved session while authentication refreshes in the background. First connection still authenticates. **Refresh catalog from provider** queues a full background scan; the existing per-screen refresh controls still bypass the local cache. Disable **Prefer local catalog** to request fresh provider listings.

Xtream playback URLs are assembled from current profile credentials and the saved stream/episode ID and extension. Stalker commands are retained locally, but final stream URLs are always resolved through the portal. A missing/unusable local item falls back to provider lookup. Downloaded/offline media does not require these network requests.

Provider scans run approximately daily, independently of GitHub configuration, and can also be queued when opening a profile. Each completed page is checkpointed. Scans and automatic catalog backup pause while a player is open. Android may defer or interrupt background work; a later run resumes unfinished categories. Series listings are scanned; episode details are populated when series are accessed, rather than fetching every episode of every series at startup.

Only a scan that starts at page one and reaches the provider's end can mark missing items unavailable. Interrupted, capped or repeating pages never delete items. Removal markers are included in snapshots so an older device cannot restore a removed record during merge. Scan freshness is provider-observed device time; accurate device clocks are important for resolving concurrent changes.

## Opt-in backup

**Back up catalog from this device** is OFF by default and is a device-only preference. Configure and save GitHub settings first. The backup password is optional: leave it blank for password-free uploads, or use at least 12 characters for encryption. Existing encrypted snapshots still require their original password to restore.

Enabled devices upload approximately every 12 hours; **Back up catalog now** queues an immediate run. Each installation writes only its own snapshot file at:

`catalog-v1/<account-hash>/<media-type>/<device-uuid>.niktv`

Files are compressed logical snapshots of Room records, not copies of an open SQLite file. With a blank password, a versioned gzip/base64 envelope is uploaded without encryption; with a password, the existing encrypted format is used. Playback commands can contain account-specific information readable by anyone with repository access in password-free mode. These snapshots exclude personal history/favorites and device settings. Unchanged snapshots are skipped. Existing settings/profile backups remain separate.

**Merge catalogs from GitHub** is available on any device, including devices with uploading disabled. It imports snapshots for matching saved profiles, unions distinct records and selects the newest observed version of conflicting records. It does not replace the live database or personal preferences. Reopen the profile after an import to reload in-memory screen state. Each device has its own upload file, so two devices cannot overwrite each other's snapshots. Imports are explicit; enabling upload does not silently replace or merge data on another device.

Catalog payload version 1 is independent of the Room schema. Unsupported versions or mismatched profile/type identities are rejected. Restores run in database transactions. Backups are currently limited to 20 MiB uploaded / 80 MiB expanded per profile and media type; exceeding the limit fails visibly instead of silently truncating data.

The old periodic GitHub search-index uploader is removed and its scheduled work is cancelled on upgrade. Old remote search-index files are left untouched; they are not full catalog backups and are not imported by the new snapshot action.

## Verification

`gradlew testDebugUnitTest assembleDebug` runs in-memory Room tests covering legacy migration, profile/type isolation, multi-device merge, removal markers, complete-category routing, episode merging, and current-credential Xtream URLs. GitHub upload/restore and provider scanning still need device-level acceptance testing with configured accounts.
