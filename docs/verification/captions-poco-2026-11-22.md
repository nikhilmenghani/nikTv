# Caption validation on Poco X3 Pro

Scope: the shared production player and caption dialog on Poco X3 Pro, Android 16. Playback was muted. Fire TV was not used. The ten-media matrix uses ten distinct local videos with real embedded text tracks, rather than relying on provider subtitle availability. A real Xtream movie, **Wonder Woman 1984**, also played with readable embedded English captions.

## Changes

- Retain stable caption-track focus, scroll lazy destinations into view, and return directly to the originating player control.
- D-pad focus does not open a search keyboard. OK/touch activates editing; Back unwinds editing, online search, and the popup.
- Remove unsupported native timing/appearance controls instead of offering settings that have no effect. ExoPlayer native appearance works; native VLC timing works; downloaded SRT/VTT supports both.
- Observe VLC caption preferences in composition before synchronizing them to the parent. Appearance-only updates previously changed a child layer without updating the saved session state.
- Keep native ExoPlayer captions above visible transport/settings controls. Standard preserves authored ASS styling; readability presets override it explicitly.
- Parse UTF-16, markup/entities and WebVTT metadata safely; combine overlapping dialogue and use an indexed timeline for seeks/delay changes. Android instrumentation covers ICU regex initialization separately from JVM tests.
- Styled downloaded formats use a one-session VLC handoff when the stream supports it, preserving playback intent/position and the subtitle file. Incompatible sources explain the supported SRT/VTT alternative.

## Media matrix

| File | Caption coverage |
| --- | --- |
| case01-movtext-en.mp4 | English, MP4 timed text |
| case02-movtext-hi.mp4 | Hindi shaping, MP4 timed text |
| case03-subrip-ar.mkv | Arabic shaping, SubRip |
| case04-subrip-es.mkv | Spanish accents, SubRip |
| case05-ass-ja.mkv | Japanese, ASS |
| case06-ass-styled-en.mkv | Authored ASS style and long text |
| case07-multi-en-fr.mkv | English/French track switching |
| case08-forced-en.mkv | Forced track |
| case09-nondefault-en.mkv | Non-default track selection |
| case10-delayed-multiline-en.mkv | Delayed start, multiline text |

All ten check native cue text, visible screenshots, D-pad selection/dismissal, Off and clearing after cue end. MP4 checks direct backward-seek restoration. Sparse MKV checks restoration by decoding from the subtitle packet, with the limitation below recorded in each result. The popup tests cover actual portrait/landscape windows, 24 tracks, long names, selected-track entry, timing reset, unsupported-control paths, keyboard activation/dismissal, touch-to-D-pad switching and origin focus-event history. Track labels are bounded to two lines so the focus outline fits inside a short list viewport.

## Known engine/container limit

These valid, deliberately sparse MKVs contain one caption packet at 0.5s lasting until 7s, followed by later video-keyframe clusters. Seeking directly from 7.5s back to 3s can omit the still-active caption packet in Media3 1.8.0. The same demux omission reproduces with FFmpeg input seeking; equivalent MP4 retains the caption. Seeking to the start and decoding the packet restores it. This is **not** counted as arbitrary MKV seek support or a fixed Android app bug. Keep the sparse fixtures; do not regenerate easier files to conceal it.

LibVLC 3.6.5 owns native caption styling and placement. Its native captions can overlap the visible settings ribbon; normal viewing with controls hidden is unaffected. SRT/VTT overlays and Media3 native captions account for visible controls. Do not translate the entire VLC subtitle surface as a quick workaround: that can clip authored captions positioned at the top. Native VLC appearance presets are therefore explicitly unavailable, rather than implying a runtime styling API exists.

## Reproduction

Validation passed: the ten-media matrix; all four popup D-pad scenarios in physical portrait/landscape; Android parser initialization; both VLC integration tests (native French pixels/Off, and downloaded SRT timing/reset/appearance/Off); eight targeted JVM subtitle tests. Screenshots verify English, Hindi, Arabic, Japanese, styled and multiline captions. Native VLC's test decodes from the beginning to include the sparse subtitle packet; it does not establish arbitrary sparse-MKV seek support.

Generate the deterministic fixture archive with `python tools/generate-caption-fixtures.py` (FFmpeg/FFprobe required). Build with `-PregressionBuild=true -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true`, install the regression APK and androidTest APK, and run these classes through AndroidJUnitRunner:

- `CaptionMediaMatrixDeviceTest`
- `SubtitleSelectionNavigationDeviceTest`
- `DownloadedSubtitleParserDeviceTest`
- `VlcCaptionIntegrationDeviceTest`

The isolated package writes matrix results/screenshots under `files/caption-matrix` and VLC evidence under `files/vlc-caption-integration`. Local copied evidence and runner logs are under the ignored `build/caption-evidence` and `build/poco-caption-*` paths. Tests do not alter saved Dev profiles or provider credentials.
