# Programme information: requests and responses

Reviewed 24 October 2026 against commit `8b93ac4e`. This is a code trace, not a fresh provider capture. No private-provider requests were made for this document. Credentials, device identifiers and provider URLs are placeholders. Example programme names and timestamps below are synthetic.

## The three Wio / Stalker requests

All three are HTTP GETs to the authenticated session's discovered endpoint (`session.endpointUrl`), such as `<PORTAL>/server/load.php`. Do not assume that example path is the endpoint for every profile.

| Order | Purpose | Specific query parameters |
|---|---|---|
| 1 | Current/upcoming programmes for one channel | `type=itv&action=get_short_epg&ch_id=<CHANNEL_ID>&size=5` |
| 2 | Shared full-day guide for multiple channels | `type=itv&action=get_epg_info&period=1` |
| 3 | Date-specific guide for one channel | `type=epg&action=get_simple_data_table&ch_id=<CHANNEL_ID>&date=<YYYY-MM-DD>&p=0` |

These are fallback stages, not three unconditional requests per tile. The shared guide is reused across channels. `period=1` is the literal full-day request parameter; NikTV does not guarantee that the provider returns exactly 24 hours. The date-specific request uses today's date in the device's default timezone and page zero; this lookup does not loop over further guide pages.

### Common authentication and headers

`authorizedParams()` adds these query parameters to each Stalker request:

```text
auth_second_step=1
not_valid_token=0
metrics=<SESSION_DEVICE_METRICS_JSON>
hw_version=1.7-BD-00
hw_version_2=<SESSION_HARDWARE_IDENTIFIER>
timestamp=<CURRENT_UNIX_SECONDS>
api_signature=262
JsHttpRequest=1-xml
```

The HTTP builder supplies:

```http
Authorization: Bearer <SESSION_TOKEN>
X-Token: <SESSION_TOKEN>
X-User-Agent: Model: MAG424; Link: WiFi
Language: en-US
Accept: application/json, text/javascript, */*; q=0.01
Accept-Language: en-US,en;q=0.9
Referer: <DERIVED_PORTAL_REFERER>
Connection: keep-alive
Cookie: <PORTAL_COOKIE_JAR_VALUES>
```

It also adds two User-Agent headers: a QtEmbedded/Linux armv7l value and the MAG200/stbapp compatibility value defined by `USER_AGENT`. Portal cookies are seeded using the profile MAC address. Do not save real token, Cookie, metrics, MAC, hardware identity or authenticated request URLs in documentation/log captures.

### 1. Current programme: short EPG

```http
GET <SESSION_ENDPOINT>?type=itv&action=get_short_epg&ch_id=<CHANNEL_ID>&size=5&<COMMON_QUERY_PARAMETERS>
```

Illustrative response supported by the parser:

```json
{
  "js": [
    {"ch_id":"CHANNEL_A","name":"Example movie","start_timestamp":1792836000,"stop_timestamp":1792843200},
    {"ch_id":"CHANNEL_A","name":"Next movie","start_timestamp":1792843200,"stop_timestamp":1792850400}
  ]
}
```

`size=5` is intentional: the source comment records that some portals returned empty payloads for larger sizes. It is a request size, not proof that five entries will arrive. NikTV removes ended entries and sorts the remaining schedule. If no entry is current, it tries request 2, then request 3 as necessary.

### 2. Full-day/shared guide

```http
GET <SESSION_ENDPOINT>?type=itv&action=get_epg_info&period=1&<COMMON_QUERY_PARAMETERS>
```

Illustrative response shape:

```json
{
  "js": [
    {"ch_id":"CHANNEL_A","epg":[
      {"name":"Example movie","start_timestamp":1792836000,"stop_timestamp":1792843200},
      {"name":"Next movie","start_timestamp":1792843200,"stop_timestamp":1792850400}
    ]},
    {"ch_id":"CHANNEL_B","epg":[]}
  ]
}
```

Channel-keyed maps are also supported, for example `{"js":{"CHANNEL_A":[...]}}`. This endpoint uses the streaming guide reader, rather than loading the entire JSON response into a string. Successful responses are limited to 8 MiB, including responses with an unknown Content-Length. Memory pressure can defer the download. A shared download can be canceled to prioritize playback resolution.

The cached schedule is matched by provider channel ID, then `epgChannelId`. If it contains a currently running programme, it fills the tile even when the short endpoint did not provide one. Current/upcoming programmes can then advance locally by time without a request per programme. An exhausted cached channel window can trigger a shared-guide refresh.

### 3. Channel/date guide (calendar-style fallback)

```http
GET <SESSION_ENDPOINT>?type=epg&action=get_simple_data_table&ch_id=<CHANNEL_ID>&date=<YYYY-MM-DD>&p=0&<COMMON_QUERY_PARAMETERS>
```

Illustrative response supported by the parser:

```json
{
  "js": {
    "data": [
      {"name":"Earlier movie","start_timestamp":1792828800,"stop_timestamp":1792836000},
      {"name":"Example movie","start_timestamp":1792836000,"stop_timestamp":1792843200},
      {"name":"Next movie","start_timestamp":1792843200,"stop_timestamp":1792850400}
    ]
  }
}
```

Used when neither the short nor shared schedule yields a current programme. The requested channel supplies the identity if individual rows omit it. Ended entries are removed. Results are merged with the short schedule, deduplicated by `(title, start, end)`, and sorted. This matters when the short response contains tomorrow's entries but the date table contains the current programme. The normal JSON response bound is 32 MiB. An oversized date-guide response is handled without another bulk retry and without changing playback.

## Xtream / Nikhil equivalent

Xtream does not use the three Stalker actions above. Its current-programme path makes this separate GET:

```http
GET <XTREAM_BASE>/player_api.php?username=<REDACTED>&password=<REDACTED>&stream_id=<STREAM_ID>&limit=24&action=get_short_epg
User-Agent: NikTV/0.1 Android
Accept: application/json
```

Illustrative response:

```json
{
  "epg_listings": [
    {"title":"RXhhbXBsZSBtb3ZpZQ==","start_timestamp":"1792836000","stop_timestamp":"1792843200"}
  ]
}
```

The title decodes to `Example movie`. NikTV attempts Base64 decoding and otherwise uses the supplied title. The extractor accepts a root array or an object container named `epg_listings`, `listings`, `data`, or `epg`; a nested container object can hold the array. `limit=24` requests up to 24 entries; it does **not** mean 24 hours. This method has no Xtream full-day or channel/date fallback. Do not infer that Wio and Xtream have the same API coverage.

## Parsing and what becomes visible

- Stalker unwraps `js` and, where present, `data`. It supports arrays, channel maps and nested `epg`/`programs` arrays. Streaming shared-guide decoding is implemented separately in `StreamingPortalGuide.kt`.
- Guide row channel identity uses `ch_id`, then `channel_id`, then its containing channel key/requested channel.
- Stalker programme titles recognize `program_name`, `programme_name`, `title`, `name`, `cur_playing`, `playing`; generic channel `name`/`title` is not automatically treated as an inline programme.
- Timestamp aliases: start uses `start_timestamp`, `start_ts`, `start_time`; end uses `stop_timestamp`, `end_timestamp`, `stop_ts`, `end_ts`, `end_time`. Positive numeric values below 10,000,000,000 are converted from seconds to milliseconds; larger values are milliseconds. These aliases do not imply arbitrary date-string parsing.
- `LiveProgramme` stores title, start/end milliseconds and observation time. Unknown/missing programme-title placeholders are rejected.
- `currentLiveProgramme(now)` selects by broadcast time, never simply the first future row. Untimed entries have a five-minute freshness window. The same selector supplies tiles, player summaries and channel search. A progress bar needs valid start/end times.
- A successful empty response can clear old guide fields; a failed request does not replace them. Time validation still prevents expired entries from appearing current. Updating guide fields does not replace the stream command, seek, switch engines or restart playback.

## Cache, retries and manual refresh

| Mechanism | Current behavior |
|---|---|
| Shared guide identity | Hash of profile/credentials plus endpoint; token renewal can reuse metadata, different accounts cannot |
| Nonempty shared guide TTL | 30 minutes |
| Empty shared guide TTL | 5 minutes |
| Forced shared refresh | Still reuses a guide fetched less than 60 seconds ago; avoids repeated bulk downloads |
| Shared-download failure backoff | 5 minutes normally; 60 seconds for forced attempts; cached fallback may remain available |
| Stored shared-guide bounds | 5,000 channels, 20,000 rows, 2,000,000 title characters; useful timed entries within the next 48 hours |
| Missing current programme | Per-channel automatic retry delay of 5 minutes |
| Repeated request failures | Three failures within the policy window also quiet the profile for 5 minutes |
| Visible-guide scheduler | Approximately 2-minute minimum attempt spacing for incomplete guides, combined with retry-policy deadlines; known programme endings are eligible sooner, subject to those deadlines |
| Request gate spacing | Short guide: 250ms minimum; other Stalker requests: 1,000ms minimum; these are app scheduling values, not measured provider quotas |

Manual Force fetch programme uses a user-selected request and skips the warm-current-guide shortcut. It does not bypass provider HTTP 429/cooldown safeguards or the shared guide's 60-second anti-repeat protection. Provider waiting/countdown UI is associated with explicit manual refresh rather than displayed on every automatically loading tile. Work is on `Dispatchers.IO`; shared work is deduplicated, and canceling one consumer does not cancel other consumers' guide fetch.

## Actual response evidence versus examples

The older investigation recorded in `docs/live-programme-refresh.md` reported two Wio short responses with `{"js":[]}` and a shared guide containing 8,507 channel IDs with empty schedules on 1 October 2026. That is historical evidence, not a statement of today's provider health. The positive JSON bodies above are illustrative supported shapes, not live captures. Tests in `StalkerGuideResponseTest.kt` use a local HTTP fixture with real client parsing for empty short/shared responses, recovery through the date-specific endpoint, later short-guide recovery, and oversized date-guide handling.

No fresh provider status, response byte count, latency or current channel title was measured in this documentation task. A future live capture should record action, timestamp/timezone, status, Content-Type, body size, sanitized body and returned time coverage separately for each of the three actions; exclude authenticated URLs and device/account headers.

## Source map

- `app/src/main/java/com/nikhil/niktv/data/StalkerPortalClient.kt`: `playingChannelSchedule`, `requestSharedGuide`, `xtreamRequest`, `authorizedParams`, EPG extractors.
- `app/src/main/java/com/nikhil/niktv/data/StreamingPortalGuide.kt`: streaming shared-guide decoder.
- `app/src/main/java/com/nikhil/niktv/data/PortalGuideCache.kt` and `SharedPortalGuideCache.kt`: bounds, TTLs, deduplication and fallback.
- `app/src/main/java/com/nikhil/niktv/data/PortalRequestGate.kt`: request priority/spacing.
- `app/src/main/java/com/nikhil/niktv/ui/NikTvViewModel.kt`: visible/manual programme requests, session retry and publishing to UI/cache.
- `app/src/main/java/com/nikhil/niktv/ui/VisibleLiveGuideScheduler.kt` and `LiveGuideRetryPolicy.kt`: automatic eligibility/retries.
- `app/src/main/java/com/nikhil/niktv/model/LiveChannelSearch.kt`: current programme selection and freshness.
- `app/src/test/java/com/nikhil/niktv/data/StalkerGuideResponseTest.kt`: local request/response fixtures.
