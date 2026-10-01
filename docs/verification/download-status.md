# Download status verification

Status queries now run through an IO snapshot function. One ViewModel poll publishes shared observable snapshots, including completed/failed transitions, and all Compose status consumers read those snapshots. Download-action status checks also dispatch to IO. Duplicate entries query once per snapshot, and removed entries disappear.

Normal checks: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug -PskipLocalGithubToken=true --offline` passed (232 unit tests, no failures; lint has no errors).

Device: authorized Xiaomi 21051182G, API 36, separate regression package. `gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PregressionBuild=true -PskipLocalGithubToken=true --offline`, then `adb -s <tablet> shell am instrument -w -e class com.nikhil.niktv.ui.DownloadSnapshotRegressionTest,com.nikhil.niktv.ui.PlaybackRegressionTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner`: OK (4 tests).

Device assertions: a blocked worker query leaves Android Main responsive; duplicate entries query once; terminal state reaches the snapshot; actual WorkManager/index queries for a random synthetic request execute off Main and report MISSING. Both production-player retry regressions remain passing. No real download, provider login, user-data clear, or uninstall is involved.

The user's existing ModernTileBrowseScreen.kt changes were preserved. Only the download-state hunk was staged from that file. Polling remains at two seconds on IO; provider reauthentication and real download completion are not exercised by these synthetic tests.
