# NikTV UI conventions

- Follow NikTV's existing visual design when adding or changing screens. Reuse `NikColors`, existing action buttons, glass surfaces, rounded shapes, and remote focus frames.
- Keep layouts compact: restrained headings, small gaps, and controls sized for their content. Avoid oversized cards, excessive empty space, and expanding secondary actions inline.
- Keep the current profile tiles visible together on ordinary phone and tablet layouts. Allow scrolling for larger profile collections and small windows.
- Preserve the established Home artwork, hero fading, and navigation styling unless the task explicitly changes them.
- Support touch, accessibility, keyboard, and TV remote navigation. Check relevant UI changes on phone and tablet; keep distinct actions as separate, reachable targets.
- Match press, hold, and focus feedback to the target's shape and bounds. Avatar tile feedback uses the avatar's rounded shape and must not cast a rectangular overlay behind its separate name label.
- On tablet/TV headers, keep Profile and Portals as independent glass tiles on the right; the clock is separate. Preserve the mobile header unless explicitly changing it.
- Network requests, sync merges, and storage decoding must not run on the UI thread or block navigation. Show quiet progress and recovery status using the existing visual language.

- Apply shared tablet/TV UI changes to mobile landscape as well, with compact proportions for short windows. Verify the production navigation entry path in portrait and landscape; testing an isolated component alone does not verify that the app opens it.
