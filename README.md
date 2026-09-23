# nikTv

Clean-room Kotlin/Jetpack Compose IPTV client for authorized Stalker/MAG portals.

## MVP features

- Portal URL, profile name, and MAC-address onboarding
- Stalker handshake and profile authentication
- Live TV, VOD, series, and radio categories/catalogs
- Media3/ExoPlayer playback
- Encrypted-by-app-sandbox profile preference storage
- Responsive phone, tablet, Android TV, Google TV, and Fire TV layouts
- Touch, keyboard, and D-pad focus behavior

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
