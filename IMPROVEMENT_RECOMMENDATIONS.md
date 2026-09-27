# NikTV Improvement Recommendations

This document records the next recommended improvements for NikTV. The order reflects expected impact on stability and perceived performance. These recommendations should use the established storage implementation and must not depend on the separate, untested Room WIP.

## 1. Contain memory use

Samsung crashes demonstrated that provider responses and catalogs can exceed the practical Android heap limit. NikTV now rejects oversized Stalker JSON responses and limits diagnostic previews, but memory containment should remain the highest priority.

- Keep only fields needed for search and browsing in memory.
- Avoid retaining duplicate catalogs in the ViewModel, network cache, and decoded disk cache.
- Release profile-specific and search-specific data when leaving a profile or closing search.
- Respond to Android memory-pressure callbacks by clearing nonessential catalogs and image state.
- Decode artwork at its displayed dimensions and keep image-cache limits appropriate for phones and TV devices.
- Record response size, item count, retained catalog size, and memory pressure without logging credentials or full provider responses.
- Ensure malformed provider data fails only the current operation.

### Validation

- Repeatedly open search, browse categories, play media, and return for at least 30 minutes.
- Confirm memory returns to a stable range instead of increasing after every cycle.
- Test unusually large and malformed provider responses.
- Confirm playback continues when an unrelated catalog request fails.

## 2. Make search local-first

Nikhil's Xtream service returns complete media catalogs instead of providing an efficient title-search endpoint. A cold provider search can therefore take tens of seconds.

- Refresh the persisted catalog quietly after profile login or during an idle period.
- Build a compact normalized search index using the current storage implementation.
- Search the local index as the user types and display results immediately.
- Refresh stale provider data in the background without clearing visible results.
- Reserve **Search provider again** for an explicit forced refresh.
- Parse each persisted catalog once per search rather than decoding equivalent caches repeatedly.
- Reuse an already downloaded Xtream catalog for subsequent searches.

### Validation

- Measure cold startup search, warm search, and explicit provider refresh separately.
- Ensure cached results appear without a network connection.
- Confirm catalog refreshes do not block typing, scrolling, or playback.
- Test large movie, series, and live-TV catalogs independently.

## 3. Consolidate programme requests

Programme information should update predictably without excessive provider traffic or distracting status messages.

- Schedule a refresh near the current programme's end time.
- Prioritize the focused tile and tiles currently visible on screen.
- Cancel work for tiles that leave the screen.
- Share programme results between Home, Live TV, and the player queue.
- Deduplicate simultaneous requests for the same channel.
- Preserve the provider wait countdown for manual refreshes only.
- Keep automatic refresh activity silent unless a real error needs user action.
- Continue using full-guide fallback when a provider's short-guide response is empty.

### Validation

- Leave a channel tile visible across a programme boundary and confirm it updates automatically.
- Navigate rapidly through channels and verify stale work is cancelled.
- Confirm the same channel does not trigger duplicate requests from multiple screens.
- Test both Wio and Nikhil profiles.

## 4. Stress-test player reliability

Both Media3 and VLC now expose embedded audio tracks dynamically. Longer testing is needed to detect lifecycle leaks and provider-specific playback failures.

- Exercise repeated channel, movie, and episode changes for 30–60 minutes.
- Verify Media3-to-VLC switching releases the previous player and preserves position.
- Preserve selected audio track, subtitle state, progress, and playback options when appropriate.
- Track reconnect frequency and distinguish provider stalls, network loss, decoder errors, and malformed streams.
- Treat incorrect provider title-to-stream mappings separately from player failures.
- Ensure background catalog and programme work cannot interrupt active playback.

### Validation

- Test multi-audio media in both players.
- Switch players repeatedly and monitor Java and native memory.
- Test pause, resume, seek, next/previous, backgrounding, and screen rotation.
- Run separate sessions on Samsung, tablet, and Fire TV.

## 5. Refine the player queue

Touch devices now use programme-aware live-channel tiles in a compact single-row queue. Samsung is configured to show four complete tiles across the usable width.

- Verify long channel and programme titles at the device's actual font scale.
- Check four-tile sizing across smaller phones and larger tablets.
- Keep the programme progress bar visible without increasing queue height.
- Preserve enough exposed video area while the queue is open.
- Confirm long-press actions, pinning, favorites, and manual programme refresh remain accessible.
- Test portrait and landscape layouts where supported.

### Validation

- Capture screenshots on Samsung and the tablet with short and long programme names.
- Confirm no text or progress bar is clipped.
- Confirm horizontal scrolling and focus restoration remain smooth.

## 6. Measure navigation performance

Frame timing should guide further animation and rendering changes.

- Add release-profile Macrobenchmark coverage for Home, Wio Live TV, search, category browsing, and the player queue.
- Record slow and frozen frames during D-pad and touch navigation.
- Limit preloading to the next likely row or small adjacent window.
- Prevent programme-clock updates from recomposing an entire grid.
- Keep image decoding, shadows, scaling, and metadata processing away from navigation-critical frames.
- Use device-specific measurements before reducing visual quality or functionality.

### Validation

- Compare frame timing before and after each optimization.
- Test Wio separately because its heavier catalog is the most useful stress case.
- Test Fire TV D-pad input separately from Samsung and tablet touch input.

## 7. Isolate provider failures

Provider errors should be actionable and confined to the operation that encountered them.

- Classify authentication failures, throttling, timeouts, malformed responses, oversized responses, and unavailable streams.
- Show a short user-facing explanation and keep technical diagnostics available separately.
- Never include full provider payloads or credentials in diagnostics.
- Allow retry and player switching when those actions can address the failure.
- Keep playback alive when background metadata work fails.

### Validation

- Simulate each error class and verify its message and recovery action.
- Confirm repeated failures do not grow memory or trigger request storms.
- Confirm logs contain enough context to diagnose the request without sensitive data.

## Recommended implementation order

1. Memory containment and catalog lifecycle work.
2. Local-first search indexing using the current non-Room storage path.
3. Programme request consolidation and expiry scheduling.
4. Player lifecycle and reconnect stress testing.
5. Player queue layout checks across device sizes.
6. Macrobenchmark-driven navigation improvements.
7. Structured provider error handling and diagnostics.
