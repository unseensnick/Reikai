<div align="center">

<a href="https://github.com/unseensnick/Reikai">
    <img src="./.github/readme-images/app-icon.webp" alt="Reikai logo" height="200px" width="200px" />
</a>

# Reikai

</div>

<div align="center">

A free and open source manga reader

[![CI](https://github.com/unseensnick/Reikai/actions/workflows/build_check.yml/badge.svg?labelColor=27303D)](https://github.com/unseensnick/Reikai/actions/workflows/build_check.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/unseensnick/Reikai?labelColor=27303D&color=0877d2)](/LICENSE)

<img src="./.github/readme-images/screens.gif" alt="screenshots" />

## Download

[![Reikai Stable](https://img.shields.io/github/v/release/unseensnick/Reikai?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/unseensnick/Reikai/releases)

*Requires Android 8.0 or higher.*

## About

Reikai (霊界, "spirit world") is a personal manga + light-novel reader **built on [Mihon](https://github.com/mihonapp/mihon)**. It started as a fork of [Yōkai](https://github.com/null2264/yokai) (妖怪, "spirit-creature") and is being rebased onto Mihon. Built for personal use; updates are sporadic.

The goal is to ride Mihon's actively maintained Compose base while layering on the features below, which suit my needs and aren't likely to land upstream as PRs.

> **Status:** the rebase onto Mihon is in progress, so feature availability is in flux while the features below are ported over from the Yōkai-based build. See [docs/dev/development.md](docs/dev/development.md) for the current state.

## Features

<div align="left">

<details open="">
    <summary><h3>Unique to Reikai</h3></summary>

* **Multi-source manga grouping**: same-title entries from different sources fold into a single library card with a source-count badge. Switch sources via chips on the detail screen without losing progress or tracker links ([docs](docs/multi-source.md)).
* **Manual merge / unmerge**: merge entries with different titles (e.g. romanization variants) or split a group back to standalone entries ([docs](docs/multi-source.md)).
* **Bulk-remove merged groups**: remove all sources from a group at once via Library multi-select ([docs](docs/multi-source.md#bulk-remove-all-sources-from-library)).
* **Tracker sync across grouped sources**: add a tracker on one source in a group and it propagates to all other sources automatically ([docs](docs/tracker-sync.md)).
* **Category sort order & bulk delete**: sort categories A→Z / Z→A, or delete multiple categories at once ([docs](docs/categories.md)).
* **Taste-profile personalization** of the related-mangas carousel: recommendations reranked against your tracked-tag preferences, with status-aware hide filters ([docs](docs/related-mangas.md#taste-profile)).
* **FlareSolverr support** for Cloudflare bypass on sources that block WebView ([docs](docs/flaresolverr.md)).
* **Light novels in your library**: a dedicated Novels tab brings light novels into the same library experience as manga (categories, dynamic grouping, search, filter, per-category sort, category hopper) — including Reikai's own multi-source grouping, manual merge/unmerge, and category sort order extended to novels, none of which LNReader has.

</details>

<details open="">
    <summary><h3>From Komikku</h3></summary>

* **Related-mangas carousel** on manga details: pulls from five independent streams (source API, keyword search, three tracker services) merged into one deduplicated row ([docs](docs/related-mangas.md)).
* **Full-screen "See all" browse** for related mangas, with bulk add-to-library ([docs](docs/related-mangas.md)).

</details>

<details open="">
    <summary><h3>From LNReader</h3></summary>

* **Light novel sources**: install and browse light novels via [LNReader](https://github.com/LNReader/lnreader)-format source plugins, hosted in-app next to your manga extensions, with update detection on the Browse tab.
* **Light novel reading** *(in progress)*: an in-app light-novel reader, source-catalog browsing, and novel details are being ported from LNReader.

</details>

<details open="">
    <summary><h3>From Yōkai</h3></summary>

* NSFW/SFW library filter (taken from [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY)).
* Fix backup incompatibility with upstream.
* New theme.
* Local Source chapters now read ComicInfo.xml for chapter title, number, and scanlator.

</details>

<details open="">
    <summary><h3>From upstream (Tachiyomi/Mihon)</h3></summary>

* Local reading of downloaded content.
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/explore/anime), [Manga Updates](https://www.mangaupdates.com/), [Shikimori](https://shikimori.one), and [Bangumi](https://bgm.tv/).
* Categories to organize your library.
* Light and dark themes.
* Schedule updating your library for new chapters.
* Create backups locally to read offline or to your desired cloud service.

</details>

<details>
    <summary><h3>From J2K</h3></summary>

* UI redesign.
* New Manga details screens, themed by their manga covers.
* Combine 2 pages while reading into a single one for a better tablet experience.
* Floating searchbar to easily start a search in your library or while browsing.
* Library redesigned as a single list view: categories in a vertical view, collapsible with a tap.
* Staggered Library grid.
* Dynamic Categories: group your library automatically by tags, tracking status, source, and more.
* New Recents page: quick access to newly added manga, new chapters, and where you left off.
* New Themes.
* Dynamic Shortcuts: open the latest chapter of what you were last reading right from your homescreen.
* [New material snackbar](.github/readme-images/material%20snackbar.png): removing manga auto-deletes chapters with an undo button.
* Batch Auto-Source Migration (taken from [TachiyomiEH](https://github.com/NerdNumber9/TachiyomiEH)).
* View all chapters right in the reader.
* Material Design You additions throughout.
* Android 12 features such as automatic extension and app updates.

</details>

</div>

## Contributing

This is a personal fork; pull requests may not be reviewed. Feel free to open an issue to report a bug.

<div align="left">

<details><summary>Bugs</summary>

* Include version (**Settings → About → Version**). Check the [changelog](https://github.com/unseensnick/Reikai/releases) and [open issues](https://github.com/unseensnick/Reikai/issues) first; it may already be fixed or tracked.
* Include steps to reproduce (if not obvious from description).
* Include screenshot (if needed).
* If it could be device-dependent, try reproducing on another device (if possible).
* For large logs use [Pastebin](https://pastebin.com/) (or similar).

</details>

<details><summary>Syncing from upstream</summary>

Upstream is now [Mihon](https://github.com/mihonapp/mihon); changes are ported manually (clone it locally, check what changed, apply the relevant diffs by hand, fenced with `// RK` markers). Reikai's own pre-rebase features are ported from the `design/library-compose` branch.

See [docs/dev/development.md](docs/dev/development.md#porting) for details.

</details>

</div>

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/unseensnick/Reikai/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=unseensnick/Reikai" alt="Reikai contributors" title="Reikai contributors" width="600"/>
</a>

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 null2264
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
