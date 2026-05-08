<div align="center">

<a href="https://github.com/unseensnick/yokai-y2k">
    <img src="./.github/readme-images/app-icon.webp" alt="Yokai logo" height="200px" width="200px" />
</a>

# Yōkai-Y2K

</div>

<div align="center">

A free and open source manga reader

[![CI](https://github.com/unseensnick/yokai-y2k/actions/workflows/build_push.yml/badge.svg?labelColor=27303D)](https://github.com/unseensnick/yokai-y2k/actions/workflows/build_push.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/unseensnick/yokai-y2k?labelColor=27303D&color=0877d2)](/LICENSE)

<img src="./.github/readme-images/screens.gif" alt="Yokai screenshots" />

## Download

[![Yokai-Y2K Stable](https://img.shields.io/github/v/release/unseensnick/yokai-y2k?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/unseensnick/yokai-y2k/releases)

*Requires Android 6.0 or higher.*

## About Fork

This fork was created for personal usage. The name Yōkai is chosen in theme of my "paranormal" fork collection — all made for personal purposes, to pick up the language along the way or simply add my own twists that may not be accepted by upstream as a PR.

Updates are sporadic, sometimes fast, sometimes slow.

The goal is to stay in sync with upstream Yōkai as closely as possible, while layering on personal features that suit my needs.

## Features

<div align="left">

<details open="">
    <summary><h3>From Yōkai-Y2K</h3></summary>

* **Multi-source manga grouping** — manga entries sharing the same title are automatically grouped into a single library card with a source-count badge. Switch between sources from the manga details screen via a chip row.
* **Manual merge/unmerge** — manually merge any two library entries into one group, or long-press a source chip to remove it from a group. Use "Merge selected" in library multi-select to combine entries.
* **Manage sources sheet** — accessible from the manga details overflow menu; search for and add other library entries to the current manga's source group.
* **Category sort order** — new setting under Settings → Library to sort categories alphabetically (A→Z or Z→A) instead of manual order.
* **Category bulk delete** — long-press any category in Settings → Library → Edit categories to enter multi-select mode; select as many categories as needed and delete them all at once with a single confirmation and an undo snackbar.
* **FlareSolverr support** — configure a [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) service URL under Settings → Advanced → Network to automatically bypass Cloudflare protection on supported sources, with WebView as a fallback.

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
* Tracker support:
  [MyAnimeList](https://myanimelist.net/),
  [AniList](https://anilist.co/),
  [Kitsu](https://kitsu.app/explore/anime),
  [Manga Updates](https://www.mangaupdates.com/),
  [Shikimori](https://shikimori.one),
  and [Bangumi](https://bgm.tv/) support.
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
* An expanded toolbar for easier one handed use (with the option to reduce the size back down).
* Floating searchbar to easily start a search in your library or while browsing.
* Library redesigned as a single list view: See categories listed in a vertical view, that can be collapsed or expanded with a tap.
* Staggered Library grid.
* Drag & Drop Sorting in Library.
* Dynamic Categories: Group your library automatically by the tags, tracking status, source, and more.
* New Recents page: Providing quick access to newly added manga, new chapters, and to continue where you left off in a series.
* Stats Page.
* New Themes.
* Dynamic Shortcuts: open the latest chapter of what you were last reading right from your homescreen.
* [New material snackbar](.github/readme-images/material%20snackbar.png): Removing manga now auto deletes chapters and has an undo button in case you change your mind.
* Batch Auto-Source Migration (taken from [TachiyomiEH](https://github.com/NerdNumber9/TachiyomiEH)).
* [Share sheets upgrade for Android 10](.github/readme-images/share%20menu.png)
* View all chapters right in the reader.
* A lot more Material Design You additions.
* Android 12 features such as automatic extension and app updates.

</details>

</div>

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

<div align="left">

<details><summary>Issues</summary>

**Before reporting a new issue, take a look at the [changelog](https://github.com/unseensnick/yokai-y2k/releases) and the already opened [issues](https://github.com/unseensnick/yokai-y2k/issues).**

</details>

<details><summary>Bugs</summary>

* Include version (**Settings → About → Version**).
  * If not latest, try updating, it may have already been solved.
  * Dev version is equal to the number of commits as seen in the main page.
* Include steps to reproduce (if not obvious from description).
* Include screenshot (if needed).
* If it could be device-dependent, try reproducing on another device (if possible).
* For large logs use [Pastebin](https://pastebin.com/) (or similar).
* Don't group unrelated requests into one issue.

</details>

<details><summary>Feature Requests</summary>

* Write a detailed issue, explaining what it should do or how.
  * Avoid writing just "like X app does"
* Include screenshot (if needed).

</details>

</div>

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/unseensnick/yokai-y2k/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=unseensnick/yokai-y2k" alt="Yokai app contributors" title="Yokai app contributors" width="600"/>
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
