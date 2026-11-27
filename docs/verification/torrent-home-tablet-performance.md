# Torrent Home tablet scrolling — 2026-11-27

Measured on the connected Xiaomi Pad 5 (`6889f3db`), landscape 2560×1600,
using NikTV Dev and the existing Nikhil profile. No playback was started.
Fire TV was not used.

## Reproduction and measurements

`python tools/measure-home-scroll.py LABEL --activity TorrentActivity`
performs eight identical 500 ms vertical swipes (down, down, up, up, twice),
after resetting `dumpsys gfxinfo`. IPTV uses `--activity MainActivity`.
The tool checks the foreground activity before each swipe and saves raw
process/window frame statistics in `build/home-scroll/LABEL.txt`.

| Screen/sample | Frames | Janky frames | P90 | P95 | P99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| IPTV before | 637 | 4.24% | 16 ms | 21 ms | 32 ms |
| Torrent before, first sweep | 297 | 28.28% | 89 ms | 150 ms | 150 ms |
| Torrent before, repeated sweep | 405 | 30.37% | 57 ms | 69 ms | 97 ms |
| Torrent final, first sweep | 595 | 7.90% | 17 ms | 19 ms | 73 ms |
| Torrent final, repeated sweep | 654 | 4.89% | 15 ms | 16 ms | 22 ms |
| IPTV final control | 671 | 3.87% | 18 ms | 19 ms | 28 ms |

Corresponding raw files: `iptv-before`, `torrent-before`,
`torrent-before-warm`, `torrent-final-1`, `torrent-final-2`, `iptv-final`.
These are physical-device Debug comparisons with real cached/network artwork,
not release benchmarks or a claim of zero dropped frames. The portals have
different configured rows/content. The UI-thread stall count fell from 107
in the repeated pre-fix Torrent sample to 13 in the final repeated sample;
GPU P95 remained 8–9 ms. Both final Torrent runs reported zero missed Vsync.

## Cause and implementation

Tablet/TV/mobile-landscape Torrent Home was still composed as a nested
`LazyColumn`/`LazyRow` feed. IPTV Home and Torrent portrait already used the
recycled native row/card renderer. The wide Torrent path therefore lacked
IPTV's retained card pools, asynchronous row diffing, bounded artwork work,
and native scroll/focus/viewport ownership.

Wide Torrent Home now uses `RecycledDiscoveryRows` with contained captions,
the existing hero, skeleton reconciliation, stable row/item identities and
native saved viewport restoration. The old wide list/handoff implementation
was removed. Home's hero candidates are remembered by shelf data and date,
avoiding repeated release-date validation while scrolling/focus state changes.

The existing native focus restorer owns re-entry from details/menus. An
opt-in loading-boundary policy keeps the current tile focused while a pending
Torrent shelf loads. Default IPTV behavior is unchanged.

## Targeted validation

`TorrentHomeScrollFocusDeviceTest` passed both TV and short mobile-landscape
configurations on the physical tablet (`OK (2 tests)`):

- D-pad entry through the actual hero, then movement through successive rows.
- Repeated Down at a loading shelf retains the previous tile and viewport.
- Publishing loaded cards and updated artwork/titles does not steal focus.
- Repeated Down at the final row does not wrap to the header.
- Native re-entry restores the selected poster and viewport without selecting
  another poster or the hero in the focus-event history.

Build/runner logs are `build/torrent-home-final-dev-build.log` and
`build/torrent-home-recycled-focus-tests.log`. Updated NikTV Dev was installed
on the tablet and opened through the actual portal menu for the final samples.
Focused/unfocused screenshots are in `D:/workspace/artifacts/` with the prefix
`tablet-torrent-home-final`.
