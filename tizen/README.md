# NikTV for Samsung Tizen

Separate TV application in the existing NikTV repository. Target: Samsung
UN43MU6300FXZC (2017), Tizen 3.0 / Chromium M47. This is an initial playback
prototype, not the Android feature set and not yet tested on physical hardware.

## Current slice

- Direct HTTP(S) stream entry; URLs remain in memory only.
- Samsung AVPlay, letterboxed full-screen video, remote focus, pause/resume, stop.
- Return cancels playback; Return on setup exits. Backgrounding releases AVPlay.
- ES5 runtime JavaScript and simple CSS for the old TV engine. No Node runtime
  or Android Gradle changes required on the TV.
- Local lifecycle checks: `node tizen/tests/player.test.cjs` from the repo root.

Opening index.html on a PC can exercise navigation, but cannot validate AVPlay.
Codec/container, TLS, streaming, and firmware compatibility require the real TV.
No portal credentials, tokens, device MAC, or Android private configuration are
copied into this project.

## Device prerequisites

1. Install the Tizen SDK CLI, TV Extension tools, Certificate Manager, and Samsung
   Certificate Extension (2.0.73 or newer). Current Tizen documentation retires the
   Eclipse-based Tizen Studio IDE; use the CLI or a supported editor extension.
2. Put the TV and PC on the same local network.
3. On the Samsung TV's Apps screen, enter 12345 using the remote/number keypad.
   Enable Developer Mode, enter the PC's LAN IPv4 address, and restart the TV.
   Menu placement can vary by firmware. Do not change service-menu settings.
4. Connect using Tizen Studio Remote Device Manager with the TV's LAN address.
5. In Certificate Manager, create a Samsung TV certificate profile using your
   Samsung account and include this TV's DUID. Keep the author certificate safe
   and private for future updates. Permit application installation on the device.
6. Build this folder as a Tizen web application, then package with the Samsung
   certificate profile to produce a signed WGT and install it using the CLI or
   supported editor extension.
7. Test a known playable direct stream, including Return during buffering,
   pause/resume, stream completion, invalid URL, and background/foreground.

The Tizen package ID is `NikTVTizen`, application ID `NikTVTizen.NikTV`.
Signed WGTs remain ignored by Git. On 2026-10-19, this preview installed on the
UN43MU6300 (Tizen 3.0) at 192.168.2.18, reporting `install completed` and
`cmd_ret:0`. AVPlay stream playback still requires a device test.

Use the verified Samsung TV deployment path:

```powershell
.\tizen\install-tv.ps1 -TvIp 192.168.2.18 -ProfileName Nikhil -Launch
```

The generic `tizen install` invocation failed without diagnostics on this TV.
The successful procedure uploads the generated Samsung `device-profile.xml` to
the device's `sdk_toolpath`, uploads the WGT with a filename containing no spaces,
and invokes `shell 0 vd_appinstall`. Both authorization and the upload filename
changed together, so the original failure cannot be assigned to only one change.
No TV apps or user data were deleted by this procedure.

### Local SDK setup

SDK installed at `D:\workspace\tools\tizen-sdk` with Tizen SDK 10 CLI installer.
Verified tools: Tizen CLI 2.5.25 and SDB 4.2.36. Samsung TV Extension Tools and
Resources 10.0.0 and Samsung Certificate Extension 2.0.75 are installed.
`build-web` succeeds for this project; this does not validate TV playback.

PowerShell build command:

```powershell
& D:\workspace\tools\tizen-sdk\tools\ide\bin\tizen.bat build-web -- D:\workspace\android\nikTv\tizen
```

Use the PC's current LAN IPv4 address for the TV's Host PC IP field (check
`ipconfig`; DHCP can change it). The Samsung TV address is separate from the
Fire TV stick address. Certificates and passwords must remain outside Git.

## Porting plan

Keep Android in `app/`; develop Samsung UI/playback here. Kotlin Compose,
Media3, MPV, Room, WorkManager, and Android Keystore cannot be imported into this
web app. Reuse protocol semantics, fixtures, catalog schemas, and branding;
translate runtime behavior behind JavaScript adapters.

Next milestones:

1. Prove AVPlay and developer installation on this MU6300.
2. Port profile onboarding and portal authentication from StalkerPortalClient,
   then live categories, channels, and stream resolution. Audit the portal's
   Cookie/User-Agent/header requirements: browser XHR cannot freely set headers
   like Android OkHttp. Test packaged-app requests and provider behavior before
   choosing a direct client or an optional companion relay.
3. Add VOD/series, search, favorites/history and persistence, then pairing/backup.
   Credential storage needs a separate design; do not port tokens to plain storage
   by default. Large catalogs need paging and memory limits for this hardware.
4. Evaluate subtitles/audio selection, recording, downloads, DRM, and NikTube
   individually. Android native engines and YouTube integration are separate
   compatibility work, not automatically available in Tizen.

## Samsung references

- [TV model groups](https://developer.samsung.com/smarttv/develop/specifications/tv-model-groups.html)
- [Web engine specifications](https://developer.samsung.com/smarttv/develop/specifications/web-engine-specifications.html)
- [AVPlay guide](https://developer.samsung.com/smarttv/develop/guides/multimedia/media-playback/using-avplay.html)
- [Connect and run on a TV](https://developer.samsung.com/smarttv/develop/getting-started/using-sdk/tv-device.html)
- [Create certificates](https://developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/creating-certificates.html)
