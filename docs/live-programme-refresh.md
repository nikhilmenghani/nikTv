# Live programme freshness

On 1 October 2026, the Fire TV investigation found successful Wio responses with empty schedules: two sampled short-guide requests returned `js: []`, and the shared guide listed 8,507 channel IDs with empty schedules. The saved actor-channel schedules were from earlier days. An empty tile in this case is not evidence of a failed network request or parser failure.

Programme handling now follows these rules:

- A successful channel-guide response replaces the saved guide, even when empty. It cannot revive the previous inline programme title.
- Failed requests do not replace the guide. All current-programme labels still validate broadcast times (and the five-minute freshness window for untimed entries).
- Search results, player summaries, channel supporting text, and showcase details use the same current-programme selection as modern live tiles.
- Current implementation (reviewed 24 October): nonempty shared guides are reused for 30 minutes and empty guides for five minutes. Manual refresh bypasses the warm-current shortcut but retains a 60-second shared-download anti-repeat guard. See [request/response reference](programme-info-requests-and-responses.md) for all three Stalker actions and the separate Xtream path.
- Visible-tile attempt spacing is approximately two minutes for incomplete guides, combined with the retry policy: missing programmes and failures defer that channel for five minutes; repeated failures can defer the profile too. Once valid data returns, an eligible successful retry can populate the tile without restarting. An empty response cannot be turned into programme information by the app.
- Refreshing guide fields does not alter stream URLs, commands, or player selection. No Room storage is involved.

Regression coverage includes HTTP empty-guide responses through the real Stalker client, manual cache bypass, subsequent provider recovery, expiry boundaries, and clearing programme fields across the browse cache and active player queue without changing playback.
