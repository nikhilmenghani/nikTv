# LAN playback validation — 2026-11-07

Release 0.6.1 adds paired playback between NikTV devices on the same Wi-Fi.
Both sender and receiver need the updated app.

## Behaviour

- An IPTV match resolves its playback source on the sender. The receiver checks
  the matching portal is open and starts that stream without another catalogue
  search. Provider restrictions on session-bound URLs still apply.
- External files retain a read grant in the foreground sharing service. Direct
  HTTP sources retain required headers; selected files and loopback streams use
  a bounded relay supporting ranges and necessary HLS resources.
- Codes rotate every five minutes. Existing paired tokens survive rotation.
  The receiver lists paired devices, names the playback owner and can disconnect
  individual devices. A second sender cannot replace or control the owner's movie.
- The compact phone dialog keeps its footer visible. Connect or Stop receiving
  sits beside Done; tablet dialogs use two columns. Address and port share a row.

## Automated checks

- Full unit suite: 1,465 tests, zero failures/errors, one existing skip.
- Debug and release lint: passed.
- Source credential scan and release workflow validation guards: passed.
- The first CI run exposed an asynchronous cleanup assertion in the existing
  Abibli lease test. It now waits for the five real close callbacks before
  checking the counter; runtime behaviour and cleanup assertions are preserved.
- Unit coverage includes code expiry/rotation without token loss, pairing attempt
  limits, duplicate installation pairing, ownership during pending playback,
  failed playback/stop, revocation, source validation and matching-portal checks.

## Connected devices

- POCO X3 Pro and Xiaomi Pad 5: compact/wide forms, footer alignment, same-row
  address/port, paired-device list, reactive code update and footer D-pad paths.
- Actual Stalker handoff: selected the English 4K-labelled version of The Odyssey
  on POCO's TMDB IPTV matches page. The tablet rendered the stream in VLC and its
  native clock advanced beyond 1:55. Receiver status reported Playing.
- Observed a real five-minute code rotation with POCO still paired, then used
  that existing connection to start the movie.
- A separate sender installation paired during that movie. Play, Pause, Resume
  and Stop were all rejected, while status remained available and the original
  movie's playback position continued advancing. Both senders appeared in the list.
- Removing the other sender left playback running. Removing the owner stopped
  its movie and removed its pairing.
- File relay regression: passed in 88 seconds. The tablet rendered a video from
  a separate file-manager UID, continued while POCO opened Settings, retained
  access after the sender activity finished, reopened/seeks the retained file
  at 50 seconds, and stopped with foreground-service cleanup.

Fire TV was not operated during this validation. Local screenshots and detailed
logs are retained under `D:/workspace/niktv-playback-tests/lan-playback-20261107`.
