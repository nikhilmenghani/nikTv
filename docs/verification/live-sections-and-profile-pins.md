# Live TV sections and profile PINs

All shows pinned shortcuts before the complete provider list. Both copies have distinct stable keys; the original keeps its provider position. Headings are excluded from logical D-pad indices, including partial pinned rows. All playback reconstructs the shortcut prefix and complete provider queue; Pinned uses a fixed pinned-only queue without another prefix.

Profile PINs are four digits. `2564` is the requested universal override and the initial PIN for existing profiles without a custom PIN. New-profile setup offers a PIN after saving; existing profiles offer **Profile PIN** in their hold/menu actions. Setting an existing PIN requires the current PIN or override. Successful opening is remembered only on that account/profile/device and bound to the current verifier. Deletion always asks again. Changed PINs invalidate old unlock markers when the new profile record syncs. Existing sessions are not interrupted.

Only a salted PBKDF2 verifier is stored in the synced profile record; the KDF works on the minimum supported Android API. Hashing runs off the UI thread. Ordinary profile edits preserve the current verifier, preventing a stale setup screen from erasing a newer PIN. PIN dialogs use the shared remote/touch editor behavior and do not open the keyboard on focus.

Validation:

- Poco's isolated Live TV device test passes All/Pinned queue contexts, D-pad navigation, deep pin/unpin at channel 65, original tile/focus/viewport retention and external pin updates.
- Poco's PIN device tests pass wrong-PIN rejection, owner/override authorization, account/profile isolation and invalidation after a PIN change.
- Focused unit tests pass section-index mapping, provider occurrence identity, PIN verification and preserving/serializing a PIN through a stale profile edit.
- The actual Dev startup on Poco was checked: Cancel returns to Who's watching; the supplied override opens the saved profile/portal; a subsequent process restart restores it without another PIN prompt.
- Caption follow-up tests also pass exact paused MKV seeks/cold resume/language selection/Off and native VLC top/bottom caption bounds across control hide/show.

Fire TV was not used. Existing Dev setup was retained. No real profile PIN was changed during testing.
