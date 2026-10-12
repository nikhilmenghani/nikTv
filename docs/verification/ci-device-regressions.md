# CI and full device regression verification

The Android validation workflow runs unit tests, lint and debug assembly on pull requests and manual runs. Ordinary pushes do not trigger it. It has read-only repository permissions and does not publish artifacts. Lint uses abortOnError=true. The local and device verification results below describe the original verification at introduction; validation has subsequently also passed on GitHub.

## Strict lint correction

Earlier verification notes incorrectly described the legacy lint results as having no errors. With abortOnError=false the lint task completed, but SARIF results inherited severity from rule defaults; counting only explicit result.level missed errors. Enabling the strict gate exposed 56 errors (46 Media3 opt-in findings, 6 non-observed artwork StateFlow reads, 3 restricted key-dispatch API findings, and 1 PiP API-level finding). They were corrected directly with explicit opt-ins, collected artwork state, a delegating public Window.Callback for transport keys, and an explicit API guard. No lint detector was disabled and no baseline was added. Earlier notes now identify their lint run as non-gating.

An independent, earlier lint FIR analyzer crash (`FirDeclaration was not found for KtFunctionLiteral`) was resolved by rerunning fresh analysis: `gradlew.bat :app:lintDebug -PregressionBuild=true -PskipLocalGithubToken=true --offline --rerun-tasks`. Its underlying tool cause was not established. This is separate from the real lint findings above.

## Final local checks

`gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PskipLocalGithubToken=true --offline`: final strict validation passed. 237 unit tests, no failures. Lint XML is the severity source of truth; final report has zero Error/Fatal findings.

`tools/test-device-regression.ps1 -Serial 6889f3db`: passed on authorized Xiaomi 21051182G/API 36, `OK (12 tests)` in 39.383 seconds. The script builds the separate regression package, validates both APK identities, installs with -r, and requires a real successful instrumentation summary. It neither clears nor uninstalls user apps.

Coverage:
- Production Media3 and VLC same-URI retry, resume retention, actual progressing playback, and fatal dialog clearing.
- Main responsiveness during a blocked download query, deduplication, and real missing WorkManager/index reads off Main.
- Actual HLS foreground-service rejection and cleanup of UUID-named synthetic output; EXTINF accounting.
- Previous/Next playback error actions and transport keys through the production window callback.
- Actual display rotation, activity recreation and advancing playback after restoration.
- Existing parental PIN dialog rejection/unlock/cancel and isolated local PIN/relock preferences. Recovery and shared override behavior were not changed, as requested by the user.

Android 16 ignores app-requested orientation on large displays, so the rotation test uses actual display rotation and restores the original user_rotation and accelerometer_rotation values afterward. The initial requestedOrientation-only test failed and was not counted as a pass. Reference: https://developer.android.com/about/versions/16/behavior-changes-16#adaptive-layouts

## Remaining coverage limits

Fire TV AFTKRT/API 30 and POCO F1/API 28 were authorized ADB devices, but no final passing regression suite was obtained there. The POCO was securely locked. Early Fire TV fixture/progress attempts failed and were not reported as passes; malformed URI/awake-state fixture issues were corrected and validated on the tablet. No claim is made that the Fire TV-specific gap is resolved.

No real provider reauthentication, physical Cast receiver, long-duration provider recording, or remote CI execution was tested. Fixtures are synthetic/local and the test application bypasses remote credentials initialization. The user's unrelated ModernTileBrowseScreen.kt navigation edit remains outside all commits. No push, PR, release or deployment was performed.
