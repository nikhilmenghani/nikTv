# Cricket highlights verification — October 28, 2026

The feature adds direct Cricbuzz API signing and **Sports → Highlights** for Canadian featured videos. The API signing configuration is provisioned at runtime; a separate provider endpoint is optional. See [cricket-highlights.md](../cricket-highlights.md).

## Current implementation

- Fresh API signatures, server-reported Canadian geo checking and bounded Home protobuf parsing.
- Featured artwork/titles/durations/search, remote/touch navigation, explicit selection and fresh catalogue resolution by stable ID before every play/retry.
- Direct playback resolution uses the full signed Home URL. The extra NikTV account-verification request, account credentials, entitlement flag and subscription-state checks are excluded from this branch.
- Full/promo separation, same-Kaltura-entry rejection, VOD and duration checks remain. Media-server rejection propagates as a failure; API signatures are not forwarded to media requests.
- Runtime API configuration is encrypted with Android Keystore. No signing secret or captured media session is included in source, tests or APK assets.

## Validation

The initial port's normal APK build and **76/76 focused JVM tests passed** after removing the added account layer. Those tests include independent HMAC vectors, Canadian selection, bounded clock correction, fresh signed Home URL resolution without account requests, credential isolation, unchanged media rejection, missing full URLs and promo checks. The build output is recorded in `D:/workspace/android/cricbuzz-analysis/niktv-cricbuzz-branch-build.log`. The updated isolated Android test APK also compiled and packaged successfully; it was not run on Samsung in this branch.

Earlier live verification on Samsung SM-S948W confirmed that the direct Android client loaded a nonempty Canadian catalogue, that local configuration was encrypted, and that the normal app displayed the real featured grid. Two UI fixture tests and the live catalogue test passed together. The normal app's existing profile/player DataStore hashes were unchanged by installation. Those earlier fixture results predate removal of the extra account layer. The additional normal-app playback verification below covers the installed branch without it.

Full highlights played from Home in the supplied reference APK, and independent requests to the returned full URLs retrieved clear HLS playlists. The captured media requests used the signed Kaltura URL without separate Cricbuzz/account headers. The original two assets contained no HLS encryption tags and no observed DRM license exchange. These are observations for those assets, not a guarantee for every video or future session.

## Samsung full-highlight playback

Installed the APK built from `b34eb7d8` on Samsung SM-S948W, updating `com.nikhil.niktv.debug` without clearing its data. Opened Sports → Highlights and selected videos from the live Canadian Home catalogue.

| Highlight | Actual player duration | Playback evidence |
|---|---|---|
| Short Highlights: South Africa vs Australia, Day 2 — 3rd Test | 24:16 | Actual match footage advanced past 2:46, beyond the known 76-second promo. |
| Highlights: New Zealand vs India — 3rd T20I | 24:27 | Seeked to 11:00, resumed, and observed playback advance to 11:25 with match footage. |

The user independently confirmed full-video playback. The checks sampled actual playback and seeking; neither video was watched continuously to the end. No separate account credentials or account-verification request were used. The installed build's existing profile/player DataStore hashes were unchanged. No account login or token refresh flow is included.

The current Canadian Home response contained 10 unique video items: 3 Highlights, 6 Top Knocks and 1 Top Spells. A recursive check found the same 3 highlights, matching NikTV's displayed tiles. At that verification stage, the screen browsed only Home's featured highlights. The subsequent expansion below adds the wider listings and pagination.

## Latest Videos, series and row layout — October 29, 2026

- Traced and fetched the Latest Videos index, timestamp pagination, collection discovery, Latest Videos collection, India–New Zealand collection and ODI Tri-Series collection. Their full/explicitly-free playback URLs are retained separately from previews.
- The expanded backend and image changes passed **83 focused JVM tests** before the final layout-only pass. The live expanded catalogue/older-page/series checks plus three UI checks also passed on Samsung earlier in the expansion work (`OK (4 tests)`). These were not repeated for the final UI pass.
- The final landing page follows the captured Videos screen's stacked category rows with horizontally navigable tiles. View all opens a category grid with Load more and Back.
- As requested, final testing was limited to **two basic UI tests on the Xiaomi tablet (21051182G)**: horizontal/vertical remote navigation with explicit playback selection, and View all → Load more → Back. Both passed in 5.816 seconds. No backend suite was rerun during that layout pass.
- Verified the image CDN returned 90×68 for the original unsized fallback, 640×360 for a sized request and 1280×720 for the selected replacement. The larger URL was installed and visually checked on Samsung before the horizontal-row redesign.
- After the two tablet checks passed, the final normal APK was installed on Samsung without clearing application data. No further Samsung tests were run for that final layout.
- The normal app had already opened the series-specific full New Zealand–India video and reported 45:51 during expansion verification; no additional playback/decoder tests were run for the layout-only pass.

## Local evidence outside Git

- `niktv-video-rows-tablet-tests.log`: final `OK (2 tests)` tablet UI result.
- `niktv-video-rows-tablet-build.log`, `niktv-video-rows-samsung-build.log`: final row-layout builds.
- `niktv-expanded-videos-sharp-build.log`: 83-test expanded-backend/thumbnail result.
- `niktv-expanded-videos-samsung-tests.log`: earlier `OK (4 tests)` live expansion/UI checks.
- `thumbnail-dimensions.json`: measured CDN thumbnail sizes.
- `niktv-cricbuzz-branch-build.log`: current normal APK build and focused JVM checks.
- `niktv-samsung-playback-build.log`: successful build used for the normal-app Samsung playback verification.
- `niktv-highlight-controls-private.xml`: South Africa–Australia player at 2:46 / 24:16.
- `niktv-nz-after-seek-private.xml`, `niktv-nz-advanced-private.xml`: New Zealand–India at 11:00 and 11:25 / 24:27.
- `niktv-nz-full-after-seek.png`, `niktv-nz-full-verified.png`: actual decoded match footage.
- `current-home-catalogue-counts.json`: current featured video counts, types, titles and durations; no media URLs.
- `full-playback-stores-before.txt`, `full-playback-stores-after.txt`: unchanged profile/player DataStore hashes for this installation/test.
- `niktv-cricbuzz-branch-android-build.log`: current isolated Android test build.
- `niktv-cricbuzz-direct-samsung-tests.log`: earlier `OK (3 tests)` result for live catalogue/UI verification.
- `niktv-direct-canadian-catalogue.png`: earlier real Canadian grid displayed by normal NikTV.
- `niktv-direct-stores-before.txt`, `niktv-direct-stores-after.txt`: unchanged normal profile/player-store hashes.

Evidence files, runtime signing configuration, captured sessions and APKs remain outside the repository.
