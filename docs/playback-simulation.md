# Debug popup playback

Open Sports → Options → Playback test in a debug build. This page also appears as
**NikTV playback test** in Android's video Open with and Share menus. Paste a direct
HTTP(S) video URL when a source application supplies one. The Live stream switch
selects the live player timeline. The page never starts a sample automatically.

The popup uses NiKTV's Media3 sports player. Its status comes from actual first-frame
and rendered-buffer callbacks. Retry creates a fresh playback attempt for the same
source. Close returns remote focus to the originating control. URLs are held only in
screen state and are not written to the catalogue fixtures or application profiles.

The two labelled public samples use local bootstrap, catalogue and Base64 resolver
fixtures before opening public DASH media. This is a controlled flow demonstration.
It does not implement HD Streamz's private resolver, bootstrap decoder, sponsor gate,
or DRM integration. Successful fixture playback does not establish that an HD Streamz
match works independently.

## Verification

Build the isolated test package with `-PregressionBuild=true`, which produces
`com.nikhil.niktv.regression` and leaves normal debug/release outputs separate.

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests com.nikhil.niktv.sports.PlaybackSimulationRepositoryTest -PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true
```

Run `com.nikhil.niktv.ui.PlaybackSimulationDeviceTest` with the Android test runner.
Its playback cases use bundled local DASH media to verify advancing decoded frames,
popup focus return in both orientations, HTTP 403 recovery through Retry, and rejected
input. A separate navigation case opens the real Sports menu, whose ordinary startup
may load sports metadata. None of these cases contacts HD Streamz. Release builds
contain neither the page nor its fixtures or Android intent filters.
