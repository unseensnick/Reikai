<div align="center">

<a href="https://github.com/unseensnick/Reikai">
    <img src="./.github/readme-images/app-icon.webp" alt="Reikai logo" height="200px" width="200px" />
</a>

# Reikai

### One library for manga and light novels

A free and open source reader for Android, built on Mihon.

[![Reikai Stable](https://img.shields.io/github/v/release/unseensnick/Reikai?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/unseensnick/Reikai/releases) [![CI](https://github.com/unseensnick/Reikai/actions/workflows/build_check.yml/badge.svg?labelColor=27303D)](https://github.com/unseensnick/Reikai/actions/workflows/build_check.yml) [![License: Apache-2.0](https://img.shields.io/github/license/unseensnick/Reikai?labelColor=27303D&color=0877d2)](/LICENSE)

*Requires Android 8.0 or higher.*

<img src="./.github/readme-images/screens.webp" alt="screenshots" />

</div>

## About

Reikai (霊界, "spirit world") is a personal manga and light-novel reader for Android, built on [Mihon](https://github.com/mihonapp/mihon) (Tachiyomi lineage). It started as a fork of [Yōkai](https://github.com/null2264/yokai) and was later rebased onto Mihon.

Two things set it apart from the lineage: **manga and light novels share one library** as equal content types, with the same layout and interactions; and it adds **multi-source power features** the lineage lacks, like folding the same series from several sources into a single entry, and ordering categories per library.

It is built first for my own daily use, so development is sporadic and the feature set follows my taste rather than a broad roadmap. It rides Mihon's actively maintained base for the core reader and layers these features on top.

## Features

Grouped by where each one comes from: what's original to Reikai, the light-novel layer, what's adapted from other forks, and what's inherited from the Mihon base.

### Unique to Reikai

Features built for this fork that the lineage doesn't have:

- **Multi-source grouping** (manga and novels): same-title entries from different sources fold into a single library card. Switch between sources with chips on the detail screen without losing progress or tracker links. It is preference-based rather than a database merge table, so it stays lightweight and the grouping is trivially reversible ([docs](docs/multi-source.md)).
- **Manual merge / unmerge**: hand-pick a set of library entries and merge them into one group yourself, for when the same series carries different titles across sources (romanization variants, for example) and auto-grouping doesn't catch them; unmerge splits a group back into standalone entries. Group-wide removal is available from the **Manage sources** dialog ([docs](docs/multi-source.md)).
- **Tracker sync across grouped sources**: add a tracker on one source in a group and it is shared across the others; each source keeps the tracker if you later split the group ([docs](docs/tracker-sync.md)).
- **Category sort order & bulk delete**: order categories Off / A→Z / Z→A everywhere they appear, and multi-select to delete several categories at once with undo. Both work for manga and novel categories ([docs](docs/categories.md)).
- **Taste-profile recommendations**: a tag-preference profile built from your tracker libraries personalizes the related row on manga details. It adds taste-driven candidates (tag searches on the current source, and cross-recommendations from your top tracked titles), reranks the row against your taste, and offers optional status-aware hide filters plus a full-screen *See all* browse with bulk add-to-library ([docs](docs/related-mangas.md#taste-profile)).
- **FlareSolverr support**: route a Cloudflare-blocked source through a [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) proxy when the in-app WebView can't solve it. WebView stays the default and the fallback ([docs](docs/flaresolverr.md)).
- **Library update-errors screen**: an opt-in, persistent list of entries that failed their last update, grouped by reason (Settings → Advanced).

### Light novels

Light novels are a first-class content type: one library behind a Manga / Novels chip, with their own categories, dynamic grouping, search, filter / sort, and category hopper, plus Reikai's multi-source merge, tracker sync, reading history, offline downloads, background updates, tracking (AniList / MyAnimeList / MangaUpdates / Kitsu), per-novel notes, and backup.

- **Sources and reader come from [LNReader](https://github.com/LNReader/lnreader)**: novel sources use LNReader's JavaScript plugin format, and the reader uses LNReader's web typography (both ported).
- **Everything around them is Reikai's own**: the plugins run in a headless QuickJS host (no WebView), so novel sources work in the background like manga extensions, and the library integration, cross-source merge, tracking, downloads, and backup are all net-new.

### Adapted from Komikku

- **Related-mangas carousel** on manga details: similar titles below the description, pooled and deduplicated from three streams (the source's own related-manga API, a keyword-search fallback, and public tracker recommendations from AniList, MyAnimeList, MangaUpdates, and Shikimori). The carousel and these baseline streams come from [Komikku](https://github.com/komikku-app/komikku); Reikai's personalization layer on top is listed under *Unique to Reikai* above ([docs](docs/related-mangas.md)).

### Library experience (TachiyomiJ2K lineage)

From the [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K) lineage by way of Yōkai, rebuilt on Mihon so they sit alongside Mihon's own tabbed library:

- **Single-list view with a category hopper**: an optional one-scroll view of collapsible categories with a floating jump-to puck, plus per-category sort, refresh, and select-all.
- **Dynamic grouping**: group the library by source, tag, author, language, status, or tracking status instead of by category, in both views and for both content types.
- **Cover-color theming**: tint the reader and manga details with each entry's cover color.

<details>
<summary><strong>From the Mihon base</strong></summary>

The core manga experience is Mihon's (Tachiyomi lineage):

- Local reading of downloaded content.
- A configurable reader with multiple viewers, reading directions, and settings.
- Tracker support: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://www.mangaupdates.com/), [Shikimori](https://shikimori.one), and [Bangumi](https://bgm.tv/).
- Categories to organize your library.
- Light and dark themes.
- Scheduled library updates.
- Local backups, or to your own cloud storage.
- Third-party extensions and repository management.

</details>

## Contributing

[Code of conduct](./CODE_OF_CONDUCT.md) · [Contributing guide](./CONTRIBUTING.md)

This is a personal fork, so development is sporadic: bug reports are genuinely welcome, but pull requests may take a while and might not be merged if they don't fit the fork's direction. Report a bug with an [issue](https://github.com/unseensnick/Reikai/issues), and raise a feature idea or question in a [discussion](https://github.com/unseensnick/Reikai/discussions).

Reikai does not maintain or fix extensions/sources. Problems with a specific source or extension are out of scope.

## Acknowledgements

Reikai is a personal fork and stands on the work of the projects it builds on and borrows from:

- [Mihon](https://github.com/mihonapp/mihon): the base it is built on.
- [Yōkai](https://github.com/null2264/yokai): the previous base, where several of the features were first built.
- [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K): the single-list library and dynamic-grouping experience.
- [Komikku](https://github.com/komikku-app/komikku): the related-mangas carousel.
- [LNReader](https://github.com/LNReader/lnreader): the light-novel source format and reader.
- [Tachiyomi](https://github.com/tachiyomiorg) and its wider community, where the lineage began.

Thanks to everyone who contributed to those projects.

## Disclaimer

The developer of this application does not have any affiliation with the content providers available, and this application hosts zero content.

## License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2026 unseensnick

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>
