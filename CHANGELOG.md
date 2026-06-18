# Changelog

All notable changes to this project will be documented in this file.

The format is simplified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/):
- `Additions` - New features
- `Changes` - Behaviour/visual changes
- `Fixes` - Bugfixes
- `Other` - Technical changes/updates

## [Unreleased]

### Additions

**Light novels**
- **First-class in your library.** A Manga / Novels chip switches the library between the two; novels get the same grid, grouping, badges, multi-select, and Filter / Sort / Display sheet as manga, with their own categories.
- **Browse and install novel sources.** Add LNReader plugin repos from the Repos screen and browse novel sources in a catalogue styled like manga: Popular / Latest, filters, source settings, search, in-library badges, long-press to add, and pin favorite sources to the top.
- **Global search across novel sources.** One query searches every installed novel source at once, each filling in its own row; filter by Pinned / All / Has results, and tap a source to open its full results.
- **A full novel details screen.** Matches the manga layout, with chapter multi-select, a Filter / Sort / Display sheet, hideable chapters, Edit info, WebView / Share, and per-page loading for huge chapter lists. Saved novels open instantly from local storage and refresh on demand.
- **A full-screen novel reader.** LNReader-style typography with a live Display / Theme sheet (fonts, size, spacing, margins, and light / sepia / mint / dark / black themes), saved scroll position, a prefetched next chapter, and tap-to-hide immersive mode.
- **Offline downloads.** Save chapter text with inline images, one at a time or in batches, on a single background queue that paces itself per source and resumes after a restart.
- **Background updates in the Updates tab.** Favorited novels re-check on a schedule (interval, device restrictions, category include / exclude, Smart update) and optionally auto-download; new chapters join a unified All / Manga / Novels Updates feed.
- **Novels in the History tab.** Reading a novel records it in History; the tab interleaves recently read manga and novels (All / Manga / Novels chip), newest first, with search, tap to resume, and delete or clear.
- **Cross-source merge.** Combine the same novel from several sources into one cover and one deduplicated chapter list with a source switcher and shared read state, by hand or automatically by title.
- **Plugins stay current.** The Browse badge counts pending plugin updates, checked in the background, with one-tap reinstall and real plugin icons.

**Library**
- **Cross-source merge for manga.** A series from several sources shows as one cover with combined unread counts and one deduplicated chapter list behind a source switcher; merge by hand or automatically by title, with Manage sources and a Preferred sources ranking.
- **Dynamic grouping.** Group the library by source, tag, author, language, status, or tracking status instead of by category, with collapsible groups, in both views and for both manga and novels.
- **Single-list view with a category hopper.** An optional one-scroll view of collapsible categories with a floating jump-to hopper, plus per-category sort, refresh, and select-all.
- **Category sort order and hidden categories.** Order categories Off / A to Z / Z to A everywhere they appear, and hide a category without deleting it (it round-trips through backups, including Komikku).
- **Library update-errors screen.** Opt in under Settings → Advanced for an Update errors list of entries that failed their last update, grouped by reason.
- **Panorama grid and source-icon badges.** A comfortable grid that shows wide covers uncropped, and an optional source-icon badge on covers.
- **Lewd and category filters** in the library filter sheet.

**Manga details & recommendations**
- **Related-manga recommendations carousel.** A Related row suggests similar titles (with in-library badges) from the source and, when enabled, from AniList, MyAnimeList, MangaUpdates, and Shikimori; tap to open or global-search, and a See all grid bulk-adds with category handling.
- **A Recommendations settings screen** (Settings → Library → Recommendations) toggles tracker recs per tracker, builds a taste profile from your tracker libraries, and offers style, serendipity, auto-refresh, and library / status filters (all off by default).
- **Two-finger range selection** on manga and novel chapter lists: press two rows to select everything between them.

**Reader**
- **New options:** resume reading position, pages to preload (default 4), and mark a chapter read when you skip ahead.
- **A customizable bottom bar and an in-reader chapters list** to jump to, bookmark, or download chapters without leaving the reader.
- **Cover-color theming.** Tint the reader and manga details with each manga's cover color (Settings → Appearance, on by default).

**Networking**
- **FlareSolverr support for bypassing Cloudflare.** Route a blocked source through a [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) proxy instead of the in-app WebView (Settings → Advanced → Networking); the WebView solver stays the default and the fallback.

### Changes
- **Renamed the fork to Reikai.** Installs upgrade in place (same package ID), and the launcher shows the new R-monogram icon and "Reikai" label.
- **The library Display options sheet is now tab-aware,** so a filter or category change made on the Novels tab no longer reaches into the manga library.
- **Extensions no longer tied to a repository are labeled "Orphaned"** instead of "Obsolete", with a clearer note that they won't receive updates.

### Fixes
- **The novel reader no longer crashes on a chapter with repeated paragraphs** (blank lines, scene breaks, recurring phrases).
- **Adding a light novel no longer creates a duplicate library entry** when you add the same novel again.

### Other
- **Reikai is now built on the Mihon base.** The previous release was a fork of Yokai; this cycle rebases the app onto Mihon, so the core manga reader (library, details, reader, tracking, extensions, backups) is Mihon's, with Reikai's own features (light novels, cross-source merge, recommendations, and the library, reader, and theming additions above) rebuilt on top. This is why the core UI looks different; the `.y2k` package id is preserved so existing installs upgrade in place.
- **Support for TachiyomiX 1.6 extensions** (via the Mihon sync): the newer extension format installs and loads, existing extensions keep working, sources can attach hidden metadata carried through backups, and older backups still restore.
- **Synced upstream changes from Mihon:** Coil / OkHttp / Firebase updates, a SQLite driver build that avoids a rare database stall on a cancelled write, lifecycle-bound background tasks, and auto-following extension repositories that moved to the newer index format.

## [1.9.7.5.9]

### Additions
- **Taste profile** under Settings → Library → Recommendations. Pull your library from AniList / MyAnimeList / Kitsu (per-tracker toggles), auto-refresh on a `Never` / `7 days` / `30 days` schedule, manual refresh button with a 60 s cooldown, and a last-refresh summary line. Used by the related-mangas carousel to personalize what gets shown
- **Candidate injection** under Settings → Library → Recommendations (both default on). *Tag search on current source* runs your top taste-profile tags as searches on the current source. *Cross-recommendation from favorites* looks your top-rated tracked manga up on the current source and pulls each match's related-mangas list. Silently produce nothing when the taste profile is empty
- **Reranking** under Settings → Library → Recommendations. Master toggle *Rerank by taste* (default on) reorders the carousel against your taste profile and drops manga already in your library. Two sliders tune the behavior: *Recommendation style* (Popular ← → Personalized) controls how much taste weighs vs. the source's own popularity ordering; *Serendipity* (Familiar ← → Adventurous) reserves exploration slots and boosts rare tags. Both sliders are disabled when *Rerank by taste* is off
- **Filters** under Settings → Library → Recommendations (both default on). *Hide already-tracked* drops Reading / Completed entries from the carousel. *Hide dropped* drops Dropped entries. Plan-to-read and On-hold entries are now allowed back into the carousel as reminders
- **Full-screen "See all" browse** for related mangas. A "See all (N)" card appears at the end of the carousel when the pool exceeds 30 candidates; tapping opens a dedicated grid with no cap. Long-press to multi-select; bulk actions include *Add to library* (single category-set applied to every selection), *Select all*, and *Invert selection*. Grid column count scales to screen width, so foldables and tablets get more columns

### Changes
- Related-mangas carousel now activates source-native related-mangas data by default on every HTTP-backed extension. Hundreds of installable extensions immediately surface real source data alongside the existing keyword-search fallback and tracker recommendations
- Related-mangas carousel now collapses duplicates across streams. A manga returned by source-native, an AniList recommendation, and a favorite's related list at once now shows as one card instead of three
- Slow tracker recommendation endpoints no longer hang the related-mangas carousel. Each fetch now has a 15 s cap; on timeout the slow tracker is skipped and the carousel finishes populating from the rest
- Bulk-adding many mangas via the "See all" browse no longer freezes the UI for a few hundred ms while the category picker processes the selection

### Fixes
- Long-pressing a card in the related-mangas carousel no longer surfaces the chapter-list context menu by mistake
- Multi-selecting sources in **Manage Sources** and removing them from a group now writes the correct merge state and refreshes the chip row in place without needing to reopen the manga
- After fully un-merging a group and re-merging a subset, members that were left out are no longer silently re-admitted on the next library refresh
- Multi-source groups with members bound to different tracker IDs for the same service no longer silently overwrite one with the other on reconciliation. Ties leave both members untouched; propagation runs only when there's a strict majority

### Other
- New per-tracker library cache for the taste profile. Cache is rebuilt on demand from the tracker APIs and isn't included in app backups
- Documentation reorganized: `docs/` now holds only user-facing guides; maintainer-only docs moved under `docs/dev/`. New [`docs/backup-restore.md`](docs/backup-restore.md) covers Y2K ↔ upstream Yōkai backup compatibility and the `.yokai` → `.y2k` package-suffix migration

## [1.9.7.5.8]

### Changes
- **Package ID changed to allow installing alongside upstream Yōkai.** Release builds are now `eu.kanade.tachiyomi.y2k` (was `.yokai`). Existing Y2K installs need to back up → install the new build → restore; backup files are forward-compatible.

### Additions
- Manage Sources sheet now supports multi-select with two bulk actions: split selected sources from the group, or remove the selected entries from the library entirely. Tapping anywhere on a row toggles its checkbox, and both actions show an undo snackbar so accidental selections can be reverted within the grace period
- Tracker links now mirror across multi-source groups: adding a tracker on one source automatically links the same tracker on every still-in-library sibling, and both manual merges (Library multi-select) and auto-grouped same-title entries propagate existing trackers onto any newly joined source. Toggle in Settings → Tracking. Removing a manga from the library — via the Manage Sources sheet, the heart-button popup (single or "remove all sources"), or Library multi-select — also cleans up that manga's tracker rows. Explicit tracker-chip removal and Split actions leave siblings' trackers untouched
- Related-mangas carousel on the manga details screen — shows similar titles below the description, sourced from the current source (native related-mangas API where supported, otherwise a keyword-search fallback) and from public tracker recommendations (AniList, MyAnimeList via Jikan, MangaUpdates community ratings). Tracker recommendations work without a tracker login — if you've tracked the manga the remote id is used directly, otherwise a title-search resolves it. Tap a source-origin card to open the manga's details page; tap a tracker-origin card to jump to Global Search with the title pre-filled so you can pick a source to read on. Hidden when nothing is returned. Configurable under Settings → Library → Recommendations: master toggle for tracker recommendations and per-tracker on/off for AniList, MyAnimeList, MangaUpdates

### Fixes
- Source-switcher chips on the manga details screen now refresh when returning from another screen — previously, adding a same-title source via Global Search and pressing back left the chip bar showing the old set of sources until you backed out to Library and came back

### Other
- Source-API: added `getRelatedMangaList` with three opt-in flags (`supportsRelatedMangas`, `disableRelatedMangasBySearch`, `disableRelatedMangas`) and a built-in keyword-search fallback. Powers the new related-mangas carousel; sources can override `fetchRelatedMangaList` to provide native suggestions

## [1.9.7.5.7]

### Changes
- Cloudflare handling realigned with upstream: WebView is now the primary solver and FlareSolverr (when configured) is used as a fallback only if the WebView solve fails — drastically cuts wait time on most challenges

### Fixes
- FlareSolverr no longer rewrites the global User-Agent preference; the FlareSolverr-derived UA is now pinned per-host instead, preventing cross-source UA pollution
- FlareSolverr now returns the page response directly (proxy mode) instead of just cookies. Cookie/UA replay from FlareSolverr to OkHttp is unreliable for sites on Cloudflare's stricter bot-management tier because cf_clearance is bound to TLS / `__cf_bm` session fingerprint that OkHttp can't replicate; serving FlareSolverr's own response sidesteps the binding problem
- FlareSolverr now reuses a single browser session across all calls and skips the 30-second WebView pre-attempt for hosts already known to need FlareSolverr; subsequent requests after the first solve drop from ~42 s to ~1–3 s. Fixes a serialization bug in the same change that was throwing a MissingFieldException on the session-create response and short-circuiting the entire FlareSolverr path.

## [1.9.7.5.6]

### Additions
- Remove merged source groups from library in one step: new "Remove all sources from library" option in the manga detail favorite-button popup; bulk library delete now automatically includes all sources in any selected merged group

## [1.9.7.5.5]

### Additions
- Category bulk delete: long-press any category in Settings → Library → Edit categories to enter multi-select mode, then delete all selected categories at once with a single confirmation dialog and an undo snackbar

### Fixes
- Fix debug and nightly builds showing "Yōkai" instead of "Yōkai-Y2K" in the app launcher
- Fix crash when opening Manage Sources sheet: add no-arg constructor to satisfy Conductor's state-restoration requirement
- Fix source-switcher chips not appearing on large-screen / foldable devices: add chip row views to sw600dp-port and sw600dp-land layout variants
- Fix FlareSolverr re-challenging the same site multiple times in rapid succession: cookie removal is now deferred until an actual solve begins, and a 30-second reuse window prevents redundant solves for batch requests whose 403 responses arrive after a concurrent solve has already completed

## [1.9.7.5.4]

### Fixes
- Fix repeated Cloudflare challenge solves when switching between manga listing tabs: FlareSolverr cookies are now stored with proper domain scope (leading dot preserved) so they apply to all subdomains, and concurrent requests for the same host share a single solve instead of triggering parallel ones
- Fix `AndroidCookieJar.remove()` silently failing to delete cookies whose names had a leading space after splitting on `;`

## [1.9.7.5.3]

### Additions
- Add FlareSolverr support for Cloudflare bypass; configure the service URL in Settings → Advanced → Network

## [1.9.7.5.2]

### Other
- Update in-app GitHub links to point to unseensnick/yokai-y2k instead of upstream

## [1.9.7.5.1]

### Additions
- Add multi-source manga grouping: same-title library entries collapse into a single card with a source-count badge
- Add source-switcher chip row in manga details to switch between grouped sources
- Add manual merge/unmerge: "Merge selected" in library multi-select, long-press a chip to remove an entry from a group
- Add "Manage sources" sheet in manga details overflow menu to add or remove entries from a source group
- Add category sort order setting (off / A→Z / Z→A) under Settings → Library

### Other
- Rebrand to Yōkai-Y2K (fork of upstream Yōkai 1.9.7.5)

## [1.9.7.5.0]

### Additions
- Add random library sort
- Add the ability to save search queries
- Add toggle to enable/disable hide source on swipe (@Hiirbaf)
- Add the ability to mark duplicate read chapters as read (@AntsyLich)
- Add option to zoom into full covers (@Hiirbaf)
- Add APNG support for Android 9+ (@lalalasupa0)
- Add markdown support to entry description (@luigidotmoe)
  - Fix text disappeared when it's surrounded by `<>` (@lalalasupa0)

### Changes
- Temporarily disable log file
- Categories' header now show filtered count when you search the library when you have "Show number of items" enabled (@LeeSF03)
- Chapter progress now saved everything the page is changed
- Adjust sorting order to be more consistent (@Astyyyyy)
- Improve Local Source when loading from `android/data` (@lalalasupa0)
- Refresh available extensions list when an extension repo is added or removed
- Replace filter FAB with Floating Toolbar when browsing source
- Show FAB button to read/resume chapter when start/continue reading button is off-screen
- LocalSource entries no longer auto-refresh when opened (@lalalasupa0)
- Long tap chapters on Reader now mark it as read (@lalalasupa0)

### Fixes
- Allow users to bypass onboarding's permission step if Shizuku is installed
- Fix Recents page shows "No recent chapters" instead of a loading screen
- Fix not fully loaded entries can't be selected on Library page
- Fix certain Infinix devices being unable to use any "Open link in browser" actions, including tracker setup (@MajorTanya)
- Fix source filter bottom sheet unable to be fully scrolled to the bottom
- Prevent potential "Comparison method violates its general contract!" crash
- Fix staggered grid cover being squashed for local source (@AwkwardPeak7)
- Fix GPU crash when setting cover from downloaded chapters (@Angrevol)
- Fix crashes when handling certain sources' deep links (@Hiirbaf)
- Properly filter sources by extension (@Hiirbaf)
- Fix crashes caused by RecyclerView stable id (@MuhamadSyabitHidayattulloh)
- Fix paused download notification is not shown (@MuhamadSyabitHidayattulloh)
- Disable auto refresh entry from Local Source (@lalalasupa0)
- Fix extension download stuck on pending state
- Only solve Cloudflare with WebView if it's not geoblock (@AwkwardPeak7)
- Fix cover from LocalSource sometimes didn't load (@lalalasupa0)

### Translation
- Update translations from Weblate

### Other
- Refactor Library to utilize Flow even more
- Refactor EmptyView to use Compose
- Refactor Reader ChapterTransition to use Compose (@arkon)
- [Experimental] Add modified version of LargeTopAppBar that mimic J2K's ExpandedAppBarLayout
- Refactor About page to use Compose
- Adjust Compose-based pages' transition to match J2K's Conductor transition
- Resolve deprecation warnings
  - Kotlin's context-receiver, schedule for removal on Kotlin v2.1.x and planned to be replaced by context-parameters on Kotlin v2.2
  - Project.exec -> Providers.exec
  - Remove internal API usage to retrieve Kotlin version for kotlin-stdlib
- Move :core module to :core:main
  - Move archive related code to :core:archive (@AntsyLich)
- Refactor Library to store LibraryMap instead of flatten list of LibraryItem
  - LibraryItem abstraction to make it easier to manage
  - LibraryManga no longer extend MangaImpl
- Update dependency gradle to v8.12
- Update user agent (@Hiirbaf)
- Update serialization to v1.8.1
- Update dependency io.github.fornewid:material-motion-compose-core to v2.0.1
- Update lifecycle to v2.9.0
- Update dependency org.jsoup:jsoup to v1.21.2
- Update dependency org.jetbrains.kotlinx:kotlinx-collections-immutable to v0.4.0
- Update dependency io.mockk:mockk to v1.14.2
- Update dependency io.coil-kt.coil3:coil-bom to v3.4.0
- Update dependency com.squareup.okio:okio to v3.12.0
- Update dependency com.google.firebase:firebase-bom to v33.14.0
- Update dependency com.google.accompanist:accompanist-themeadapter-material3 to v0.36.0
- Update dependency com.github.requery:sqlite-android to v3.49.0
- Update dependency com.getkeepsafe.taptargetview:taptargetview to v1.15.0
- Update dependency androidx.window:window to v1.4.0
- Update dependency androidx.webkit:webkit to v1.13.0
- Update dependency androidx.sqlite:sqlite-ktx to v2.5.1
- Update dependency androidx.sqlite:sqlite to v2.5.1
- Update dependency androidx.recyclerview:recyclerview to v1.4.0
- Update dependency androidx.core:core-ktx to v1.17.0
- Update dependency androidx.core:core-splashscreen to v1.2.0
- Update dependency androidx.compose:compose-bom to v2026.02.00
- Update aboutlibraries to v13.1.0
- Update plugin kotlinter to v5.1.0
- Update plugin gradle-versions to v0.52.0
- Update okhttp monorepo to v5.0.0-alpha.16
- Update moko to v0.25.1
- Update dependency org.jetbrains.kotlinx:kotlinx-coroutines-bom to v1.10.2
- Update dependency me.zhanghai.android.libarchive:library to v1.1.5
- Update dependency io.insert-koin:koin-bom to v4.0.4
- Update dependency com.android.tools:desugar_jdk_libs to v2.1.5
- Update dependency androidx.work:work-runtime-ktx to v2.10.1
- Update dependency androidx.constraintlayout:constraintlayout to v2.2.1
- Update plugin firebase-crashlytics to v3.0.3
- Update null2264/actions digest to 363cb9c
- Update dependency io.github.pdvrieze.xmlutil:core-android to v0.91.1
- Improve X-Requested-With spoof to support newer WebView versions (@Hiirbaf)
- Update agp to v8.12.2
- Update activity to v1.11.0
- Update lifecycle to v2.9.4
- Update sqldelight to v2.2.1
- Update dependency com.google.android.material:material to v1.14.0-alpha09
- Update dependency androidx.compose.material3:material3 to v1.5.0-alpha14
- Minimize memory usage by reducing in-memory cover cache size (@Lolle2000la)

## [1.9.7.5]

### Fixes
- Add missing ProtoBuf singleton definition to the DI for extensions

## [1.9.7.4]

### Other
- Prioritize extension classpath over app
- Update kotlin monorepo to v2.3.10
- Update dependency gradle to v8.14.4

## [1.9.7.3]

### Fixes
- More `Comparison method violates its general contract!` crash prevention

## [1.9.7.2]

### Fixes
- Fix MyAnimeList timeout issue

## [1.9.7.1]

### Fixes
- Prevent `Comparison method violates its general contract!` crashes

## [1.9.7]

### Changes
- Adjust log file to only log important information by default

### Fixes
- Fix sorting by latest chapter is not working properly
- Prevent some NPE crashes
- Fix some flickering issues when browsing sources
- Fix download count is not updating

### Translation
- Update Korean translation (@Meokjeng)

### Other
- Update NDK to v27.2.12479018

## [1.9.6]

### Fixes
- Fix some crashes

## [1.9.5]

### Changes
- Entries from local source now behaves similar to entries from online sources

### Fixes
- Fix new chapters not showing up in `Recents > Grouped`
- Add potential workarounds for duplicate chapter bug
- Fix favorite state is not being updated when browsing source

### Other
- Update dependency androidx.compose:compose-bom to v2024.12.01
- Update plugin kotlinter to v5
- Update plugin gradle-versions to v0.51.0
- Update kotlin monorepo to v2.1.0

## [1.9.4]

### Fixes
- Fix chapter date fetch always null causing it to not appear on Updates tab

## [1.9.3]

### Fixes
- Fix slow chapter load
- Fix chapter bookmark state is not persistent

### Other
- Refactor downloader
  - Replace RxJava usage with Kotlin coroutines
  - Replace DownloadQueue with Flow to hopefully fix ConcurrentModificationException entirely

## [1.9.2]

### Changes
- Adjust chapter title-details contrast
- Make app updater notification consistent with other notifications

### Fixes
- Fix "Remove from read" not working properly

## [1.9.1]

### Fixes
- Fix chapters cannot be opened from `Recents > Grouped` and `Recents > All`
- Fix crashes caused by malformed XML
- Fix potential memory leak

### Other
- Update dependency io.github.kevinnzou:compose-webview to v0.33.6
- Update dependency org.jsoup:jsoup to v1.18.3
- Update voyager to v1.1.0-beta03
- Update dependency androidx.annotation:annotation to v1.9.1
- Update dependency androidx.constraintlayout:constraintlayout to v2.2.0
- Update dependency androidx.glance:glance-appwidget to v1.1.1
- Update dependency com.google.firebase:firebase-bom to v33.7.0
- Update fast.adapter to v5.7.0
- Downgrade dependency org.conscrypt:conscrypt-android to v2.5.2

## [1.9.0]

### Additions
- Sync DoH provider list with upstream (added Mullvad, Control D, Njalla, and Shecan)
- Add option to enable verbose logging
- Add category hopper long-press action to open random series from **any** category
- Add option to enable reader debug mode
- Add option to adjust reader's hardware bitmap threshold (@AntsyLich)
  - Always use software bitmap on certain devices (@MajorTanya)
- Add option to scan local entries from `/storage/(sdcard|emulated/0)/Android/data/<yokai>/files/local`

### Changes
- Enable 'Split Tall Images' by default (@Smol-Ame)
- Minor visual adjustments
- Tell user to restart the app when User-Agent is changed (@NGB-Was-Taken)
- Re-enable fetching licensed manga (@Animeboynz)
- Bangumi search now shows the score and summary of a search result (@MajorTanya)
- Logs are now written to a file for easier debugging
- Bump default user agent (@AntsyLich)
- Custom cover is now compressed to WebP to prevent OOM crashes

### Fixes
- Fix only few DoH provider is actually being used (Cloudflare, Google, AdGuard, and Quad9)
- Fix "Group by Ungrouped" showing duplicate entries
- Fix reader sometimes won't load images
- Handle some uncaught crashes
- Fix crashes due to GestureDetector's firstEvent is sometimes null on some devices
- Fix download failed due to invalid XML 1.0 character
- Fix issues with shizuku in a multi-user setup (@Redjard)
- Fix some regional/variant languages is not listed in app language option
- Fix browser not opening in some cases in Honor devices (@MajorTanya)
- Fix "ConcurrentModificationException" crashes
- Fix Komga unread badge, again
- Fix default category can't be updated manually
- Fix crashes trying to load Library caused by cover being too large

### Other
- Simplify network helper code
- Fully migrated from StorIO to SQLDelight
- Update dependency com.android.tools:desugar_jdk_libs to v2.1.3
- Update moko to v0.24.4
- Refactor trackers to use DTOs (@MajorTanya)
  - Fix AniList `ALSearchItem.status` nullibility (@Secozzi)
- Replace Injekt with Koin
- Remove unnecessary permission added by Firebase
- Remove unnecessary features added by Firebase
- Replace BOM dev.chrisbanes.compose:compose-bom with JetPack's BOM
- Update dependency androidx.compose:compose-bom to v2024.11.00
- Update dependency com.google.firebase:firebase-bom to v33.6.0
- Update dependency com.squareup.okio:okio to v3.9.1
- Update activity to v1.9.3
- Update lifecycle to v2.8.7
- Update dependency me.zhanghai.android.libarchive:library to v1.1.4
- Update agp to v8.7.3
- Update junit5 monorepo to v5.11.3
- Update dependency androidx.test.ext:junit to v1.2.1
- Update dependency org.jetbrains.kotlinx:kotlinx-collections-immutable to v0.3.8
- Update dependency org.jsoup:jsoup to v1.18.1
- Update dependency org.jetbrains.kotlinx:kotlinx-coroutines-bom to v1.9.0
- Update serialization to v1.7.3
- Update dependency gradle to v8.11.1
- Update dependency androidx.webkit:webkit to v1.12.0
- Update dependency io.mockk:mockk to v1.13.13
- Update shizuku to v13.1.5
  - Use reflection to fix shizuku breaking changes (@Jobobby04)
- Bump compile sdk to 35
  - Handle Android SDK 35 API collision (@AntsyLich)
- Update kotlin monorepo to v2.0.21
- Update dependency androidx.work:work-runtime-ktx to v2.10.0
- Update dependency androidx.core:core-ktx to v1.15.0
- Update dependency io.coil-kt.coil3:coil-bom to v3.0.4
- Update xml.serialization to v0.90.3
- Update dependency co.touchlab:kermit to v2.0.5
- Replace WebView to use Compose (@arkon)
  - Fixed Keyboard is covering web page inputs
- Increased `tryToSetForeground` delay to fix potential crashes (@nonproto)
- Update dependency org.conscrypt:conscrypt-android to v2.5.3
- Port upstream's download cache system

## [1.8.5.13]

### Fixed
- Fix version checker

## [1.8.5.12]

### Fixed
- Fixed scanlator data sometimes disappear

## [1.8.5.11]

### Fixed
- Fixed crashes caused by Bangumi invalid status

## [1.8.5.10]

### Fixes
- Fixed scanlator filter not working properly

## [1.8.5.9]

### Changes
- Revert create backup to use file picker

## [1.8.5.8]

### Other
- Separate backup error log when destination is null or not a file
- Replace com.github.inorichi.injekt with com.github.null2264.injekt

## [1.8.5.7]

### Fixes
- Fixed more NPE crashes

## [1.8.5.6]

### Fixes
- Fixed NPE crash on tablets

## [1.8.5.5]

### Fixes
- Fixed crashes caused by certain extension implementation
- Fixed "Theme buttons based on cover" doesn't work properly
- Fixed library cover images looks blurry then become sharp after going to
  entry's detail screen

### Other
- More StorIO to SQLDelight migration effort
- Update dependency dev.chrisbanes.compose:compose-bom to v2024.08.00-alpha02
- Update kotlin monorepo to v2.0.20
- Update aboutlibraries to v11.2.3
- Remove dependency com.github.leandroBorgesFerreira:LoadingButtonAndroid

## [1.8.5.4]

### Fixes
- Fixed custom cover set from reader didn't show up on manga details

## [1.8.5.3]

### Additions
- Add toggle to enable/disable chapter swipe action(s)
- Add toggle to enable/disable webtoon double tap to zoom

### Changes
- Custom cover now shown globally

### Fixes
- Fixed chapter number parsing (@Naputt1)
- Reduced library flickering (still happened in some cases when the cached image size is too different from the original image size, but should be reduced quite a bit)
- Fixed entry details header didn't update when being removed from library

### Other
- Refactor chapter recognition (@stevenyomi)
- (Re)added unit test for chapter recognition
- More StorIO to SQLDelight migration effort
- Target Android 15
- Adjust manga cover cache key
- Refactor manga cover fetcher (@ivaniskandar, @AntsyLich, @null2264)

## [1.8.5.2]

### Fixes
- Fixed some preference not being saved properly

### Other
- Update dependency co.touchlab:kermit to v2.0.4
- Update lifecycle to v2.8.4

## [1.8.5.1]

### Fixes
- Fixed library showing duplicate entry when using dynamic category

## [1.8.5]

### Additions
- Add missing "Max automatic backups" option on experimental Data and Storage setting menu
- Add information on when was the last time backup automatically created to experimental Data and Storage setting menu
- Add monochrome icon

### Changes
- Add more info to WorkerInfo page
  - Added "next scheduled run"
  - Added attempt count
- `english` tag no longer cause reading mode to switch to LTR (@mangkoran)
- `chinese` tag no longer cause reading mode to switch to LTR
- `manhua` tag no longer cause reading mode to switch to LTR
- Local source manga's cover now being invalidated on refresh
- It is now possible to create a backup without any entries using experimental Data and Storage setting menu
- Increased default maximum automatic backup files to 5
- It is now possible to edit a local source entry without adding it to library
- Long Strip and Continuous Vertical background color now respect user setting
- Display Color Profile setting no longer limited to Android 8 or newer
- Increased long strip cache size to 4 for Android 8 or newer (@FooIbar)
- Use Coil pipeline to handle HEIF images

### Fixes
- Fixed auto backup, auto extension update, and app update checker stop working
  if it crash/failed
- Fixed crashes when trying to reload extension repo due to connection issue
- Fixed tap controls not working properly after zoom (@arkon, @Paloys, @FooIbar)
- Fixed (sorta, more like workaround) ANR issues when running background tasks, such as updating extensions (@ivaniskandar)
- Fixed split (downloaded) tall images sometimes doesn't work
- Fixed status bar stuck in dark mode when app is following system theme
- Fixed splash screen state only getting updates if library is empty (Should slightly reduce splash screen duration)
- Fixed kitsu tracker issue due to domain change
- Fixed entry custom cover won't load if entry doesn't have cover from source
- Fixed unread badge doesn't work properly for some sources (notably Komga)
- Fixed MAL start date parsing (@MajorTanya)

### Translation
- Update Japanese translation (@akir45)
- Update Brazilian Portuguese translation (@AshbornXS)
- Update Filipino translation (@infyProductions)

### Other
- Re-added several social media links to Mihon
- Some code refactors
  - Simplify some messy code
  - Rewrite version checker
  - Rewrite Migrator (@ghostbear)
  - Split the project into several modules
  - Migrated i18n to use Moko Resources
  - Removed unnecessary dependencies (@null2264, @nonproto)
- Update firebase bom to v33.1.0
- Replace com.google.android.gms:play-services-oss-licenses with com.mikepenz:aboutlibraries
- Update dependency com.google.gms:google-services to v4.4.2
- Add crashlytics integration for Kermit
- Replace ProgressBar with ProgressIndicator from Material3 to improve UI consistency
- More StorIO to SQLDelight migrations
  - Merge lastFetch and lastRead query into library_view VIEW
  - Migrated a few more chapter related queries
  - Migrated most of the manga related queries
- Bump dependency com.github.tachiyomiorg:unifile revision to a9de196cc7
- Update project to Kotlin 2.0 (v2.0.10)
- Update compose bom to v2024.08.00-alpha01
- Refactor archive support to use `libarchive` (@FooIbar)
- Use version catalog for gradle plugins
- Update dependency org.jsoup:jsoup to v1.7.1
- Bump dependency com.github.tachiyomiorg:image-decoder revision to 41c059e540
- Update dependency io.coil-kt.coil3 to v3.0.0-alpha10
- Update Android Gradle Plugin to v8.5.2
- Update gradle to v8.9
- Start using Voyager for navigation
- Update dependency androidx.work:work-runtime-ktx to v2.9.1
- Update dependency androidx.annotation:annotation to v1.8.2

## [1.8.4.6]

### Fixes
- Fixed scanlator filter not working properly if it contains " & "

### Other
- Removed dependency com.dmitrymalkovich.android:material-design-dimens
- Replace dependency br.com.simplepass:loading-button-android with
  com.github.leandroBorgesFerreira:LoadingButtonAndroid
- Replace dependency com.github.florent37:viewtooltip with
  com.github.CarlosEsco:ViewTooltip

## [1.8.4.5]

### Fixes
- Fixed incorrect library entry chapter count

## [1.8.4.4]

### Fixes
- Fixed incompatibility issue with J2K backup file

## [1.8.4.3]

### Fixes
- Fixed "Open source repo" icon's colour

## [1.8.4.2]

### Changes
- Changed "Open source repo" icon to prevent confusion

## [1.8.4.1]

### Fixes
- Fixed saving combined pages not doing anything

## [1.8.4]

### Additions
- Added option to change long tap browse and recents nav behaviour
  - Added browse long tap behaviour to open global search (@AshbornXS)
  - Added recents long tap behaviour to open last read chapter (@AshbornXS)
- Added option to backup sensitive settings (such as tracker login tokens)
- Added beta version of "Data and storage" settings (can be accessed by long tapping "Data and storage")

### Changes
- Remove download location redirection from `Settings > Downloads`
- Moved cache related stuff from `Settings > Advanced` to `Settings > Data and storage`
- Improve webview (@AshbornXS)
  - Show url as subtitle
  - Add option to clear cookies
  - Allow zoom
- Handle urls on global search (@AshbornXS)
- Improve download queue (@AshbornXS)
  - Download badge now show download queue count
  - Add option to move series to bottom
- Only show "open repo url" button when repo url is not empty

### Fixes
- Fix potential crashes for some custom Android rom
- Allow MultipartBody.Builder for extensions
- Refresh extension repo now actually refresh extension(s) trust status
- Custom manga info now relink properly upon migration
- Fixed extension repo list did not update when a repo is added via deep link
- Fixed download unread trying to download filtered (by scanlator) chapters
- Fixed extensions not retaining their repo url
- Fixed more NullPointerException crashes
- Fixed split layout caused non-split images to not load

### Other
- Migrate some StorIO queries to SQLDelight, should improve stability
- Migrate from Timber to Kermit
- Update okhttp monorepo to v5.0.0-alpha.14
- Refactor backup code
  - Migrate backup flags to not use bitwise
  - Split it to several smaller classes
- Update androidx.compose.material3:material3 to v1.3.0-beta02

## [1.8.3.4]

### Fixes
- Fixed crashes caused by invalid ComicInfo XML

  If this caused your custom manga info to stop working, try resetting it by deleting `ComicInfoEdits.xml` file located in `Android/data/eu.kanade.tachiyomi.yokai`

- Fixed crashes caused by the app trying to round NaN value

## [1.8.3.3]

### Changes
- Crash report can now actually be disabled

### Other
- Loading GlobalExceptionHandler before Crashlytics

## [1.8.3.2]

### Other
- Some more NullPointerException prevention that I missed

## [1.8.3.1]

### Other
- A bunch of NullPointerException prevention

## [1.8.3]

### Additions
- Extensions now can be trusted by repo

### Changes
- Extensions now required to have `repo.json`

### Other
- Migrate to SQLDelight
- Custom manga info is now stored in the database

## [1.8.2]

### Additions
- Downloaded chapters now include ComicInfo file
- (LocalSource) entry chapters' info can be edited using ComicInfo

### Fixes
- Fixed smart background colour by page failing causing the image to not load
- Fixed downloaded chapter can't be opened if it's too large
- Downloaded page won't auto append chapter ID even tho the option is enabled

### Other
- Re-route nightly to use its own repo, should fix "What's new" page

## [1.8.1.2]

### Additions
- Added a couple new tags to set entry as SFW (`sfw` and `non-erotic`)

### Fixes
- Fixed smart background colour by page failing causing the image to not load

### Other
- Re-route nightly to use its own repo, should fix "What's new" page

## [1.8.1.1]

### Fixes
- Fixed crashes when user try to edit an entry

## [1.8.1]

### Additions
- (Experimental) Option to append chapter ID to download filename to avoid conflict

### Changes
- Changed notification icon to use Yōkai's logo instead
- Yōkai is now ComicInfo compliant. [Click here to learn more](https://anansi-project.github.io/docs/comicinfo/intro)
- Removed "Couldn't split downloaded image" notification to reduce confusion. It has nothing to do with unsuccessful split, it just think it shouldn't split the image

### Fixes
- Fixed not being able to open different chapter when a chapter is already opened
- Fixed not being able to read chapters from local source
- Fixed local source can't detect archives

### Other
- Wrap SplashState to singleton factory, might fix issue where splash screen shown multiple times
- Use Okio instead of `java.io`, should improve reader stability (especially long strip)

## [1.8.0.2]

### Fixes
- Fixed app crashes when backup directory is null
- Fixed app asking for All Files access permission when it's no longer needed

## [1.8.0.1]

### Additions
- Added CrashScreen

### Fixes
- Fixed version checker for nightly against hotfix patch version
- Fixed download cache causes the app to crash

## [1.8.0]

### Additions
- Added cutout support for some pre-Android P devices
- Added option to add custom colour profile
- Added onboarding screen

### Changes
- Permanently enable 32-bit colour mode
- Unified Storage™ ([Click here](https://mihon.app/docs/faq/storage#migrating-from-tachiyomi-v0-14-x-or-earlier) to learn more about it)

### Fixes
- Fixed cutout behaviour for Android P
- Fixed some extensions doesn't detect "added to library" entries properly ([GH-40](https://github.com/null2264/yokai/issues/40))
- Fixed nightly and debug variant doesn't include their respective prefix on their app name
- Fixed nightly version checker

### Other
- Update dependency com.github.tachiyomiorg:image-decoder to e08e9be535
- Update dependency com.github.null2264:subsampling-scale-image-view to 338caedb5f
- Added Unit Test for version checker
- Use Coil pipeline instead of SSIV for image decode whenever possible, might improve webtoon performance
- Migrated from Coil2 to Coil3
- Update compose compiler to v1.5.14
- Update dependency androidx.compose.animation:animation to v1.6.7
- Update dependency androidx.compose.foundation:foundation to v1.6.7
- Update dependency androidx.compose.material:material to v1.6.7
- Update dependency androidx.compose.ui:ui to v1.6.7
- Update dependency androidx.compose.ui:ui-tooling to v1.6.7
- Update dependency androidx.compose.ui:ui-tooling-preview to v1.6.7
- Update dependency androidx.compose.material:material-icons-extended to v1.6.7
- Update dependency androidx.lifecycle:lifecycle-viewmodel-compose to v2.8.0
- Update dependency androidx.activity:activity-ktx to v1.9.0
- Update dependency androidx.activity:activity-compose to v1.9.0
- Update dependency androidx.annotation:annotation to v1.8.0
- Update dependency androidx.browser:browser to v1.8.0
- Update dependency androidx.core:core-ktx to v1.13.1
- Update dependency androidx.lifecycle:lifecycle-viewmodel-ktx to v2.8.0
- Update dependency androidx.lifecycle:lifecycle-livedata-ktx to v2.8.0
- Update dependency androidx.lifecycle:lifecycle-common to v2.8.0
- Update dependency androidx.lifecycle:lifecycle-process to v2.8.0
- Update dependency androidx.lifecycle:lifecycle-runtime-ktx to v2.8.0
- Update dependency androidx.recyclerview:recyclerview to v1.3.2
- Update dependency androidx.sqlite:sqlite to v2.4.0
- Update dependency androidx.webkit:webkit to v1.11.0
- Update dependency androidx.work:work-runtime-ktx to v2.9.0
- Update dependency androidx.window:window to v1.2.0
- Update dependency com.google.firebase:firebase-crashlytics-gradle to v3.0.1
- Update dependency com.google.gms:google-services to v4.4.1
- Update dependency com.google.android.material:material to v1.12.0
- Update dependency com.squareup.okio:okio to v3.8.0
- Update dependency com.google.firebase:firebase-bom to v33.0.0
- Update dependency org.jetbrains.kotlin:kotlin-gradle-plugin to v1.9.24
- Update dependency org.jetbrains.kotlin:kotlin-serialization to v1.9.24
- Update dependency org.jetbrains.kotlinx:kotlinx-serialization-json to v1.6.2
- Update dependency org.jetbrains.kotlinx:kotlinx-serialization-json-okio to v1.6.2
- Update dependency org.jetbrains.kotlinx:kotlinx-serialization-protobuf to v1.6.2
- Update dependency org.jetbrains.kotlinx:kotlinx-coroutines-android to v1.8.0
- Update dependency org.jetbrains.kotlinx:kotlinx-coroutines-core to v1.8.0
- Resolved some compile warnings
- Update dependency com.github.tachiyomiorg:unifile to 7c257e1c64

## [1.7.14]

### Changes
- Added splash to reader (in case it being opened from shortcut)
- Increased long strip split height
- Use normalized app name by default as folder name

### Fixes
- Fixed cutout support being broken

### Other
- Move AppState from DI to Application class to reduce race condition

## [1.7.13]

### Additions
- Ported Tachi's cutout option
- Added Doki theme (dark only)

### Changes
- Repositioned cutout options in settings
- Splash icon now uses coloured variant of the icon
- Removed deep link for sources, this should be handled by extensions
- Removed braces from nightly (and debug) app name

### Fixes
- Fixed preference summary not updating after being changed once
- Fixed legacy appbar is visible on compose when being launched from deeplink
- Fixed some app icon not generated properly
- Fixed splash icon doesn't fit properly on Android 12+

### Other
- Migrate to using Android 12's SplashScreen API
- Clean up unused variables from ExtensionInstaller

## [1.7.12]

### Additions
- Scanlator filter is now being backed up (@jobobby04)

### Fixes
- Fixed error handling for MAL tracking (@AntsyLich)
- Fixed extension installer preference incompatibility with modern Tachi

### Other
- Split PreferencesHelper even more
- Simplify extension install issue fix (@AwkwardPeak7)
- Update dependency com.github.tachiyomiorg:image-decoder to fbd6601290
- Replace dependency com.github.jays2kings:subsampling-scale-image-view with com.github.null2264:subsampling-scale-image-view
- Update dependency com.github.null2264:subsampling-scale-image-view to e3cffd59c5

## [1.7.11]

### Fixes
- Fixed MAL tracker issue (@AntsyLich)
- Fixed trusting extension caused it to appear twice

### Other
- Change Shikimori client from Tachi's to Yōkai's
- Move TrackPreferences to PreferenceModule

## [1.7.10]

### Addition
- Content type filter to hide SFW/NSFW entries
- Confirmation before revoking all trusted extension

### Changes
- Revert Webcomic -> Webtoon

### Fixes
- Fix app bar disappearing on (scrolled) migration page
- Fix installed extensions stuck in "installable" state
- Fix untrusted extensions not having an icon

### Other
- Changed (most) trackers' client id and secret
- Add or changed user-agent for trackers

## [1.7.9]

### Other
- Sync project with J2K [v1.7.4](https://github.com/Jays2Kings/tachiyomiJ2K/releases/tag/v1.7.4)

## [1.7.8]

### Changes
- Local source now try to find entries not only in `Yōkai/` but also in `Yokai/` and `TachiyomiJ2K/` for easier migration

### Other
- Changed AniList and MAL clientId, you may need to logout and re-login

## [1.7.7]

### Changes
- Hopper icon now changes depending on currently active group type (J2K)

### Fixes
- Fixed bookmarked entries not being detected as bookmarked on certain extensions

## [1.7.6]

### Additions
- Shortcut to Extension Repos from Browser -> Extensions page
- Added confirmation before extension repo deletion

### Changes
- Adjusted dialogs background colour to be more consistent with app theme

### Fixes
- Fixed visual glitch where page sometime empty on launch
- Fixed extension interceptors receiving compressed responses (T)

### Other
- Newly added strings from v1.7.5 is now translatable

## [1.7.5]

### Additions
- Ported custom extension repo from upstream

### Changes
- Removed built-in extension repo
- Removed links related to Tachiyomi
- Ported upstream's trust extension logic
- Rebrand to Yōkai

### Other
- Start migrating to Compose

## [1.7.4]

### Changes
- Rename project to Yōkai (Z)
- Replace Tachiyomi's purged extensions with Keiyoushi extensions (Temporary solution until I ported custom extension repo feature) (Z)
- Unread count now respect scanlator filter (J2K)

### Fixes
- Fixed visual glitch on certain page (J2K)
