# Shared portal menu verification — 2026-11-27

Scope: wide IPTV, Torrent, Sports, Playfy and Highlights menus, including mobile landscape. Portrait navigation keeps its existing layout.

The wide portals now share the IPTV menu shell, inline search position, stable header width, vertical options icon and explicit horizontal focus targets. Closing search clears its query and returns directly to Search. Keyboard dismissal keeps the editor open; the next Back closes search. An activation ticket prevents returning from details from reopening the keyboard. Live native IME visibility avoids stale insets retained by a moved Compose owner.

## Device checks

- Pad 5: the targeted IPTV search geometry/focus test and shared-portal menu test passed. Actual Torrent and Sports search opened the keyboard without moving the artwork.
- Poco X3 Pro: both targeted menu tests passed separately. Actual landscape Torrent and Sports menus were traversed in both directions; search submission, keyboard dismissal, close/reopen, Sports tab selection and portrait Back behavior were captured. Original automatic rotation was restored.
- Fire TV: actual Torrent, Sports and Playfy headers were traversed; floating-keyboard opening, query submission, keyboard Back, search close/reopen, options return and portal switching were captured without starting playback.

Local screenshots are retained under `D:/workspace/artifacts/menu-release-checks/`; they are not packaged with the app.

## Home hero correction

Torrent's recycled wide hero reserved the compact IPTV Home frame but rendered the larger noncompact hero on TV. Text consumed the action row's remaining height, reducing its buttons to thin strips. Rendering the same compact hero as IPTV preserves the frame and full-height controls, including long titles. Fire TV screenshots confirmed restored Open, add and carousel button geometry.

The same check exposed a separate shared-action focus reset. Conditional composition-local reads in the color argument produced different sibling groups on focused/unfocused paths; the runtime removed the inner Surface layout and returned focus to Open. The fix reads those locals unconditionally and passes pure styling values. The original clickable Surface, styling and navigation remain intact. The original tablet regression now passes full button-height checks and both directions through all four hero controls.

Final Fire TV traversal confirms Open → add → previous → next and the reverse retain focus with correctly aligned rounded borders. Captures: `firetv-hero-final-shapes/02-20.png` through `08-21.png`. No playback was started. Both tablet menu/search regressions and the Poco shared-menu regression passed again after this shared-control fix.

## Automated checks

- The affected 28 unit tests passed using the same private-configuration opt-out flags as CI.
- Targeted hero frame and recycled-position unit tests passed.
- Final debug lint passed (`build/menu-release-final-lint.log`, 2m54s).
- Source credential scan and release-pipeline prerequisite checks passed.

The full release CI matrix remains the publication gate. These menu checks do not claim playback, provider availability or unrelated screens were retested.
