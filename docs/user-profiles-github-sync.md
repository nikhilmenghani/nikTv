# User profiles and GitHub account sync

## Identity and navigation

The GitHub numeric account ID is the account boundary. A token identifies that account; it is not an account identifier or an encryption password. User profiles have permanent UUIDs, editable names/avatar/colors, and a stable `GENERAL` or `KIDS` type. Portals are separate content connections. Sports and Offline are destinations, not authentication types.

Fresh startup is GitHub account → token validation → private repository setup and initial sync → user profiles → that person's portals → content. A successful empty-account initialization creates Family and Kids once. Their UUIDs derive deterministically from the numeric account ID and the default profile type, so simultaneous initialization converges. A retained account marker and profile tombstones prevent recreating deleted defaults. Existing accounts keep their profiles. Kids currently has **no content restrictions**.

Later launches try sync with the protected token before showing the chooser. Previously initialized accounts can use cached data after a connection failure. Invalid/revoked credentials require reconnect and preserve local data. Settings and token rotation are on the GitHub account screen, reachable from ordinary navigation. Disconnect removes authentication on this device; profile deletion explicitly deletes across devices after sync.

Adding a user profile creates a new UUID with empty viewing data, preferences, offline listings, and portal associations. It does not copy another person or automatically import legacy device data. Existing device data can only be assigned with the explicit import action. Personal UI streams are bound to their captured account/profile so a late emission cannot refill a different person's screen. Under **Manage**, **Delete profile** opens an explicit across-devices confirmation; cancelling preserves the profile. Deletion retains sync tombstones and does not remove other people's records or physically erase downloaded media.

## Repository and token capabilities

`GET /user` resolves the authenticated numeric ID and login. The application discovers or creates `<login>/niktv-data`, requires private visibility, resolves its actual default branch, and validates the application marker and owner ID. It refuses an unrelated existing repository or an unsupported schema. Bootstrap can resume an interrupted app-created repository with only its initial README. No public fallback exists.

GitHub permissions differ:

| Operation | Classic PAT | Fine-grained PAT |
| --- | --- | --- |
| Authenticate with `GET /user` | A valid token; additional user scopes are unnecessary for numeric ID/login | No additional permissions |
| Create a private repository | `repo` | Administration write or Repository creation write, with resource-owner/repository access that permits creation |
| Read and sync existing data | `repo` | Repository access to `niktv-data`, Contents read and write |

Connection verifies Contents write by creating an unreferenced constant Git blob; it does not alter user files. A fine-grained token restricted to selected existing repositories may require creating the repository first and then granting it access, or replacing the token after creation. SSO/organization policies can require authorization independently of these permissions.

References checked during implementation: [repository creation](https://docs.github.com/en/rest/repos/repos?apiVersion=2026-03-10#create-a-repository-for-the-authenticated-user), [contents access](https://docs.github.com/en/rest/repos/contents?apiVersion=2026-03-10), [authenticated user](https://docs.github.com/en/rest/users/users?apiVersion=2026-03-10#get-the-authenticated-user), and [rate-limit/backoff guidance](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api).

The development provisioning utility `tools/provision-sync-repository.py` reads `g_token` case-insensitively from project/user Gradle properties into memory. It emits only repository metadata. Runtime account authentication never consults this property, `BuildConfig.LOCAL_GITHUB_TOKEN`, a shared backup token, or remote configuration. The APK's local GitHub token field is always empty.

## Version 1 wire format

The repository contains:

```text
README.md
manifest.json
account.json
```

`manifest.json` contains `application: "niktv"`, `schemaVersion: 1`, the numeric `accountId` as a string, and `dataFile: "account.json"`.

`account.json` has the same application/schema/account header and a `records` array. A record contains:

| Field | Meaning |
| --- | --- |
| `accountId`, `profileId` | Account and person ownership; account records use an empty profile ID |
| `collection` | `account`, `profile`, `portals`, `progress`, `history`, `favorites`, `series`, or `preferences` |
| `recordId` | Stable identity within that person and collection |
| `payload` | JSON string with profile metadata, a portable portal association, or a typed preference entry |
| `generation` | Delete/re-add generation; absent in early v1 documents means zero |
| `revision` | Account-local Lamport counter advanced after observing remote revisions |
| `deviceId`, `mutationId` | Random installation ID and unique mutation ID used for deterministic ordering and exact acknowledgments |
| `deleted` | Retained deletion record |

This combines the proposed per-profile files into one atomic GitHub Contents update to reduce request count and prevent torn multi-file uploads. Records still merge individually. Payload entries retain the legacy preference names, rather than renaming serialized portal fields. Unknown JSON properties are tolerated within a supported schema; an unsupported schema version blocks all account-data writes. Account size is capped at 16 MiB, with an actionable error rather than truncation.

## Merge, durability, deletion, and retry

Room stores records, local projections, local portal mappings, and an outbox. A viewing change and its queued mutation commit in the same transaction. Writes are local first. Sync fetches remote data, merges per record, and uploads only when the resulting document differs. A SHA conflict causes a fresh fetch/merge and at most four upload attempts. An interrupted successful upload is safe to retry because mutation IDs and per-record ordering are idempotent.

Ordering is generation, logical revision, device ID, then mutation ID. Ordinary stale edits cannot beat a known deletion merely because their unrelated logical counter is larger. Deletion increments the generation; an explicit re-add after observing deletion advances it again. Deleted user profile UUIDs cannot be edited back into existence. Rewind, restart, completion, and mark-unwatched are explicit progress actions, rather than maximum-position merges. Removing history/favourites produces tombstones scoped to the selected account/person.

Tombstones and the defaults-initialized marker are retained indefinitely in v1. There is no unsafe time-based pruning. Older devices can rejoin and merge retained deletions; an intentional re-add must observe the deletion first. Future compaction requires a new protocol with an epoch/full-resync rule. The 16 MiB cap currently bounds this retention strategy.

Only mutations included in the successful captured snapshot, or proved superseded by a downloaded winner, are acknowledged. Mutations arriving while an upload is in flight remain queued. A process interruption preserves the transaction and queue. One mutex serializes sync, connect, rotation, and disconnect on a device; each HTTP transport captures one token/account. Work requests carry the account ID and are canceled/ignored on account changes.

WorkManager uses connected-network constraints, bounded foreground SHA retries, and exponential background backoff. GitHub Retry-After and primary rate-limit reset times are persisted and honored. Credentials/permission failures require reconnect. Transport errors expose safe status messages, not response bodies, signed URLs, or tokens.

## Content and configuration portability

Logical portal IDs are UUIDs independent of editable labels and credentials. Built-in Stalker/Xtream connections supplied by the existing private-configuration mechanism have deterministic per-account IDs. Custom connections receive random UUIDs. On another device, use **Map portals on this device** on the user profiles screen to explicitly map a synced association to an already configured local connection. Unmapped remote records remain stored until mapping is available.

Content identity uses logical portal ID, catalog type, provider ID, and, for episodes, series identity/season/episode/provider episode ID. New playback keys use `progress-v3`. Titles never match unrelated content. Verified provider TMDB IDs remain metadata; this phase does not automatically merge viewing state across different portals. Legacy title/command-derived progress keys remain local and readable, and are not uploaded as shared identity.

Portal passwords, MAC/serial credentials, authentication sessions, resolved URLs, request headers, and image/provider URLs are excluded from sync payloads. Refresh the provider catalog on a new device to obtain its own current commands and artwork. Local metadata can be retained while merging sanitized remote records. Existing encrypted device/private-configuration and Settings flows supply portal and Sports credentials; user sync never treats a shared configuration token as its account identity. No new token-derived credential encryption key exists. Custom portal credentials are configured locally on each device, so there is no hidden portable-secret key to transfer.

Tokens use a dedicated Android Keystore AES-256-GCM key with authenticated context and app-private preferences. Android application backup is disabled. Tokens are excluded from account JSON and ordinary profile sync. Future full backup exports also omit GitHub tokens, cached `G_TOKEN` values, and the backup passphrase; old imports remain compatible. Full backup/restore is a separate operation: formats 1 and 2 remain importable, format 3 adds account records, per-person local preference rows, and portal mappings. GitHub full-backup uploads require an explicit strong encryption passphrase because full backups include portal credentials. A second device needs that user-supplied backup passphrase to decrypt a full backup; GitHub token rotation does not affect it.

Downloaded files, catalog caches, local portal mappings, active selections, player/hardware settings, and authentication sessions stay local. Offline's visible download list is person-scoped. Playback captures an immutable account/person scope before asynchronous loading; later progress callbacks use that scope even after switching people.

The account can carry a secret-free `configuration-reference` record with the owner/repository/path of its existing private configuration. Another device can use its entered account token to read that reference through the existing encrypted private-configuration mechanism, when its permissions allow access. Otherwise, configure that connection in Settings. Private configuration caches and backup connection settings are isolated by numeric account ID. Replacing an account token rotates a matching local configuration-token copy; independently entered configuration credentials remain independent.

Browsing preferences include pins, collections, hidden sections/layouts, preferred watch providers, channel availability, player browsing columns, content-specific intro timings, and Sports browsing/favourites. These use per-key portable mutations; hardware tuning remains local. The `LocalViewingOwner` boundary and owner-qualified view-model keys carry the same person through browse/search/recommendation/playback/offline destinations, providing the context for future profile policy without applying restrictions now.

## Migration

An empty account with pre-existing local viewing data imports that data into deterministic Family, while Kids starts empty. The Room migration marker makes the import idempotent; legacy serialized portal fields and local credentials are preserved. When remote profiles already exist, local viewing data is not guessed into one of them: the chooser offers an explicit confirmed import into a selected person. A full backup with another account's user records is rejected before restoring those records.

## Validation and limits

See `docs/verification/user-profiles-github-sync.md` for commands and observed results. No release is published by this implementation. Live-provider playback and two separately authenticated production devices require account-specific token/configuration supplied interactively; fixture and local replica tests cover these data paths without embedding development authentication in APKs.
