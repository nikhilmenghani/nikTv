# Canadian cricket highlights

**Sports → Highlights** loads Cricbuzz's Canadian featured highlights, Latest Videos and series collections directly. Tiles show artwork, titles and durations, support touch/remote selection and search, and start playback only on selection. Each play/retry refreshes the Home catalogue by stable video ID. Short-lived video URLs remain in memory.

## Direct configuration

Import a local JSON/text configuration with **Sports → Highlights → Cricbuzz configuration**, or supply these keys through the existing encrypted private configuration:

```properties
NIKTV_CRICBUZZ_AUTH_KEY=<runtime API signing key>
NIKTV_CRICBUZZ_AUTH_MODE=AK
NIKTV_CRICBUZZ_APP_VERSION=6.16.04
```

The import is stored encrypted with Android Keystore and overrides synced Cricbuzz values on that device. No GitHub connection or provider endpoint is needed. The APK contains the signing implementation, but no signing secret, account token or captured media session. The signing configuration must be provisioned separately; importing a replacement updates it without rebuilding the APK. The debug-only `files/local_cricket_configuration_import` path supports local `adb run-as` provisioning and deletes the plaintext file after consumption.

NikTV first signs `GET /a/infra/v1/geo-location` and requires a Canadian response. It signs the Home and video catalogue requests with `Accept: application/x-protobuf`, `CB-Loc: CA`, numeric `CB-TZ`, `CB-AppVer` and `cb-src: playstore`. The app's region decision is respected; setting a header alone does not establish a Canadian connection. Region checks are cached for 15 minutes and catalogue results for two minutes. Every outgoing API request receives a fresh timestamped signature. Redirects are rejected and API authentication is never sent to artwork or media hosts.

## Latest Videos and series

The landing page uses stacked category rows: Featured highlights, Latest Videos, then the discovered series. Each row scrolls horizontally and keeps duplicate IDs out of that row; the same match may legitimately appear in more than one category. Series names and preview videos are discovered from the API rather than hardcoded. A full Home URL is preserved when another copy supplies only a preview. Latest Videos includes Highlights, Top Knocks, Top Spells and other on-demand clip types; live MatchStream records remain excluded. Tiles show the returned video type.

Verified routes (all use the existing API authentication and Canadian geo check):

| Purpose | URL |
|---|---|
| Home featured highlights | `https://api.cricbuzz.com/a/home/v1/index` |
| Latest Videos | `https://api.cricbuzz.com/a/videos/v1/index` |
| Discover Latest Videos and series collections | `https://api.cricbuzz.com/a/videos/v1/collection/videoIndex` |
| Latest Videos collection | `https://api.cricbuzz.com/a/videos/v1/collection/detail/1` |
| India tour of New Zealand, 2026 | `https://api.cricbuzz.com/a/videos/v1/collection/detail/656` |
| ODI Tri-Series in Pakistan, 2026 | `https://api.cricbuzz.com/a/videos/v1/collection/detail/650` |
| Australia tour of South Africa, 2026 | `https://api.cricbuzz.com/a/videos/v1/collection/detail/627` |
| Afghanistan vs Zimbabwe in UAE, 2026 | `https://api.cricbuzz.com/a/videos/v1/collection/detail/659` |
| Zimbabwe Women tour of India, 2026 | `https://api.cricbuzz.com/a/videos/v1/collection/detail/643` |

The series IDs and titles above were reported by the live Canadian discovery response. The Latest Videos, New Zealand and Tri-Series collection detail routes were fetched successfully during tracing. Discovery is cached for up to 15 minutes and reloaded by an explicit refresh; the UI follows whichever collections the service supplies.

**View all** opens the selected category in a full grid (two columns on compact phones). **Load more videos** appends the next page using `?pt=<last published timestamp>` for either the latest index or the selected collection. It stops on an empty/repeated page, repeated cursor or the 500-video bound. Each video's original listing/collection and page cursor are held in memory so playback can fetch a fresh URL even for an older page. No signed playback URL is persisted.

The tablet UI supports Left/Right within a category, Up/Down between categories, and explicit OK selection; focus alone does not start playback. Back from a full category returns to the category rows. Search is available from the header icon, and browse positions are retained across playback. Image-ID fallbacks request 1280×720 artwork: the unsized CDN URL returned only 90×68 and became blurry when enlarged. Explicit image URLs supplied by the API remain unchanged.

The bounded collection parser follows `VideoCollectionsDetail → VideoCollectionAdWrapper → VideoCollectionDetail → VideoAdWrapper → VideoItem`. It also supports the response's direct `videos` field and skips unrelated category/ad data. Explicitly free items with no premium URL use their returned `videoUrl` as the actual clip; other missing premium URLs do not fall back to a preview.

## Authentication trace

The supplied APK's `i2/f.java` builds the request headers; `i2/a.java` generates `Authentication`. Its runtime preferences select `AK` for the API hosts. With Unix time in seconds and the app's clock correction:

```text
expiry = now + clockOffset + 300
payload = "exp=" + expiry + "~acl=/*"
signature = lowerHex(HMAC-SHA1(hexDecode(runtimeKey), UTF8(payload)))
Authentication = payload + "~hmac=" + signature
```

The key is loaded from encrypted APK configuration in the inspected app. NikTV accepts the runtime key through encrypted device configuration; it does not include the APK's secret or its decryption wrapper. `CB`/`CF` modes are also implemented from the traced alternate branch: timestamp `now + clockOffset - 600`, HMAC-SHA256 over encoded path, optional query and timestamp, then `<timestamp>-<standard Base64 signature>`. Only the observed `AK` mode has been checked against the live service. A meaningful clock mismatch reported by the HTTPS API's `Date` header permits one corrected retry, bounded to one day.

## Direct Home playback

NikTV selects the fresh `premiumVideoUrl` supplied by Home, preserving the signed Kaltura session URL. It does not add a separate account-verification request, account-state predicate or local entitlement flag. HTTP 401/403 responses from Cricbuzz or the media server are returned as failures; they are not converted into access grants. The signed-out video detail endpoint is never substituted for Home because it returned a promo in the inspected flow.

The bounded Home protobuf reader follows the declared featured-list/video-wrapper fields and selects its non-live highlights. The Latest Videos and series readers include the additional on-demand clip types. The merged catalogue is bounded to 500 IDs. It retains the full URL separately from the promo `videoUrl`.

Before playback, NikTV checks the full HLS master and one media variant. It rejects a missing full URL, the same Kaltura entry as the promo, a non-VOD playlist, or a playlist substantially shorter than its advertised duration. Thus a 76-second subscription promo cannot replace an advertised 24:27 highlight. The player uses on-demand seeking and requests fresh access after an expired URL error.

The two reference full assets inspected on October 28, 2026 were clear HLS through Kaltura/Akamai at 360p–1080p. Other assets may differ. Captured stream/session URLs are excluded from source and tests. Full playback was verified on Samsung after removing the extra NikTV account-verification layer: South Africa–Australia reported 24:16, and New Zealand–India reported 24:27 and continued playing after a seek to 11:00.

## Optional existing provider adapter

For deployments that already have an authorized provider, `NIKTV_CRICKET_HIGHLIGHTS_URL` and optional `NIKTV_CRICKET_HIGHLIGHTS_TOKEN` remain supported when no direct Cricbuzz key is configured. NikTV adds `country=CA` and sends the bearer token only to the configured HTTPS endpoint. A minimal response is:

```json
{
  "country": "CA",
  "videos": [{
    "id": "stable-video-id",
    "title": "Team A vs Team B | Highlights",
    "durationStr": "24:27",
    "imageUrl": "https://images.example/highlight.jpg",
    "videoType": "Highlights",
    "isLive": false,
    "videoUrl": "https://media.example/promo.m3u8",
    "premiumVideoUrl": "https://media.example/full.m3u8?session=fresh"
  }]
}
```

The envelope may instead contain `catalogueBase64` with the original Home protobuf. Both forms preserve the returned full and promo URLs, and use the same playback checks. Responses are limited to 4 MiB.

## Validation

`CricbuzzAuthenticationTest` checks independently generated HMAC vectors, rotation, host restriction, Canadian geo checking, bounded clock retries, cancellation, fresh direct Home playback, API credential isolation and propagation of media-server rejections. `CricketHighlightsTest` covers fresh playback resolution, promo rejection and bounded catalogue parsing. `CricbuzzVideoCatalogueTest` covers Latest Videos and series decoding, explicit free clips, duplicate handling, pagination termination, collection routing and original-cursor playback refresh. `CricketHighlightsDeviceTest` exercises remote/touch navigation, horizontal/vertical category navigation, View all, Load more, Back and readable failures. `CricbuzzDirectDeviceTest` is an opt-in live Canadian catalogue and encrypted-configuration check in the isolated regression package (`-e cricbuzzLive true`). See [verification/cricket-highlights.md](verification/cricket-highlights.md) for actual results and remaining limits.
