# Private NikTV configuration

NikTV no longer inserts profile credentials or service tokens into an APK. On each device, open **Settings → Private configuration**. The GitHub owner defaults to `nikhilmenghani`; enter the repository name (for example `myenv`) and configuration file name (for example `.env`). Enter a GitHub token with **Contents: read** access to that repository, or leave the token field blank to reuse a token already entered in GitHub backup settings. Tap **Save and sync**. The token and only NikTV's recognized keys are encrypted locally with an Android Keystore key. The cached values load immediately at startup; WorkManager and app startup refresh them at most once every 24 hours. **Save and sync** forces an immediate refresh.

The file may be JSON with top-level string values, or `.properties`/`.env` lines in `KEY=value` form. Supported keys are:

```text
NIKTV_DEFAULT_PROFILE_NAME
NIKTV_DEFAULT_PORTAL_URL
NIKTV_DEFAULT_MAC_ADDRESS
NIKTV_DEFAULT_SERIAL_NUMBER
NIKTV_XTREAM_PROFILE_NAME
NIKTV_XTREAM_PORTAL_URL
NIKTV_XTREAM_USERNAME
NIKTV_XTREAM_PASSWORD
NIKTV_TMDB_API_KEY
NIKTV_TMDB_READ_ACCESS_TOKEN
OPEN_SUBTITLES_KEY
G_TOKEN
```

The IPTV profile values appear in Settings → Profiles after a successful sync. `G_TOKEN` is optional; the GitHub backup screen can also accept a token entered directly on the device. The app keeps the last successful cache when GitHub is unavailable. A `G_TOKEN` GitHub Actions secret is available only during a workflow run; it cannot be used by an installed app without copying it into the APK. Keep the token out of APK assets. Rotate any credentials previously embedded in published APKs.
