# User profile / GitHub sync validation — 2026-11-04

## 0.5.4 follow-up acceptance

Implemented direct preconfigured portal tiles (Stalker/Xtream/Sports only), person-screen
device-local Downloads access, explicit cached-profile recovery during GitHub reconnect,
device-only commit provenance, profile-scoped timers, and live imported intro refresh.
Tablet Profile and Portals use separate right-side glass surfaces; phone chrome is unchanged.
Downloaded files/inventory/paths and MediaStore recording ownership are never synced.
New recordings capture their owner; older unassigned recordings require explicit local
assignment and cannot be taken from another existing owner. Downloads Back returns to
the person chooser and does not replace their last content portal.

Validation: 110 focused JVM cases passed in 13 suites (offline cancellation/isolation,
timer replication/ranges/migration, intro identity/live refresh/draft preservation,
device metadata sanitization/PUT payloads, recording ownership, account startup,
transaction dispatch, merge/transport and old backup import). Another 30 existing
watching/Sports/recording cases passed. Debug APK and instrumentation builds and lint
passed with no rules disabled. Both tablet and Poco passed all18 updated account,
profile-management, simplified-portal, session-free offline and header-navigation cases.

The first instrumentation compile found old WatchingPreferences.save(Context, ...) fixture
calls after adding the captured-preferences writer. A compatibility overload preserves
those callers; the validated UI uses captured owner preferences for late-write isolation.

## Compact profile tiles and direct Home follow-up

Checkpoint commit `cdf7c1f5` preserves the completed account/profile/sync work and
restored `ac14cb11` discovery appearance. A pre-commit audit covered 110 changed files
and found no actual Gradle development token or GitHub token-pattern matches.

The follow-up replaces profile cards with compact avatar tiles, highlights the selected
UUID, and provides touch hold, remote hold/Menu, and accessibility management routes.
Manage profile opens a dedicated identity editor; deletion remains explicitly confirmed.
Current profiles plus Add fit together in ordinary phone/tablet layouts. Existing hero
artwork and fades are preserved. Root `AGENTS.md` records the existing compact NikTV
visual conventions for subsequent UI work.

Remembered person and destination are local-only, account-scoped selections. A fresh
profile has no remembered portal. Startup validates the remembered UUID and associated
portal before reusing a cached session for Home. Missing/deleted profiles return to
the tile chooser; missing portals return to portal selection. Sports and Offline remain
destinations. Home exposes distinct compact profile and portal controls.

The user narrowed automatic sync to launch/foreground plus explicit manual sync.
Home Settings includes Sync now, quiet progress, pending-local-change status, and errors.
Ordinary viewing/preference/profile writes remain durable locally without starting
network work. The two awaited playback-start sync operations were removed. Legacy
periodic/playback workers are cancelled or ignored; only transient failures of an
explicitly started sync arrange network-constrained retries.

First validation pass: 60 JVM tests passed across scoped storage/preferences/dispatch,
cached account startup, dashboard startup, sync engine, and GitHub transport. Regression
debug and test APK builds and lint passed. Updated fixture UI checks passed **10/10 on
tablet and 10/10 on Poco**: account/setup 5, profile management 3, and separate Home
viewer/portal routes 2. Fixture callbacks never edited real profiles.

Final performance pass: **64 JVM tests passed**, including 10,000 cached preference
read/contains/getAll iterations while a storage transaction is deliberately blocked,
immediate cache visibility after external merges, stale-invalidation resistance,
deleted-profile cache invalidation, and cached screen recreation while the network
mutex is occupied. Preference snapshots warm on IO before composing a viewer. Explicit
local preference commit retains its durable synchronous contract; no universal frame
timing or zero-jank claim is made.

The final lint pass initially hit an internal ExperimentalDetector/Kotlin FIR analysis
crash while UI sources were changing. Rerunning with frozen sources passed in 1m32s;
no lint rule was disabled. The pressed/held effect now uses only the avatar's rounded
shape, with a subtle shadow and contained tint; label backgrounds remain clear.
Remote hold release uses event duration so cancelling management does not swallow
the next short OK press. Add is never marked as the selected person.

Integrated normal-app checks found and corrected two issues beyond isolated fixtures:
the Home host still wired the portal callback to the people chooser, and leaving
Settings cancelled its UI-owned manual sync coroutine. The host now uses the portal
switcher; launch/manual requests run in the manager's IO SupervisorJob scope, capture
the account before enqueueing, and survive navigation or activity recreation. Tests
verify caller cancellation does not cancel queued sync and account switches reject
stale queued requests. Retry workers still require their explicit persisted retry flag.

Final frozen acceptance: **72 tests in 8 JVM suites passed**, normal debug APK built,
and `lintDebug` passed in 1m36s with no disabled rules. This includes old-backup restore,
cache consistency, migration/isolation, startup, merge/transport, and UI coroutine
ownership. Fixture timing was corrected to await dialog dismissal and focused-tile
restoration before injecting the next remote OK; creation/deletion and hold/cancel/
first-OK cases pass on both devices. Selected tiles now expose the useful accessibility
state description `Current profile`, independently of platform selected/checked flags.

The final live Poco run restored its actual last viewer, Nikhil, directly to Stalker
Home. Its portal modal listed only associated configured connections plus Sports and
Offline; Configure portals showed all four Stalker/Xtream/Sports/Offline destinations
without associating anything automatically. Home Options → Settings opened Settings
directly. Sync now followed immediately by Back returned to Home responsively; reopening
Settings showed Up to date with no interruption error. Compact grid and identity-editor
screenshots were reviewed without saving or deleting real profiles.

Samsung received the final debug update using install -r, preserving data. Its display
was locked during the final visual check, so the new flow's live UI acceptance uses
tablet and Poco. No release or push was performed.

Final tablet live acceptance also passed direct Home Settings, manual sync followed by
Back, and subsequent Up to date status. Separate avatar/portal routes and all configured
destinations were checked. The final TV selected-profile case passed after the fixture
filtered accessibility roots to its target package and awaited fresh semantics; an
old foreground normal-app Family tile had been eligible for the unfiltered lookup.
Management, held OK/cancel/first short OK, and navigation fixtures passed on both devices.
Normal apps were left with authentication and real profiles preserved.

Poco's final selected-state case also passed (2.29s). Combined final device coverage is
10 distinct applicable account/management/header cases per device, all passing. Final
Poco screenshots include `poco-normal-home-final.png` and
`poco-normal-home-settings-sync-complete.png` under the local validation directory.

## Baseline and repository

- Inspected `D:\AGENTS.md`, workspace/Android/repository ancestors, and repository descendants; no applicable AGENTS instructions were present.
- Branch: `main`. Working tree was clean before implementation. Existing portal DataStore, startup/navigation, playback writers, GitHub snapshot backup, private configuration, and Gradle dependencies were inspected. Room and WorkManager were already dependencies.
- Provisioned [private niktv-data](https://github.com/nikhilmenghani/niktv-data) using the Gradle property in memory. `GET /user` supplied numeric account `3968443`; actual default branch is `main`. README and schema-1 manifest were initialized. A final repeat verified private visibility and the application/account marker without modifying existing contents.
- No release was published. Fixture device tests use the disposable `com.nikhil.niktv.regression` package; its updater explicitly disables network checks, downloads, installation, and shared-download cleanup. Later authenticated acceptance and user-requested installation updated the normal debug app while preserving its data, as described below.

## Build and JVM checks

Passed `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:compileReleaseKotlin`, using:

```powershell
-PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --offline --console=plain
```

The release task compiles release code; it does not publish a release. Regression APKs are under `app/build-regression/outputs/apk/`.

Expanded targeted JVM run: **86 tests passed** across:

| Suite | Passed |
| --- | ---: |
| AccountSyncEngineTest | 7 |
| GitHubSyncTransportTest | 7 |
| UserSyncStoreTest | 13 |
| UserProfilePreferencesTest | 4 |
| RemoteConfigurationAccountIsolationTest | 2 |
| ViewingIdentityTest | 6 |
| DeviceSetupBackupTest | 6 |
| CatalogBackupFormatTest | 3 |
| ProfileStoreDispatchTest | 8 |
| HistoryRecommendationsTest | 4 |
| DiscoveryProgressLookupTest | 5 |
| SeriesDetailPolicyTest | 18 |
| UpdateChannelTest | 3 |

An additional **RegressionUpdaterSafetyTest passed (1)** after the final regression-startup fix.

Coverage includes simultaneous/default initialization and retained deletions; permanent profile type; explicit legacy assignment; v1/v2 backup restore both with and without a selected person; independent account/person state and late captured writes; durable per-key preference writes; two local replicas retaining each other's history; SHA conflict fetch/merge/retry and bounded failure; exact mutation acknowledgment during concurrent local writes; retry after an uploaded-but-unacknowledged commit; stale-device deletion generations; unmatched portal mapping and resume reprojection; secret scrubbing; legacy progress remaining local; movie/episode identity; rewind/restart/completion; invalid tokens; distinct creation/Contents permissions; unrelated/public repositories and unsupported schemas refusing writes.

Run targeted suites by attaching `--tests <fully-qualified-class>` immediately after `:app:testDebugUnitTest`.

## Devices

Separate agents ran tablet/TV and phone validation in parallel, explicitly selecting each USB serial:

| Device | Serial | Scope |
| --- | --- | --- |
| Xiaomi tablet / nabu, 21051182G | `6889f3db` | Account, profiles, portal cards, TV-style D-pad/focus and Settings |
| Poco X3 Pro / bhima-vayu, M2102J20SI | `a3838893` | Account, profiles, portal cards, responsive focus, portrait/landscape mobile Settings |

Instrumentation command shape:

```powershell
adb -s <serial> install -r app/build-regression/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s <serial> install -r app/build-regression/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e class <comma-separated-test-classes> com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
```

Final results: **tablet 12/12 passed** (20.579 seconds), **Poco 14/14 passed** (44.585 seconds), run in parallel by separate agents. Tablet suites: AccountProfilesDeviceTest (5), EmptyProfileDashboardDeviceTest (4), ProfileChooserNavigationDeviceTest (1), ProfileSettingsDpadDeviceTest (2). Poco replaces the last suite with MobileSettingsDeviceTest (4).

These checks exercise blank masked input, failure/reconnect states, initial token gate and access to Settings before authentication, Family/Kids chooser, Stalker/Xtream/Sports/Offline cards, no portal “Add profile” action, portal editor cancellation, and remote focus navigation. Screenshots contain no entered real tokens. Tablet actual MainActivity cold launch succeeded (1.825 seconds), showed GitHub account/blank token before profiles/portals, opened Settings before authentication, and Back restored the account screen. Visual inspection confirmed account-screen layout and the TV fixture's profile-card contrast/focus.

Local screenshot artifacts are in `D:\workspace\niktv-validation`: `tablet-main-startup.png`, `tablet-account-settings.png`, `tablet-account-gate.png`, and `tablet-user-profiles-tv.png`. The profile chooser screenshot uses a token-free fixture; the startup and Settings screenshots use the actual activity.

Poco actual MainActivity cold launch also succeeded (1.542 seconds): empty token/account first, Connect disabled with empty input, Settings opened by touch, and Android Back restored the token screen. Visually verified artifacts: `D:\workspace\niktv-profile-validation\poco-fresh-token.png`, `poco-account-settings.png`, and `poco-account-return.png`. No runtime tokens were entered on either device.

## Checks with remaining failures or limits

- Full `:app:testDebugUnitTest` was attempted. It reported failures in 12 `VidSparkSourceResolverTest` cases and `AlternateSourceViewModelTest.qualitySelectionReadsTheCurrentPlayerPositionAfterTheDialogWaitAndKeepsALaterPause`, then stalled at a DataStore write in `lookupAndSelectionPlayWithoutAPortalSessionAndPreserveProviderDataAndMappings`. The stalled run was stopped. The full suite is **not** claimed passing; the expanded targeted suites above pass. The source-resolver files were not changed. Existing alternate-source reader calls were confirmed in HEAD, but a separate complete baseline run was not performed.
- The initial `:app:lintDebug` run failed with **21 existing errors**. Restoring the user's tested baseline through `ac14cb11` also restored its lint fixes; the current combined build passes lint without suppressions or a baseline. Current report: `app/build/reports/lint-results-debug.html`.
- Initial validation did not provision real device authentication. The user subsequently explicitly authorized `g_token` on tablet and Poco; both connected and completed initial sync. The live six-phase sync acceptance below now covers actual GitHub round trips. Streaming playback and actual token revocation/rotation or switching to a second real GitHub account remain outside this acceptance pass.
- Kids filtering is intentionally absent. Custom portal credentials require device-local setup (or the existing private configuration/full-backup passphrase workflow); explicit logical mapping is provided. Cross-portal viewing merges are intentionally absent. Tombstones are retained indefinitely; 16 MiB account limit fails safely instead of pruning.

## Credential audit

The development token was read programmatically and compared in memory against changed/new files and the uncompressed contents of both generated regression APKs. No occurrences were found. Both debug/release local-token BuildConfig fields remain empty. Token/portal credential exclusion has dedicated passing tests; full GitHub backup uploads require encryption, and future full backups omit GitHub tokens and the backup passphrase.

See [protocol and permission documentation](../user-profiles-github-sync.md).

## Samsung content-navigation ANR fix

Samsung SM-S948W reported an input timeout on November 4 at 17:54:19. The main-thread
stack was blocked in `UserProfilePreferences.Scoped.Editor.commit`, called by
`DiscoveryViewModel.bind`. A storage coroutine could hold `editMutex` and require
a main-thread continuation to finish, while the main thread synchronously waited
for another preference edit using that mutex.

All shared edit-lock operations now acquire, execute, and release on Dispatchers.IO,
including the DataStore projection adapter and SharedPreferences bridge. Local
preferences and their durable outbox still commit together. A regression test
reproduces competing UI/IO edits: it times out with the previous lock behavior and
passes with the fix. All 26 preference, scoped-store, and DataStore dispatch tests pass.

Installed the corrected debug APK on Samsung without clearing data. Verified the
existing Family profile, Stalker connection, Home, Movies, Series, and return to Home
respond to touch. No ANR dialog appeared; Android's last-ANR report was 17:56:20,
before the corrected APK was installed. Its repeated pre-fix reports show the same
blocking preference-commit stack.

## Authenticated tablet/Poco acceptance and ac14cb11 baseline recovery

The user's recent Poco tests were built from `D:\workspace\android\nikTv-optimized-series`,
while this task began at `791abc71` in `nikTv`. This explains the missing hero changes:
the new profile build used an older source baseline. No fade-specific revert occurred.
The user identified `ac14cb11` as the desired reference. Its ancestor changes through
that commit have now been merged into the current working tree, preserving account
ownership, profile-scoped preference stores, v3 identities, viewing actions, UI redesigns,
and the ANR fix. No release/version changes were imported or published.

Restored behavior includes eased hero edge feathering, scene/glass alignment, compact
phone edge arrows, Continue Watching hero artwork, optimized Series resume/focus,
and Providers logo tiles after Home content categories. The profile button now shows
the selected person rather than the legacy portal initial.

Both development apps use the explicitly supplied token, protected by the normal
Android Keystore vault. Provisioning is instrumentation-only and opt-in; the token
is never embedded or provided as a command/instrumentation argument. Tablet used an
ADB-stdin private input removed before connection. Poco's SELinux blocks run-as, so
it used a one-shot loopback socket through USB ADB forwarding; the token stayed in
memory and forwarding was removed. No security settings were disabled.

Real private-GitHub acceptance: **6/6 phases passed**, using one isolated temporary
`Sync validation` UUID, with other people's record mutation IDs checked unchanged:

1. Tablet created the fixture, movie progress 60 seconds and episode progress 90 seconds, and uploaded history A.
2. Poco fetched it, explicitly mapped the same logical portal to a different local configuration, verified movie/episode resume, rewound the movie to 30 seconds and uploaded history B.
3. The stale tablet wrote history C before fetching Poco's edit, then synced and verified A+B+C and the 30-second rewind; episode progress remained intact.
4. Poco verified the merged history and uploaded explicit mark-unwatched (zero position); episode progress remained intact.
5. Tablet verified mark-unwatched and deleted only the fixture profile across devices.
6. Poco fetched the tombstone and confirmed the fixture and its viewing data did not reappear.

The fixture was cleaned up; no real profile was deleted. Local result artifact:
`D:\workspace\niktv-profile-validation\authenticated-sync-results.json`.

Combined-build validation: **129 JVM tests in 15 suites passed**, debug and instrumentation
APKs built, release Kotlin compilation passed, and `:app:lintDebug` passed. Authenticated
manual checks on both devices reached Family → Stalker → Home/Movies/Series without
an ANR; preserved viewing history was visible. Tablet Providers placement was visually
verified below content categories, with Netflix/Prime/Apple tiles. Poco manual Next
advanced the restored edge carousel from 6/10 to 7/10.

Credential audit of 109 changed/new files, uncompressed app/test APK contents, and
the live private `account.json` found zero occurrences of the development token and
zero nonempty forbidden credential fields in remote payloads. The repository remains
private. Screenshots contain no token input.

## Fresh profiles and accessible deletion

The chooser now exposes **Manage → Delete profile** directly, with the existing explicit
across-devices confirmation and Cancel action. Creating a profile clears the prior
selection and never invokes legacy import. The editor explains that the new person
starts empty. Personal ViewModel streams capture their account/profile, discard late
deliveries for another person, and clear cached personal state on a viewer change.
Legacy portal-key normalization also captures the initiating person before storage reads.

Two new storage tests populate every personal collection before creating a new person,
verify empty history/progress/favorites/series/recommendations/preferences/searches/offline
listings/portal associations, and verify the original person remains intact. Deletion
tests cover other-person preservation, late callbacks, stale remote resurrection, and
a same-name replacement with a different UUID and empty data.

Latest validation: **37 JVM tests passed** across scoped storage, preferences, dispatch,
and dashboard navigation; debug/instrumentation builds and `lintDebug` passed. Real UI
creation and deletion tests passed **2/2 on tablet and 2/2 on Poco**. Earlier automated
tablet coverage passed 16 other navigation/provider cases; Poco passed 19 other applicable
cases. Each device skipped the single orientation test intended for the other form factor.
The corrected Poco favorite-action test also passed; its former failure was a fixture
expectation for a tablet label rather than the phone label.

The final normal debug APK was installed with `adb install -r` on tablet at 18:32:11
and Poco at 18:32:15 on November 4, preserving app data. Samsung was absent from ADB
at the final device check; its latest update remains pending reconnection. No release
was published.

Final normal-app visual checks confirmed authenticated profiles were preserved and
Manage exposes the red Delete profile action on both devices. Screenshots:
`D:\workspace\niktv-validation\tablet-profile-manage-delete-final.png` and
`D:\workspace\niktv-profile-validation\poco-normal-manage-delete-final.png`.
