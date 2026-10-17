# NikTube inside NikTV

NikTV embeds Flow (phones/tablets) and SmartTube (TV) as source libraries in the
same APK. NikTV remains the only launcher. Settings > General > NikTube experience
offers Automatic, Flow, and SmartTube; SmartTube can also be selected on a tablet.
Automatic selects Flow on touch devices and SmartTube on TV.

The imported players, screens, repositories, downloads, music, subscriptions,
history, playlists and settings remain upstream implementations. Flow has its
own Room schema and preferences; this does not migrate NikTV's IPTV catalogue to
Room. IPTV profiles remain separate. Engine-specific accounts and histories are
not merged between Flow and SmartTube.

## Source and adapters

- `niktube-flow/upstream-provenance.json`: pinned Flow revision and GPL licence.
- `niktube-smarttube/upstream-provenance.json`: SmartTube and companion revisions.
- `tools/prepare-flow.py`: host manifest, branding and licence assets.
- `tools/prepare-smarttube.py`: library builds, legacy Fragment/player resource
  isolation, shared dependency compatibility, host startup and exit navigation.
- `NikTvApplication`: shared Hilt application and display-density isolation.
- `NikTubeActivity`: engine selection and external YouTube link dispatch.

Standalone upstream updaters are disabled: only NikTV should replace its APK.
The Flow FOSS implementation disables upstream Discord integration and its
standalone APK updater. API 24/25 retains the earlier NikTube fallback; the full
Flow/SmartTube engine dispatch currently requires Android 8/API 26 or newer.

## Importing upstream changes

From the repository, inspect a branch or release tag without changing sources:

```powershell
./tools/update-integrated-apps.ps1 -App Flow -Ref main
./tools/update-integrated-apps.ps1 -App SmartTube -Ref master
```

Use `-Apply` to copy that reviewed source into the embedded library and regenerate
the integration adapters. The command does not install or commit anything.

This is a source-import workflow, not a guaranteed one-click upgrade. Review the
diff for removed upstream files, new modules, dependency and schema changes.
The importer does not delete removed files, and Flow's embedded dependency list
and version constants require reconciliation with its updated standalone build.
Build and test both engines and IPTV before distributing an updated NikTV APK.
New upstream architecture changes may require adapting the preparation scripts.

```powershell
./gradlew.bat :app:assembleDebug -PskipLocalGithubToken=true -PperformanceBuild=true
./gradlew.bat :app:testDebugUnitTest --tests '*NikTubeEnginePreferencesTest' --tests '*CancellablePortalCallTest' -PskipLocalGithubToken=true -PperformanceBuild=true
```

The combined ARM64 debug APK is approximately 155 MB. Install the APK matching
the device architecture; the universal APK includes all native player ABIs and
is substantially larger. Source licences are retained and the main upstream
licences/provenance are also packaged as APK assets.

## Validation

Tablet smoke tests cover both engines, native YouTube search/playback, engine
selection, return to NikTV, display scaling between engines, and a Nikhil IPTV
live channel using ExoPlayer. These do not certify every upstream feature or TV
hardware playback. FireTV playback was deliberately left untouched during this
integration. Samsung was disconnected, so tablet testing was used instead.

Native Flow downloads and local-file playback were also checked using a small
test download, without granting access to all device files. Phone-width portrait
layout was checked on the tablet with a temporary viewport override.
