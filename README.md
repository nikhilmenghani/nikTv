# nikTv

Clean-room Kotlin/Jetpack Compose IPTV client for authorized Stalker/MAG portals.

Samsung TV development lives in [tizen/](tizen/README.md), a separate Tizen web
application targeting the 2017 MU6300. The initial prototype tests AVPlay and
remote navigation; portal and catalog features are not yet ported.

## Discovery UI trial

Home 2 opens by default after choosing or switching a profile. Movies 2 and Series 2
remain separate navigation entries, and the original screens are accessible through
Browse. The new UI uses released TMDB discovery titles, compact artwork
rows and full information pages; IPTV availability is checked only when the user
requests playback or a download. Trial section preferences are separate from the
original Home configuration. See [the working design and verification notes](docs/tv-browsing-motion-plan.md).

Phones use a dedicated portrait discovery layout with Home, Live TV, Search and My
List in the bottom navigation. To return to the original UI, choose **Use original
UI** in the new Browse menu, or disable **Modern discovery UI** under Settings →
Device & display. The choice is remembered; enabling the setting restores the new UI.

## MVP features

- Portal URL, profile name, and MAC-address onboarding
- Stalker handshake and profile authentication
- Live TV, VOD, series, and radio categories/catalogs
- Media3/ExoPlayer playback
- Encrypted-by-app-sandbox profile preference storage
- Responsive phone, tablet, Android TV, Google TV, and Fire TV layouts
- Touch, keyboard, and D-pad focus behavior

## Play on another NikTV device

Install the updated app on both devices and connect them to the same Wi-Fi.
On the receiving Fire TV/tablet, open an IPTV portal, then **Options → Play on
NikTV → Receive on this device**. On the phone, use the NikTV cast icon (or the
same Options entry), choose the receiver, and enter its six-digit code.

For IPTV browsing, select the same portal on both devices. Normal IPTV movie
Play buttons, including movies found from TMDB, resolve the selected item's link
once on the sender and play it on the receiver. The receiver verifies that the
matching portal is open; it does not repeat the movie catalogue search.

For a video from file explorer or another app, open/share it with **NikTV**, then
tap **Play on NikTV** next to Back in the player. This path sends the selected
video and its position (where the format/player supports seeking) without requiring the same IPTV portal. The paired
receiver uses its own player. Direct network links include their playback
headers; local files and app-owned loopback streams use a temporary phone relay.
Only the selected file and its necessary stream resources are shared.

You can use other apps or close the sender's player activity while the receiver
continues playing. For phone-hosted media, keep the phone connected to Wi-Fi.
The foreground sharing notification has **Stop sharing** and reopens remote
controls. Pause, Resume, Stop, and actual playback status are also available in
the device dialog. **Disconnect** leaves receiver playback running. If discovery
is unavailable, use **Connect by address** with the receiver's IP and port.

The receiver must stay open in NikTV. The six-digit code refreshes automatically
every five minutes without disconnecting paired devices. The receiver lists paired
devices and identifies which one controls playback; another sender cannot replace
or control its movie. Disconnect a device there to revoke its access (and stop its
movie if it owns playback). Stopping Receive or leaving the app revokes its session, so pair again
after returning. Switching apps on the sender does not revoke the receiver
session. DRM and separate-audio sources still need local playback. The receiver
must support the selected video's format; this feature does not transcode it or
wake a background Fire TV. Stream URLs and required playback headers travel over
the encrypted paired connection; some provider URLs carry playback credentials or
session tokens. Only pair with a trusted receiving device. Provider restrictions on
session-bound links or simultaneous streams still apply.

## Build

Open this directory in Android Studio, or run `gradlew.bat assembleDebug` on Windows.

For local debug builds, put `G_TOKEN=...` in
`%USERPROFILE%\.gradle\gradle.properties`. Android Studio Run and
`gradlew.bat assembleDebug` embed that token in the debug APK and use it as the
initial GitHub credential on first install. An already saved device token takes
precedence. You can share this locally built APK with trusted devices, but
anyone who receives the APK can extract the token. CI builds and all release
builds leave the default blank and require normal configuration.

For easier device selection, run `powershell -File tools\install-local-debug.ps1`
or Android Studio's `installLocalDebug` Gradle task. The helper lists devices by
model, combines duplicate USB/Wi-Fi connections, and accepts `-Device Samsung`
or Gradle `-Pdevice=Samsung`. Devices initialized with the local default are not
pairing administrators; that role requires manual token entry.

Portal implementations vary. The client currently targets common `server/load.php` Stalker APIs; provider-specific URL paths and request parameters may require adapters.
