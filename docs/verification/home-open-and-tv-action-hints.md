# Home Open and TV action hints — 2026-11-14

Home's primary hero action now opens the selected title's details instead of starting playback. Its caption is Open, and the redundant trailer, Info and category shortcuts are omitted from the hero. Trailer and playback controls remain on details. This applies to the shared Home hero in portrait, tablet, TV and mobile landscape; Movies/Series browsing and My List retain their existing playback actions.

TV icon actions keep their dimensions while focused. A non-focusable caption appears below the action (above it near the bottom edge), without expanding the row or intercepting Back/D-pad events. Touch layouts retain their existing behavior.

The recycled feed previously restored the actual saved control only on the IPTV Series destination; Home returned focus through the outer Compose/native bridge. All recycled detail returns now use the existing native saved-focus owner. The primary Open control also retains the old Info return requester, so the standard hero returns to its surviving control. Saved Home Info identities map to Open.

Validation: dev assembly and isolated regression assembly passed. Three targeted device regressions passed on tablet: classic TV Home Open/Back and media identity; recycled Home Open/Back and D-pad rows; TV action dimensions. Fixture media do not access credentials, start playback or change personal viewing history.

Real Fire TV: installed dev build preserving setup. Open showed VisionQuest series details; Back restored Open; Down reached the original Continue Watching row. Up/Right reached My List and displayed its caption with an unchanged button footprint. Screenshots verified rounded focus bounds. These are destination/geometry checks, not frame-by-frame proof of no intermediate focus flash. Media mute was verified and system navigation sounds disabled; no media was played.

Evidence outside Git: `D:/workspace/niktv-playback-tests/firetv-home-open-current.png`, `firetv-home-open-detail-ready.png`, `firetv-home-open-return.png`, `firetv-home-open-return-down.png`, `firetv-home-open-hint.png`, `home-open-targeted-device-tests.log`, `home-open-tooltip-build.log` and `home-open-regression-build.log`.

Previous completed menu/fade work was committed separately as `a4e9dab3`. Home Open and TV hints are included in release 0.6.13.

Poco: installed NikTV Dev preserving setup, checked the simplified hero in portrait and landscape, and used touch Open/Back in both orientations. Details retained Play and Watch trailer; Home retained Open. Restored automatic rotation to its original setting. The updated dev build is also installed on tablet after the isolated tests. Mobile evidence: `poco-home-open-current.png` and `poco-home-open-landscape.png` in the same evidence directory.
