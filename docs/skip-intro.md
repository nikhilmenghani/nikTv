# Saved intro timings

NikTV can show a compact **Skip intro** button during a manually saved intro interval. It uses the existing seek operation in ExoPlayer, Flow, mpv and VLC; selecting Skip does not change the user's play/pause intent or replace the stream.

## Using it

1. Open a seekable movie or episode and show the player controls.
2. Open **More controls** if needed, then choose the flag icon labelled **Intro timing**.
3. Enter Start/End as `m:ss` or `h:mm:ss`, or use **Mark start / Mark end** to sample the current playback position. Close the editor to continue watching and reopen it to mark the other end; the unsaved draft is retained for that item.
4. For a series, choose **Season N** to reuse the interval across that season, or **This episode** for an override. Movies use **This movie**. Save explicitly.
5. Skip intro appears during the interval with a playback-time countdown, for example `Skip intro · 0:12`. It disappears automatically at the saved end without a click; pausing or stalled playback does not spend countdown time. On a remote, the visible corner button takes focus automatically, so OK skips immediately. Up opens transport controls and Left returns focus to the video. Touch interaction does not force remote focus. With transport controls open, Skip intro is also available among the extra player actions.

**Remove saved timing** removes the selected scope. Removing an episode override reveals its existing season timing. No timing is created automatically. Timings remain local to this installation and profile.

## Identity and playback rules

- Explicit `PlayingMedia.profileKey` identifies the owner for IPTV, alternate-source and offline playback. Do not infer it from the bounded/truncated progress key. Normal UI profile data is not copied into test packages.
- Known series/season/episode identities determine the scope. Confirmed TMDB IDs are used where available, otherwise provider identities. Unknown season metadata offers only an individual episode timing; it never silently joins Season 1. Season 0 specials are separate.
- Preference keys hash length-framed identity components; playback URLs, credentials and titles are not stored in timing keys. Changing the URL, playback attempt or engine does not invalidate a timing.
- A valid end is after the start and at least one second before the known video end. Invalid overrides do not silently fall back to another timing for a shorter/different episode cut. Live and unsupported/unknown-duration sources do not offer intro seeking.
- Skip visibility stops in the final half-second of the interval to tolerate native timestamp rounding without flashing the button again after a successful seek. Seeking back earlier in the intro offers Skip again.
- Skip takes presentation priority over the existing last-30-seconds Next Episode prompt on short clips. The original completion/autoplay policy remains active; the prompt becomes eligible after the intro interval.
- No provider metadata searches, additional decoder/stream preparation or periodic storage reads are added. Network media can still fetch its normal segments when the existing player seeks.

## Storage and cost

`player_intro_timings` SharedPreferences retains at most 500 timing records across profiles, evicting the oldest saved records at the limit. Loading and committing run on IO. The player reuses its existing position/duration updates; keys are computed once per identity. Save jobs and loads are canceled/invalidated when their media scope leaves composition. There is no Room dependency or automatic intro detection.

## Targeted verification

The fixture package is `com.nikhil.niktv.regression`; it never opens user profiles/providers. Existing generated H.264/AAC video and all-zero PCM audio fixtures are silent (AAC peak/RMS verified `-inf`). User settings, media and caches are preserved. Test timing keys and generated files are restored/removed after each case.

Focused unit selection: `IntroTimingTest`, `PlaybackSeekTargetTest`, `PlaybackSessionPolicyTest`, `VlcResumeSeekPolicyTest` (48 tests). Tablet selection: `IntroSkipDeviceTest` (11 cases covering all four engine choices with actual local video/audio, touch, D-pad, user pause, draft marking, scope persistence/overrides, storage bounds, compact layout and focus restoration/expiry).

```text
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests com.nikhil.niktv.ui.IntroTimingTest --tests com.nikhil.niktv.ui.PlaybackSeekTargetTest --tests com.nikhil.niktv.ui.PlaybackSessionPolicyTest --tests com.nikhil.niktv.ui.VlcResumeSeekPolicyTest -PregressionBuild=true -PskipLocalGithubToken=true -Dorg.gradle.jvmargs="-Xmx6g -Dfile.encoding=UTF-8" --offline --no-daemon --max-workers=1
adb -s TABLET shell am instrument -w -r -e class com.nikhil.niktv.ui.IntroSkipDeviceTest com.nikhil.niktv.regression.test/androidx.test.runner.AndroidJUnitRunner
gradlew.bat :app:assembleDebug -PskipLocalGithubToken=true -Dorg.gradle.jvmargs="-Xmx6g -Dfile.encoding=UTF-8" --offline --no-daemon --max-workers=1
gradlew.bat :app:lintDebug -PregressionBuild=true -PskipLocalGithubToken=true -Dorg.gradle.jvmargs="-Xmx6g -Dfile.encoding=UTF-8" --offline --no-daemon --max-workers=1
```

Run records, original failures and final results are retained in `D:/workspace/niktv-playback-tests/` under `intro-skip-*`, with the lint baseline audit in `skip-intro-20261025/`. Earlier failures exposed native clock rounding and short-clip prompt competition; tests also needed to respect VLC's intentionally playing-only history callbacks and the displayed clock's whole-second precision. There is no private-provider seek-accuracy or Fire TV device claim from these local tablet tests.

### Results — October 25, 2026

- **48 focused unit tests passed** (17 timing, 3 seek-target, 20 playback-session/completion, 8 VLC-resume cases).
- **11 tablet tests passed together** in 67.251s on Xiaomi Pad 5. Both touch and D-pad exercised real native local audio/video playback. The final layout assertion rejects a full-width Skip button; the retained video screenshot shows the compact corner treatment. Pause checks allow one second of displayed timestamp quantization, then wait 2.5s and verify the clock remains near the seek target and user pause intent stays false.
- Final isolated APK build passed in 1m11s; normal Dev build passed in 2m10s. Installed the normal ARM64 Dev APK in place, preserving profiles/settings. SHA-256: `B9CB934728FE9C68C78C1487A22DB97C328ED89842D6AC037C33ECF88E0BAA0D`.
- Full `lintDebug` **failed** with 25 errors, 364 warnings and 3 hints. All 25 error source lines already exist in baseline `8bfd672f`, confirmed by `skip-intro-20261025/final-lint-baseline-audit.json`; no suppressions were added.
- Fire TV, Samsung and other phones were not used or updated for this feature. No device audio/animation/accessibility settings were changed. Fixtures were silent; user profiles were not exercised. No commit or push.

### Follow-up Fire TV installation

At the user's subsequent request, updated both Fire TV packages in place: normal release `com.nikhil.niktv` 0.5.1 / 5001 and Dev `com.nikhil.niktv.debug`. The signed ARMv7 release built in 4m46s including `lintVitalRelease`, passed `apksigner verify` and is non-debuggable. Artifact SHA-256: release `5CB8C2E3EADAB12800F9F52116DF5BED77E5CAB925B2BE8DCA828C8D5E4335E4`; Dev `70683D7853E43DF405D9A16834759A717B36F2FEB9FFBFD6B5AC8789D93A8605`. Both `adb install -r` operations succeeded, retaining existing data. Build log and package update timestamps are in `skip-intro-20261025/`. No Fire TV input, playback test, app launch or audio-setting change was performed during this installation follow-up.

### Remote default focus — October 26, 2026

Skip Intro now requests focus when its corner button appears during remote navigation. The delayed player-surface handoff also respects the visible Skip control, so it cannot steal focus back after the button appears. Touch mode keeps its existing behavior, and moving Left deliberately returns focus to the video. Targeted tablet checks confirmed OK without a Down press, paused seeks on VLC/mpv, Exo video playback, Flow touch playback, and focus restoration when the intro expires. The normal Dev APK was installed on Samsung in place; Fire TV and release packages were left untouched for this follow-up. Episode navigation/intro verification records are in `D:/workspace/niktv-playback-tests/episode-destination-verification.json` (44 focused unit tests passed).

### Countdown and natural expiry — October 26, 2026

Visibility and the corner-button countdown now use one shared playback-clock calculation on ExoPlayer/Flow/mpv and VLC. The existing last-half-second native rounding guard is retained; no new polling loop, network call or playback buffer is introduced. The action keeps its accessible Skip intro identity while the visible m:ss countdown changes, preserving focus and compact dimensions.

19 focused timing tests passed. Eight distinct tablet cases passed across the initial run and focused pause retest, including real silent H.264 playback naturally crossing the saved end on all four engines without pressing Skip, returning focus to the video afterward, and existing touch/remote Skip behavior. The previous component expiry check manually removed the button; these new cases exercise the native playback clock instead. mpv pause intent precedes native/polled clock acknowledgement, so the pause check now first requires 1.5s of stable clock, then verifies another 1.2s without countdown change. Normal Dev build and Samsung in-place update succeeded. Fire TV and release builds were untouched. Verification records and retained original test failure are in `D:/workspace/niktv-playback-tests/intro-countdown-verification.json`.
