<div align="center">

<a href="https://github.com/unseensnick/Reikai">
    <img src="./.github/readme-images/app-icon.webp" alt="Reikai logo" height="200px" width="200px" />
</a>

# Reikai

### One library for manga and light novels

A free and open source reader for Android, built on Mihon.

| Releases | Preview |
| :---: | :---: |
| [![Stable](https://img.shields.io/github/v/release/unseensnick/Reikai?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/unseensnick/Reikai/releases) [![Stable downloads](https://img.shields.io/github/downloads/unseensnick/Reikai/total?maxAge=3600&label=Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF)](https://github.com/unseensnick/Reikai/releases) | [![Preview](https://img.shields.io/github/v/release/unseensnick/Reikai-preview?maxAge=3600&label=Preview&labelColor=2c2c47&color=1c1c39)](https://github.com/unseensnick/Reikai-preview/releases) [![Preview downloads](https://img.shields.io/github/downloads/unseensnick/Reikai-preview/total?maxAge=3600&label=Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF)](https://github.com/unseensnick/Reikai-preview/releases) |

*Requires Android 8.0 or higher.*

[![CI](https://github.com/unseensnick/Reikai/actions/workflows/build_check.yml/badge.svg?labelColor=27303D)](https://github.com/unseensnick/Reikai/actions/workflows/build_check.yml) [![License: Apache-2.0](https://img.shields.io/github/license/unseensnick/Reikai?labelColor=27303D&color=0877d2)](/LICENSE)

<img src="./.github/readme-images/screens.webp" alt="screenshots" />

## About

Reikai (霊界, "spirit world") is a personal manga and light-novel reader for Android, built on [Mihon](https://github.com/mihonapp/mihon) (Tachiyomi lineage). It started as a fork of [Yōkai](https://github.com/null2264/yokai) and was later rebased onto Mihon.

Two things set it apart from the lineage: **manga and light novels share one library** as equal content types, with the same layout and interactions; and it adds **multi-source power features** the lineage lacks, like folding the same series from several sources into a single entry, and ordering categories per library.

It is built first for my own daily use, so development is sporadic and the feature set follows my taste rather than a broad roadmap. It rides Mihon's actively maintained base for the core reader and layers these features on top.

## Features

<div align="left">

### Reikai's own features

- `Multi-source grouping` (manga and novels): fold same-title entries from different sources into one library card, with a per-source switcher that keeps progress and tracker links. Preference-based, so it's lightweight and reversible. ([docs](docs/multi-source.md))
- `Manual merge / unmerge`: group entries by hand when their titles differ across sources, or split a group back apart. ([docs](docs/multi-source.md))
- `Merge-aware reading`: read a merged series straight through every source in one sitting, with a unified chapter list and cross-source prev / next. ([docs](docs/multi-source.md#reading-a-merged-group))
- `Tracker sync` across grouped sources: a tracker added on one source is shared across the group, and survives a split. ([docs](docs/tracker-sync.md))
- `Category sort order` and `bulk delete`: order categories Off / A→Z / Z→A, and multi-select-delete with undo, for manga and novel categories. ([docs](docs/categories.md))
- `Light novels`, first-class: a full Manga / Novels chip library with merge, tracker sync, history, downloads, background updates, tracking, notes, and backup. Sources and reader come from [LNReader](https://github.com/LNReader/lnreader); the headless QuickJS host (no WebView) and the integration are Reikai's.
- `Taste-profile recommendations`: personalize the related row against your tracked-tag preferences, with status-aware hide filters and a full-screen *See all* browse. ([docs](docs/related-mangas.md#taste-profile))
- `FlareSolverr` support: route a Cloudflare-blocked source through a [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) proxy when the WebView can't solve it. ([docs](docs/flaresolverr.md))
- `Library update errors`: an opt-in, persistent list of entries that failed their last update.
- `Single-list library`, `category hopper`, `dynamic grouping`, and `cover-color theming`: the [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K)-lineage library experience, rebuilt on Mihon.

<details>
<summary><strong>Adapted from Komikku</strong></summary>

- `Related-mangas carousel` on manga details: similar titles below the description, pooled and deduplicated from three streams (the source's own related-manga API, a keyword-search fallback, and public tracker recommendations from AniList, MyAnimeList, MangaUpdates, and Shikimori). The carousel and these baseline streams come from [Komikku](https://github.com/komikku-app/komikku); Reikai's taste-profile layer on top is in the list above ([docs](docs/related-mangas.md)).
- `Adult sources` (E-Hentai / ExHentai): built-in browsing with full gallery filters and tag autocomplete, library tag indexing and search, a gallery-metadata viewer, account login / settings, a favorited-gallery update checker, and one-way favorites backup. From [Komikku](https://github.com/komikku-app/komikku) (Tachiyomi-SY lineage); Reikai ships a lighter slice for now. Off by default. ([docs](docs/adult-sources.md))

</details>

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

</div>

## Issues, Feature Requests and Contributing

<div align="left">

This is a personal fork: pull requests are welcome, but a PR may take a while and might not be merged if it doesn't fit the fork's direction. For anything beyond a small fix, raise it first.

<details><summary><strong>Bugs</strong></summary>

* Include your Reikai version (More → About → Version), Android version, and device.
* Check the [changelog](https://github.com/unseensnick/Reikai/releases) and [open issues](https://github.com/unseensnick/Reikai/issues) first; it may already be fixed or tracked.
* Include steps to reproduce (if not obvious) and a screenshot if it helps.
* If it could be device-dependent, try another device if you can.

Use the [bug report form](https://github.com/unseensnick/Reikai/issues/new?template=2_report_issue.yml) to submit a bug.
</details>

<details><summary><strong>Feature ideas and questions</strong></summary>

Open a [Discussion](https://github.com/unseensnick/Reikai/discussions). Reikai is shaped around one person's use, so a request might be built as asked, built as a variation that fits better, or not built at all; talking it through first is the most useful path.
</details>

<details><summary><strong>Contributing</strong></summary>

See [CONTRIBUTING.md](./CONTRIBUTING.md). Reikai does not maintain or fix extensions/sources; problems with a specific source or extension are out of scope.
</details>

<details><summary><strong>Code of Conduct</strong></summary>

See [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).
</details>

</div>

## Acknowledgements

<div align="left">

Reikai is a personal fork and stands on the work of the projects it builds on and borrows from:

- [Mihon](https://github.com/mihonapp/mihon): the base it is built on.
- [Yōkai](https://github.com/null2264/yokai): the previous base, where several of the features were first built.
- [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K): the single-list library and dynamic-grouping experience.
- [Komikku](https://github.com/komikku-app/komikku): the related-mangas carousel and the E-Hentai / ExHentai adult-source subsystem.
- [LNReader](https://github.com/LNReader/lnreader): the light-novel source format and reader.
- [Tachiyomi](https://github.com/tachiyomiorg) and its wider community, where the lineage began.

Thanks to everyone who contributed to those projects.

</div>

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

</div>
