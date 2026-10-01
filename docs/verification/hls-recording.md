# HLS recording verification

The recorder validates complete manifests before appending segments. It rejects byte ranges, encryption, initialization maps/fragments, partial segment formats and malformed segment durations. METHOD=NONE remains supported. Each segment's EXTINF duration drives recorded-media accounting and pacing; TARGETDURATION only controls playlist polling. Unsupported manifests delete the recorder's own output instead of publishing a corrupt pending file.

Normal checks: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PskipLocalGithubToken=true --offline` passed. 237 unit tests, no failures; lint has no errors.

Device: authorized Xiaomi 21051182G, API 36. Isolated package build: `gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true --offline`. Instrumentation: `adb -s <tablet> shell am instrument -w -e class com.nikhil.niktv.data.HlsRecordingRegressionTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner`: OK (3 tests).

Two tests invoke the actual foreground recording service against a loopback HTTP server. Byte-range and encrypted manifests produce clear rejection errors, zero appended segment bytes, an inactive recorder, and no remaining uniquely named synthetic MediaStore output. A third checks actual EXTINF duration parsing on Android. No provider credentials or personal recording files are read or deleted. Cleanup selects only a UUID-named test artifact.

Limits: byte ranges, encrypted and fragmented playlists are intentionally unsupported; no real provider or long-running timed recording was used. Public-output cleanup integration tests target API 29+ and were executed on API 36.
