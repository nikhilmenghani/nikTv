# Playback retry verification

Checkout base: b3953e338f5820518858d7038377246dbbb601a1.

Connected and authorized ADB devices:
- Xiaomi 21051182G, API 36 (USB): playback regression tests passed.
- POCO F1, API 28 (USB): secure lock; no UI regression performed.
- Amazon Fire TV AFTKRT, API 30 (network): earlier fixture tests failed; not counted as passing.

Checks:
- `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PskipLocalGithubToken=true --offline`: passed. 230 unit tests, no failures; lint report contains warnings, no errors.
- `gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true --offline`: passed.
- `adb -s <tablet> shell am instrument -w -e class com.nikhil.niktv.ui.PlaybackRegressionTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner`: OK (2 tests).

Device tests mount production PlayerScreen/VlcPlayerScreen in an isolated `com.nikhil.niktv.regression` package. A missing synthetic local WAV produces an error; making it available and clicking the real Retry action recovers with the identical URI. Assertions verify a new manual attempt, resume at 2500 ms, advancing playback, cleared fatal dialog, and no automatic authorization callback. VLC begins with an already-used automatic authorization attempt. No provider credentials or personal app data are used. The harness application bypasses background remote credential initialization.

Limits: fixture callbacks model resolved playback replacement; real provider reauthentication and a physical Cast receiver are not exercised. The regression application is opt-in; instrumentation refuses the ordinary application ID. Unit tests use the normal debug package because updater tests intentionally reject an unknown update channel.

Earlier failures were not counted as passes: an initial lint analyzer crash resolved with fresh analysis; legacy JUnit3 adapter failed on API 36 before tests ran; tablet lock/sleep prevented UI access; Fire TV progress assertions failed; malformed Java file URI was rejected by VLC. Final fixtures use Android file URIs and keep only the test activity awake.

Unrelated user edit in ModernTileBrowseScreen.kt remains outside this commit. No push, release, uninstall, or data clear was performed.
