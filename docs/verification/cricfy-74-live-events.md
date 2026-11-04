# Cricfy 7.4 Live Events handoff

3 November 2026. Scope: Sports web-player sources used by Live Events; existing player and IPTV paths are unchanged by this patch.

## Observed comparison

- Tablet: Cricfy 7.4, version code 54. Samsung: Cricfy 7.3, version code 53, retained without upgrading.
- Willow HD played video on both versions. Both offered Willow HD, India IP, Willow AQ and Willow EX. This is a working comparison source, not evidence that every Live Events source works.
- On this stream 7.4 displayed Video / Audio / Text track tabs, Auto / None and Apply. English audio and text tracks were listed. The 7.3 quality dialog opened during comparison listed video resolutions, Auto / None and OK.
- The first visible category titles matched. Category content is remote; this does not attribute catalogue changes to the APK. The attempted external-category comparison was inconclusive and is not a playback acceptance result.
- APK inspection identifies Edge pages containing `/api/playlist-token`. Their HTML can expose an unsigned manifest template before JavaScript obtains playlist authorization.

Screenshots and XML evidence are outside the repository in `D:/workspace/artifacts/cricfy-74-audit/`: `tablet-willow-result.png`, `samsung-willow-result.png`, both `*-willow-choices.xml`, `tablet-quality-open.png`, `tablet-audio.xml`, `tablet-text.xml`, and `samsung-quality.xml`. No stream credentials are copied into this document.

## Targeted fix

The previous extractor could return a manifest from inline HTML, player configuration or resource history before the web player completed authorization. Such a handoff also omitted request-specific token headers.

For pages containing the Edge token endpoint, extraction now marks the page as token protected and suppresses scraped media candidates. The WebView remains on that page while its JavaScript performs authorization. Only an actual GET media request completes resolution; its query string, permitted request headers and media-URL-scoped cookies pass to the existing player. The resolver no longer advances to another iframe while awaiting that page's authorized request. Ordinary web sources retain their previous extraction behavior.

This uses the page's own token flow. It does not invent a token-header convention, copy Cricfy native libraries, add CFP1 decoding, change track controls, or implement Cast. A page that never issues a media request still times out through the existing resolver timeout.

## Focused verification

The added device case serves an Edge-style page with an unsigned manifest template and delays its token request. It asserts that resolution returns the subsequent token-bearing manifest request and its custom header, rather than the early template. Run on the Poco X3 Pro and tablet. Unit coverage checks protected candidates and captured header handling.

Results: normal debug build and isolated instrumentation build passed. `SportsWebExtractionTest`: 6 passed, zero failures/errors. The single Edge token-handoff device case passed on Poco X3 Pro (2.075 seconds) and Pad 5 tablet (2.013 seconds). The normal ARM64 debug APK was installed successfully on both devices with `adb install -r`, preserving application data. Initial normal build failed due to a missing generated R.jar; a subsequent build passed. Logs are in the evidence directory as `live-events-build-retry.log`, `live-events-device-build.log`, `live-events-poco-test.log`, and `live-events-tablet-test.log`.

A fixture result verifies the handoff contract; it does not establish successful playback of an untested remote event. Remote Edge event playback remains unverified. No broader test suite was run for this targeted change.

## Switching behavior for comparison

Open **Sports > three-dot Sports options > Stream compatibility**. Choose **7.4 token-aware** (default) or **7.3 legacy**. The selection persists locally in Sports-only preferences and is captured once at the beginning of each web-source attempt. Exit playback and reopen the same source after switching; an active stream is not restarted automatically. Direct sources do not use this resolver, so their behavior does not change.

Legacy mode restores NikTV's prior scraped-candidate, iframe traversal and request-method behavior. It retains the existing header/cookie handling that already preceded this patch. The labels describe NikTV compatibility paths, not bundled Cricfy executables or backend versions. Provider catalogue changes, removed streams and expired authorization are not rolled back by this setting.

The device case now resolves the same local page in both modes: token-aware returns the delayed token-bearing manifest/header; legacy returns the early unsigned template. Passed on Poco X3 Pro (2.511 seconds) and tablet (2.479 seconds). Both APK builds passed. Evidence: `compatibility-device-build.log`, `compatibility-build.log`, `compatibility-poco-test.log`, `compatibility-tablet-test.log` in the audit artifacts directory.

The compatibility build was installed successfully on Poco X3 Pro, tablet, Samsung and Fire TV, preserving app data. On the Poco, the normal app's Sports options menu and compatibility dialog were opened and choosing legacy changed the menu label to `Stream compatibility: 7.3 legacy`. Token-aware was selected again afterward. Temporary regression packages were removed from Poco and tablet.

Initial handoff-only patch limitation (superseded for recognized Edge pages by the renewal follow-up below): that patch captured startup authorization without renewing tokens during playback. The explicit fresh Retry re-fetches source metadata and resolves again. Legacy mode cannot guarantee immunity to provider token expiry.

## Follow-up: confirmed Cricfy token renewal

Further APK instruction inspection on 3 November confirms renewal in Cricfy 7.4's custom data source (`fn0`, shared token state `en0`, factory `iu1`). These methods were omitted by the original decompiler output; fallback instruction output is under `D:/workspace/artifacts/cricfy-74-token-datasource/sources/defpackage/fn0.java`.

- For `.m3u8` requests, it obtains/caches the token from the configured token API and reads JSON `token` and `ttl`.
- Missing/unparseable TTL defaults to 120 seconds; the value is clamped to a minimum of 20 seconds.
- The next request refreshes the token after half the TTL: the refresh threshold is acquisition time plus TTL times 500 milliseconds. This is request-driven, not a periodic background timer.
- If refreshing fails, a cached token may still be reused until acquisition time plus TTL times 1000 milliseconds minus 2000 milliseconds. After that it fails with an authorization error.
- Each authorized playlist URI replaces `t` and `_` query parameters with the current token and timestamp while retaining other query parameters.
- On HTTP 403 for a playlist with a configured token API, it clears the cached token/thresholds and retries once with a freshly obtained token.
- Resolver `u44` recognizes the Edge page and returns manifest, token API (as stream API), type 4 and origin/referrer headers; the playback data-source factory holds shared token state.

These are static instruction findings, not a timed remote-expiry playback experiment. They identified the additional parity gap addressed below.

## NikTV request-time renewal implementation

In token-aware mode, recognizable Edge HTML supplies the manifest, token API and embedding origin directly. Existing Edge manifests with a same-origin `/api/playlist-token` stream API are also recognized. The resolved Sports stream carries a separate `edgeTokenApi`; other sources retain their normal handoff. Legacy mode bypasses this recognition and retains the previous extraction path.

The Sports player supplies a session-scoped Media3 data-source wrapper. Same-origin `.m3u8` loads obtain the cached token, renew at half TTL, replace only `t`/`_`, and retry HTTP 403 once after invalidating the rejected token. Concurrent loaders share token state; rejection of an older token does not erase a newer token. Refresh failure can reuse a still-valid cached token until TTL minus two seconds. Token responses are bounded to 64 KiB; token values are neither persisted nor logged, and the token endpoint does not follow redirects. Segments and cross-origin manifests are not given the session token.

Token-protected streams use ExoPlayer or Flow so that playlist loads pass through renewal. A saved VLC/MPV choice temporarily falls back to ExoPlayer for that stream, with feedback and a compatible player picker. The saved choice is not overwritten by the fallback. Ordinary Sports sources keep their player choices; this patch does not change IPTV code. Changing compatibility mode applies to the next source resolution, not an already-playing stream.

This does not promise recovery when the provider removes a stream, rejects token issuance, or changes its protocol. Unrecognized web-player pages still use the existing token-aware WebView path and have no new native renewal guarantee.

Renewal verification: 9 focused unit tests passed (3 renewal/parser cases and 6 extraction cases). Two device cases passed on each of Poco X3 Pro (1.071 seconds) and Pad 5 tablet (0.955 seconds): actual HTTP playlist loads renew at half TTL and retry 403 once, while Edge source recognition enables native renewal only in token-aware mode. The HTTP case also checks preservation of unrelated query parameters, bounded repeated 403 failure, and unchanged segment requests. Clock advancement simulates expiry; this is not a long-running remote-event playback test. Isolated and normal builds passed. Updated normal Dev APKs installed successfully on Poco, tablet, Samsung and Fire TV, preserving app data. Logs: `token-renewal-build.log`, `token-renewal-device-build.log`, `token-renewal-normal-build.log`, `token-renewal-poco-test.log`, `token-renewal-tablet-test.log`.
