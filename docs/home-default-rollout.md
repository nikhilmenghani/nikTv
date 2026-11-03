# Optimized Home rollout — 0.5.2

On November 3, 2026 the user approved promoting Home 2 and performing the remaining acceptance checks themselves. This is an explicit rollout decision, not a claim that the earlier phone/long-session or SmartTube-comparison gates have passed.

- Home entry, authenticated profile opening, cached profile switching and returns to Home now select the existing optimized Home 2 experience.
- The primary navigation shows one **Home** tab. The options menu on phone, tablet and TV includes **Previous Home**, which opens the existing original presenter. Selecting Home again returns to the optimized presenter.
- Existing Home 2 presenter preferences, profile-scoped sections, history, favorites and original Home configuration are retained. Movies, Series, Live TV and My List keep their existing destinations.
- The Home display-options menu retains the presentation controls. The separate Home 2 tab/trial menu entry is removed; internal aliases remain compatible.
- Includes the [measured progress-strip optimization](home2-bulk-loading-performance.md). No player or alternate-source changes are part of this rollout.

Validation: optimized Dev APK build passes; 236 applicable discovery, startup and navigation unit tests pass with zero failures/errors. Source credential scan and whitespace checks pass. The immediately preceding native-renderer change passed 21 Fire TV functional tests; that suite was not rerun for the navigation-label promotion. The user will test this migration on devices. Existing lint remains failed with 22 errors, 359 warnings and three hints, as recorded in the loading report.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.nikhil.niktv.discovery.*' --tests 'com.nikhil.niktv.ui.ProfileDashboardStartupTest' --tests 'com.nikhil.niktv.ui.TileNavigationMotionTest' --tests 'com.nikhil.niktv.ui.TileMenuPositionTest' -PperformanceBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true --console=plain
python tools/check-source-secrets.py
git diff --check
```

Install optimized Dev on connected Samsung, Xiaomi Pad 5, Poco F1 and Fire TV, preserving app data. Release installation is not part of this action. `release.txt` changes from 0.5.1 to 0.5.2 so pushing main triggers `.github/workflows/android_build.yml`, which builds/signs and publishes the stable v0.5.2 assets. Workflow completion is tracked outside the repository in the delivery record. Unrelated concurrent work is left uncommitted and excluded from the isolated build/release.
