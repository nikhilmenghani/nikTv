# Organized IPTV browsing

## Findings from the Nikhil profile

Inspected the connected Xiaomi tablet on 1 October 2026, across Live TV, Movies,
and Series, and exercised the provider catalogues through the app.

| Area | Provider organization | More useful entry points |
| --- | --- | --- |
| Live TV | Country/language prefixes, sports leagues, news, music, kids, regional TV and many 24/7 categories | Language, region, genre/theme, all channels, personal collections |
| Movies | Language, year, quality, CAM, dubbed titles, streaming services, actors and franchises | All movies, language across years, quality filters, themes, personal collections |
| Series | Hundreds of services and broadcasters, plus regional/language and themed categories | All series with title search, service/network, explicit language labels, personal collections |

Examples included English FHD years, Tamil and Malayalam 4K years, Hindi dubbed
movies, actor/franchise collections, Sony and Zee networks, Netflix, Prime, and
many international broadcasters. Live category labels do not reliably describe
every channel's country or audio language. `INDIAN` is not treated as `Hindi`.

## Implemented

- Xtream tabs offer Collections, My collections, Language, Region, Genre & theme,
  Service & network, Quality, and Provider categories.
- Collection membership comes from category labels and original category IDs.
  Unknown labels remain reachable through All titles and Provider categories.
- The `+` control creates a named collection from multiple provider categories.
  Definitions and browse mode persist per profile and media type. Long-press a
  custom collection to remove it.
- Collection search checks all loaded titles locally, with multiple query words.
  Within a combined collection, the source filter can select one provider
  category or a quality such as FHD across all its source categories.
- Original provider IDs, playback commands, and alternate versions are retained.
  Deduplication uses IDs, not titles.
- Sensitive categories remain in the original, gated provider view and are not
  included in ordinary automatic/custom collections. Hidden sources cannot
  reappear through a previously saved collection definition.

## Fetching and performance

One full Xtream response per media type supplies combined collections. The first
open prepares the catalogue; it does not block profile startup by downloading all
three catalogues. The existing file search cache stores the complete response,
marked with complete scope `*`. A single current catalogue is retained in memory.
Fresh cache hits avoid another provider catalogue request. The configured cache
interval and explicit collection refresh govern replacement.

Collections are filtered views of this shared data, not separate full disk
copies. Filtering runs off the main thread. Tiles are exposed in local batches
of 120, with scrolling/loading more reading the existing result set. Xtream
movie/series provider categories also expose their complete response for local
title search; the server already returns that whole category.

Programme lookup remains limited to visible channels and explicit per-channel
refresh. Combined Live TV search matches names and cached programmes; it does
not launch thousands of guide requests. Original provider categories retain
their existing explicit whole-category programme search.

No Room storage or playback-engine changes were introduced.

## Tablet observations

Measured catalogue preparation in this session, not a network-speed guarantee:

| Catalogue | Returned records | Preparation time |
| --- | ---: | ---: |
| Movies | 64,960 | 2,582 ms |
| Live TV | 11,414 | 832 ms |
| Series | 17,340 | 1,282 ms |

English combined 18 provider categories and 34,649 movie entries. Local search
for `godfather` returned 11 matches; FHD refinement returned 2. A custom collection
opened with a logged local catalogue hit. Live Hindi showed programme information.
All-series search for `kapil` found both Kapil Sharma shows and opened the episode
screen correctly.

## Limits and next improvements

Facets describe provider labels, not independently verified audio tracks, genres,
ratings, or releases. Do not infer a show's language solely from its network.
Services with unrecognized labels remain available in Provider categories and
All series. Some shows also appear in the provider's movie catalogue; cross-type
global search remains useful for those entries.

Useful next steps are editable collection membership and optional curated
language/network mappings, followed by metadata-backed genre/year sorting where
the provider or TMDB actually supplies reliable fields. Avoid mandatory TMDB
lookups for every item at profile startup.
