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

Portal implementations vary. The client currently targets common `server/load.php` Stalker APIs; provider-specific URL paths and request parameters may require adapters.
