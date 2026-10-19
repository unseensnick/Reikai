# Demo library

What the showcase demo library holds, so a rebuilt demo backup matches the screenshots. Captures run in
the benchmark build (`app.reikai.benchmark`), never in a debug library; see `docs/dev/readme-showcase.md`.

## Series

The demo backup is cut from a full library backup: these series with every source merged with them,
their tracker bindings and history, and nothing else. Every series with more than one source is
merged.

Manga:

| Series | Sources | Category |
|---|---|---|
| The Eminence in Shadow | Aqua Manga, MangaFire | Default |
| Solo Leveling | Manga Demon, Manganato, Weeb Central, Asura Scans, Aqua Manga, MangaFire | Default |
| A Ranker's Guide To The Good Life | Manganato, MangaK, Manga Demon | Reading |
| Almighty Pig Slaughtering Knife | Manganato, MangaK, Manga Demon, Aqua Manga | Reading |
| The Beginning After the End | Suwayomi (three entries), MangaFire | Reading |
| Overgeared | Manganato, Manga Demon, Weeb Central, MangaK | Plan to read |
| Omniscient Reader's Viewpoint | Manga Demon, Manganato, Weeb Central | Plan to read |

Novels:

| Series | Sources | Category |
|---|---|---|
| Catastrophic necromancer | Novel Fire, Novel Arrow | Default |
| SSS Awakening: I Can Class Change at will | Novel Fire, Novel Arrow | Default |
| Lord of the Mysteries | Novel Fire, Novel Arrow | Reading novels |
| Shadow Slave | Novel Fire, Novel Arrow, ReadNovelFull | Reading novels |
| Cultivation Online | Novel Fire, Novel Arrow | Novels to read |
| E-class is the Weakest? My Clones are All God-level! | Novel Fire, Novel Arrow | Novels to read |

The June 2026 demo backup (`demo_2026-06-21_04-51.tachibk`) had the same titles on fewer sources,
plus The Ranker's Guide to Live an Ordinary Life on Aqua Manga, which that source no longer loads.
Novel Bin is gone: its cover address no longer resolves.

## The saved backup

`demo_2026-10-19_showcase.tachibk` holds no logins: the tracker sign-ins, the self-hosted servers'
addresses and passwords, and plugin data for series outside the demo are stripped. Restoring it gives
the library and settings; sign in to the trackers again for the tracking charts, and set the server
sources up again for The Beginning After the End's Suwayomi entries.

## Categories

Manga: **Reading**, **Plan to read**. Novels: **Reading novels**, **Novels to read**. The novel ones
carry their own names because a category belongs to one content type, so the library's All view
lists both types' categories side by side.

## Settings the screenshots rely on

- Theme follows the system (`pref_theme_mode_key = SYSTEM`), cover-based theme on, Neutral style.
- Library: comfortable grid, 3 columns, alphabetical ascending, download and language badges on,
  library content chip on All.
- Panels 1 and 2 switch **Show all categories in one list**; panel 5 needs **Combine Updates and
  History** off.
- Reader defaults: manga tap zones Default, novel reading mode Scrolling.
- Tracking: **Update progress after reading** off, **Update progress when marked as read** Never,
  **Tell source sites about migrations** off, RanobeDB's sync while reading off, so reading in the
  demo never writes to a real tracker account.
- Repos: all of the backup's repos trusted on the Restore screen, so every source loads and nothing
  shows a warning badge.
