# Cricket highlights verification — October 28, 2026

The feature adds direct Cricbuzz API signing and **Sports → Highlights** for Canadian featured videos. The API signing configuration is provisioned at runtime; a separate provider endpoint is optional. See [cricket-highlights.md](../cricket-highlights.md).

## Current implementation

- Fresh API signatures, server-reported Canadian geo checking and bounded Home protobuf parsing.
- Featured artwork/titles/durations/search, remote/touch navigation, explicit selection and fresh catalogue resolution by stable ID before every play/retry.
- Direct playback resolution uses the full signed Home URL. The extra NikTV account-verification request, account credentials, entitlement flag and subscription-state checks are excluded from this branch.
- Full/promo separation, same-Kaltura-entry rejection, VOD and duration checks remain. Media-server rejection propagates as a failure; API signatures are not forwarded to media requests.
- Runtime API configuration is encrypted with Android Keystore. No signing secret or captured media session is included in source, tests or APK assets.

## Validation

The normal APK build and **76/76 focused JVM tests passed** after removing the added account layer. The current tests include independent HMAC vectors, Canadian selection, bounded clock correction, fresh signed Home URL resolution without account requests, credential isolation, unchanged media rejection, missing full URLs and promo checks. The build output is recorded in `D:/workspace/android/cricbuzz-analysis/niktv-cricbuzz-branch-build.log`. The updated isolated Android test APK also compiled and packaged successfully; it was not run on Samsung in this branch.

Earlier live verification on Samsung SM-S948W confirmed that the direct Android client loaded a nonempty Canadian catalogue, that local configuration was encrypted, and that the normal app displayed the real featured grid. Two UI fixture tests and the live catalogue test passed together. The normal app's existing profile/player DataStore hashes were unchanged by installation. Those device results predate removal of the extra account layer; they do not verify full playback in this branch.

Full highlights played from Home in the supplied reference APK, and independent requests to the returned full URLs retrieved clear HLS playlists. The captured media requests used the signed Kaltura URL without separate Cricbuzz/account headers. The original two assets contained no HLS encryption tags and no observed DRM license exchange. These are observations for those assets, not a guarantee for every video or future session.

**Full-highlight decoding on Samsung after removing the extra NikTV account layer has not been tested.** No account login or token refresh flow is included.

## Local evidence outside Git

- `niktv-cricbuzz-branch-build.log`: current normal APK build and focused JVM checks.
- `niktv-cricbuzz-branch-android-build.log`: current isolated Android test build.
- `niktv-cricbuzz-direct-samsung-tests.log`: earlier `OK (3 tests)` result for live catalogue/UI verification.
- `niktv-direct-canadian-catalogue.png`: earlier real Canadian grid displayed by normal NikTV.
- `niktv-direct-stores-before.txt`, `niktv-direct-stores-after.txt`: unchanged normal profile/player-store hashes.

Evidence files, runtime signing configuration, captured sessions and APKs remain outside the repository.
