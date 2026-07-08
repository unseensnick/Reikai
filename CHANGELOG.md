# Changelog

All notable changes to this project will be documented in this file.

The format is simplified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/):
- `Additions` - New features
- `Changes` - Behaviour/visual changes
- `Fixes` - Bugfixes
- `Other` - Technical changes/updates

Reikai uses its own [Semantic Versioning](https://semver.org/) from the Mihon-based releases onward. The earlier `1.9.7.5.x` versions tracked the upstream Yōkai release Reikai was based on.

## [Unreleased]

### Additions

- **The novel chapter list now has a fast-scroll thumb, like manga.** Drag it down the right edge to jump through a long list of chapters.
- **On a novel's details screen, long-press the In-library button to edit its categories.** It opens the same category picker manga details already had on long-press.
- **Long-press a manga's source name on its details screen to copy it.** Its title and author already copied on long-press; now the source does too.

### Changes

- **The novel chapter selection bar now shows only the actions that apply.** Like manga, it hides mark-unread, delete, or mark-previous when your selection doesn't allow them, instead of always showing every icon.
- **The novel details header now shows a status icon, matching manga.** It sits next to the source and flips between Ongoing, Completed, and the rest.
- **Novel and grouped covers in the Updates list now open the title's details.** Novel rows did nothing on cover-tap before and grouped rows just expanded; both now open details, while the rest of the row still opens the chapter or expands the group.

### Fixes

- **A novel's "Show hidden chapters" menu entry now disappears the moment nothing is hidden.** Unhiding your last hidden chapter used to leave a stale "Hide hidden chapters" item lingering in the overflow menu.
- **On a merged series, a source's extended info now shows when you view that source.** The rating and its "More info" link were hidden unless the merge happened to be anchored on that source.

### Other

- The manga and novel History/Updates rows, cover dialog, and the details action row, info header, screen shell (phone and tablet), and toolbar now render through shared components instead of near-duplicate copies. Groundwork for the unified content UI.

## [0.2.1]

### Additions

- **Track your reading on Hikka, a new tracker synced from Mihon (mihonapp/mihon#1386).** Sign in from Settings > Tracking, then bind a title and your progress stays in sync with your Hikka account, just like the other trackers.
- **Settings > Tracking now shows which account you're signed in to (synced from Mihon, mihonapp/mihon#3533).** Each connected tracker displays its username under its name, so it's clear at a glance which account is linked.

### Fixes

- **Turning off "Tracker recommendations" now gives a source-only Related carousel.** The switch previously still showed tracker suggestions for titles you track; now it hides every tracker-derived suggestion (direct recommendations and the taste-based ones), so off leaves only the source's own related titles.
- **Installing several extensions at once no longer freezes the app partway through (ported from Komikku, komikku-app/komikku#1652).** The installer no longer runs under Android's short-service time limit, which could kill it while it waited on the system's install prompts.
- **Canceling one extension install no longer cancels an unrelated one (ported from Komikku, komikku-app/komikku#1649).** The installer now matches a cancel to the right download and won't queue the same extension twice.
- **AniList tracking now shows a clear message when it's down or your login expired (ported from Komikku, komikku-app/komikku#1591).** Instead of a generic failure it surfaces AniList's own error, and points you to re-login when the token has expired.
- **The library's "Jump to category" picker now shows the Default category.** Its row was blank before; it now reads "Default" like the other categories.

## [0.2.0]

### Additions

- **Track your reading with MDList.** Sign in from Settings > Tracking, then bind a title and its follow status and rating stay in sync with your account, the same way the other trackers work.
- **One of the most-used manga sources now shows full details on its entries.** Author, artist, status, description, a star rating with its score, and namespaced tags (demographic, content rating, genres) now come from the source's own data instead of a bare listing.
- **Browse the manga you follow on MDList and add them to your library.** Once you're signed into MDList, tap the new Follows button in that source's Browse filter to see your follows and add them, one or many at once.
- **Add many titles to your library at once.** Tap Select in Browse, global search, or a recommendations "See all" grid to pick several and add them together; long-press still adds one.
- **Sync your MDList library both ways from one screen.** A new settings screen imports every title you follow on MDList into your library, filtered by follow status, and pushes your library titles back to your account as reading.
- **Jump to a random title on one of the most-used manga sources.** Open that source's Browse filter and tap Random for a surprise pick.

### Fixes

- **Cover-based theming now tints a title the first time you open it.** Previously the cover's accent color only appeared after a title was in your library and the app had been reopened; now it shows on first open when browsing, for both manga and novels.
- **The themed app icon now shows the logo instead of a shapeless blob (thanks [@Orifarius](https://github.com/Orifarius)).** With Material You themed icons enabled, the home screen icon keeps the letter and flame detail in your wallpaper's colors.

### Other

- Synced two upstream Mihon fixes: the app no longer crashes when sent to the background (replacing Reikai's earlier local notes-screen workaround), and storage folders served by non-system file providers (some cloud-storage and file-manager apps) work again.

## [0.1.8]

### Changes

- **Recommendations now include MangaUpdates similar titles, not just its community picks.** A title's related-series list pulls from both MangaUpdates buckets.
- **Shikimori tracker search now shows authors, artists and a description.** Looking up a title to track also makes fewer network requests than before.

### Fixes

- **A dropped connection now pauses downloads and picks back up on its own.** Losing network mid-download shows a resumable Paused notification and resumes automatically once you're back online, instead of failing the chapter and freezing on a stuck progress bar.
- **A stuck or failed download no longer holds up the ones you queue next.** Adding chapters while a failed download sits in the queue starts them right away instead of staying paused until you manually resume.
- **Cloudflare-protected sources that rely on FlareSolverr respond faster instead of hanging.** Once the in-app browser can't clear a site's challenge, browsing it, opening a title, and loading chapters hand off to FlareSolverr straight away instead of re-waiting 30 seconds on each request.
- **Shikimori recommendations work again after the site's domain change.** They were still pointed at the old address, so they had stopped showing up.
- **The notes editor no longer crashes when you background the app or select text.** Editing a title's notes is stable again.
- **Restored downloads appear right after a backup restore.** The download state no longer waits for an app restart to catch up.

## [0.1.7]

### Fixes

- **Chapters now open again on sources that run their own JavaScript.** Some sources decode their page list with an in-app JavaScript engine; a missing engine class made those chapters fail to open, and it is restored.
- **Uninstalling a light-novel source works right after installing it.** The trash button used to do nothing until you closed and reopened the app.

### Other

- Synced upstream Mihon changes: dependency and tooling updates, the Shikimori tracker's new domain, and compatibility fixes for a newer XML library and Material components.

## [0.1.6]

### Additions

- **Import adult galleries from a link.** Share or open a supported adult-source gallery link and pick Reikai to add it straight to your library, landing on its details page with chapters ready.
- **Batch add galleries from the More menu (once adult sources are enabled).** It takes a pile of pasted gallery URLs, or a visited-galleries export, and imports them one by one with a live progress list.
- **More adult sources are now built in, no extension to install.** They browse, read, and import directly once adult sources are enabled, including one that previously needed an extension that no longer works.
- **Adult-source browse shows a rating, category, page count and more on each result.** Browsing the built-in adult sources now lays out each result's rating, category, page count, language, uploader and date instead of a bare cover and title.
- **Search adult-source library entries by tag, with namespaces, wildcards and exclusions.** Type queries like `artist:name`, `parody:*hero*`, or `-language:japanese` to filter by captured tags; plain title search is unchanged.
- **Adult-gallery details now show grouped, tappable tags and a full info panel.** Tags appear grouped by namespace (tap one to search it), and a panel above the description lists the rating, uploader, page count, size, language and upload date.
- **Preview an adult gallery's pages from its details screen.** A grid of page thumbnails sits above the description; tap one to open the reader at that page, tap More previews for the full gallery with page-to-page navigation, and set how many rows show (0 hides it) in Appearance settings.
- **Remove every source of a merged series in one step (manga and novels).** Deleting a merged library entry now offers an "All grouped sources" option that clears the whole group at once instead of leaving the other sources behind.
- **Keep adult content off your lock screen (Security and privacy, on by default).** The new "Hide adult content in notifications" setting strips adult titles and covers from notifications across all adult sources; your normal library notifications are unaffected.

### Changes

- **Interrupted downloads now resume instead of restarting, on sources that support it.** A download cut off mid-page, or by the app closing, continues from where it stopped instead of re-fetching finished pages, piling up duplicates, stalling, or needing a manual restart.
- **The adult-gallery update checker shows a clearer notification.** Its progress matches the library updater, and any galleries that fail to update raise a notification you can tap to see exactly which ones, instead of failing silently.

### Fixes

- **Browsing adult content sources now loads past the first page.** The built-in adult-source browse stopped after the first set of results; it now pages all the way through.
- **Built-in adult sources show their own icon on library covers.** The sources that ship without an installable extension no longer fall back to a generic icon on a cover's source badge, matching how they already appear in Browse.
- **Merged galleries update when you refresh from their details.** A source merged into an entry from elsewhere used to stay stale until you reopened it from Browse; refreshing the details now fetches every merged source at once.
- **Adult-source image-quality options now take effect.** The account image-quality picker listed outdated resolutions, so most choices silently did nothing; it now matches the site's current tiers.
- **Merged adult galleries now show every source's chapters.** Combining the same gallery across two adult sources no longer drops one from the unified chapter list.
- **Built-in adult sources no longer trip site rate limits.** They throttle their requests to stay within each site's limits, avoiding bans.

### Other

- Build the app and publish previews only when an app-affecting file changes; docs and other repo-only updates no longer trigger a build.

## [0.1.5]

### Fixes

- **Merging a series' sources now takes one tap.** Selecting the cards for the same series from different sources and tapping Merge now combines all of them at once, including same-title copies that were auto-grouped, instead of needing several taps to fully coalesce. Most noticeable after restoring a backup. Applies to both the manga and novel libraries.

## [0.1.4]

### Fixes

- **Cloudflare bypass proxy handles JSON and sessionless solvers.** Pages fetched through a bypass proxy (FlareSolverr or Byparr) now load correctly when the response is JSON, and Byparr's sessionless mode is supported, instead of failing with a parse error or showing nothing.

## [0.1.3]

### Additions

- **Migrate failing entries from the update-errors screen.** Select entries that failed their last update (the update-errors list), tap Migrate, and they go straight into the migration flow to move them onto a working source. The list is opt-in: turn on Settings → Advanced → Track manga update errors and Track novel update errors first, then open it from the library overflow menu. ([#15](https://github.com/unseensnick/Reikai/issues/15))

### Fixes

- **Extensions re-trust themselves once their repository is present.** After updating from an old build, restoring a backup, or adding a repository by hand, installed extensions no longer stay "untrusted" until you restart the app: they re-check automatically as soon as the repository lands. A "Re-check extensions" action in the Browse → Extensions overflow menu can trigger the same re-check on demand. ([#14](https://github.com/unseensnick/Reikai/issues/14))

## [0.1.2]

### Additions

- **Migrate light novels from Browse → Migration.** The Migration tab now has the same All / Manga / Novels switch as the rest of Browse: pick a novel source to see its saved novels, select the ones to move, and run them through the existing novel migration flow. A source still shows (with its last-known name and icon) even after its plugin is uninstalled, so you can always migrate away from it.

### Fixes

- **Fixed the crash on launch after updating from an old Yōkai-Y2K build.** Updating in place from a pre-rebase (1.9.x) build left a database the new app couldn't open, so it crashed on startup. It now recovers your manga and novel libraries plus your extension repositories automatically on first launch (a brief notice shows while it restores), with your previous data kept safe. Merged series come back unmerged, so re-create any merges you want. ([#11](https://github.com/unseensnick/Reikai/issues/11))

## [0.1.1]

### Other

- Expanded automated test coverage for backup restore, novel chapter sync, and metadata parsing, and fixed an internal cookie-removal helper that could miss cookies after the first.
- Stopped logging a harmless cast error for every installed extension at startup (the extension lib version is now read without the failing conversion).

## [0.1.0]

### Additions

- **Read a merged manga straight through all its sources.** Opening a merged series in the reader now flows through the whole group: the in-reader chapter list shows every source's chapters (each labeled with its source), and reaching the end of one source's chapters continues into the next without leaving the reader. Downloads and tracker updates follow each chapter's own source.
- **Open Reikai's settings from Android's system settings.** Reikai now appears as a configurable app in Android Settings; opening it there jumps straight to the in-app Settings screen. (Synced from Mihon.)
- **Built-in adult content sources.** Turn on Settings → Advanced → Enable adult sources to add them to Browse, then search with full filters (including tag autocomplete as you type), open an entry, and read it.
- **Find saved entries by tag in your library.** Library search now also matches the indexed tags of adult-source entries, so typing a tag name surfaces every saved entry carrying it.
- **View an entry's full metadata.** Adult-source entry details get an info action (overflow menu) that lists every captured field: tags, uploader, rating, size, page count, language, and dates, with long-press to copy. A dedicated settings screen lets you log in to the account-backed source and set image quality, titles, and tag thresholds; your choices are synced to your account automatically.
- **More adult sources gain searchable tags.** Installing additional adult-source extensions now records each entry's namespaced tags (artist, group, parody, character, and more) into your library, so library tag search and the info viewer work for them too.
- **Keep favorited adult-source entries up to date.** A background checker re-checks your favorited entries for newer versions and pulls them in, merging the new version's pages while keeping your read progress and bookmarks. Set how often it runs (and any Wi-Fi / charging limits) in its settings.
- **Back up favorites to your account.** Turn on Favorites backup in the source's settings, and favoriting an entry also adds it to your account's favorites, so your library can stay disposable while the account keeps a record. Removing an entry from your library leaves it on the account unless you tick "Also remove from favorites" in the confirmation. A "Back up all favorites now" button pushes everything already in your library.
- **App backups now include adult-source tags.** A backup of an adult-source entry now carries its captured tags, so restoring brings them straight back: library tag search and the info viewer work immediately, without re-opening each entry.
- **Choose which source to migrate for a merged series.** Migrating a manga or novel that's merged across several sources now opens a picker first, so you can move just the source(s) you want (the rest of the group stays put); an entry that isn't merged skips straight through as before.

**Light novels**
- **First-class in your library.** A Manga / Novels chip switches the library between the two; novels get the same grid, grouping, badges, multi-select, and Filter / Sort / Display sheet as manga, with their own categories.
- **Browse and install novel sources.** Add LNReader plugin repos from the Repos screen and browse novel sources in a catalogue styled like manga: Popular / Latest, filters, source settings, search, in-library badges, long-press to add, and pin favorite sources to the top.
- **Global search across novel sources.** One query searches every installed novel source at once, each filling in its own row; filter by Pinned / All / Has results, and tap a source to open its full results.
- **A full novel details screen.** Matches the manga layout, with chapter multi-select, a Filter / Sort / Display sheet, hideable chapters, Edit info, WebView / Share, and per-page loading for huge chapter lists. Saved novels open instantly from local storage and refresh on demand.
- **A full-screen novel reader.** LNReader-style typography with a live Display / Theme sheet (fonts, size, spacing, margins, light / sepia / mint / dark / black themes, plus custom brightness and a colour filter with blend modes, the same controls as the manga reader and kept separate from it), saved scroll position, a prefetched next chapter, and tap-to-hide immersive mode. The bottom bar carries chapter skip, a chapters list to jump around, a rotation toggle, and a WebView button that opens the current chapter on the source site; the top bar bookmarks the current chapter, and a progress seekbar tracks how far you've read.
- **Read novels aloud (text-to-speech).** A floating play button voices the chapter with your device's voices, highlighting and scrolling each paragraph as it goes. A new TTS settings tab picks the voice (filter the list by language), speed, pitch, and auto-advance to the next chapter; the button fades while playing and can be dragged anywhere. Playback keeps going when you leave the app or turn the screen off, with play / pause / stop on the lock screen, the notification, and headset buttons.
- **More reader controls (General tab).** The novel reader settings are reorganized into General / Display / TTS tabs, and the new General tab adds bionic reading (bold the start of each word), remove extra spacing, auto-scroll with a speed control, a vertical progress seekbar, tap the top / bottom edge to scroll, and swipe left / right between chapters.
- **Offline downloads.** Save chapter text with inline images, one at a time or in batches, on a single background queue that paces itself per source and resumes after a restart.
- **Reorder and sort the novel download queue.** Drag chapters to set the download order (it now survives a restart), or sort by upload date or chapter number. In the combined queue, sorting applies to manga and novels together.
- **More novel download settings, matching manga.** Under Settings → Downloads: delete a chapter when you mark it read (from the reader, the chapter list, or the library), keep only the last N read chapters downloaded, never delete bookmarked chapters, exclude categories from auto-delete, and download-ahead (auto-download the next few chapters as you read).
- **Per-title novel update notifications.** When favorited novels gain new chapters, you now get one notification per novel (grouped together) that opens that novel when tapped, instead of a single "N novels" line.
- **Background updates in the Updates tab.** Favorited novels re-check on a schedule (interval, device restrictions, category include / exclude, Smart update) and optionally auto-download; new chapters join a unified All / Manga / Novels Updates feed.
- **Home-screen widget for manga and novel updates.** A resizable widget shows your recently updated manga and novels together in labeled sections; tap a cover to open it. Add it from your launcher's widget picker (the manga-only Updates widget is still there too).
- **Novels in the History tab.** Reading a novel records it in History; the tab interleaves recently read manga and novels (All / Manga / Novels chip), newest first, with search, tap to resume, and delete or clear.
- **Cross-source merge.** Combine the same novel from several sources into one cover and one deduplicated chapter list with a source switcher and shared read state, by hand or automatically by title.
- **Migrate novels to another source, one or many at once.** From a novel's overflow menu, or by multi-selecting several novels in the library, each novel auto-searches your sources and suggests a match you can accept or change; picking carries read / bookmark / scroll-progress (matched by chapter number), categories, your custom cover, notes, and tracker links, and re-downloads any chapters you had saved offline. Choose Copy (keep the original too) or Migrate (replace it). Cover and notes options appear only when a selected novel actually has them. A source picker first lets you choose which sources to migrate to and drag them into priority order, so matches come from the sources you prefer. Each novel is then shown side by side with its match (covers, source, and chapter counts) so you can compare at a glance; tapping a cover opens that novel's details to read the description, and a match with fewer chapters than your current source is flagged in red. Changing a match lists the alternatives as browse-style rows grouped by source.
- **Track novels on AniList, MyAnimeList, MangaUpdates, and Kitsu.** A Tracking action on a novel binds it to any tracker you're already signed into, then set status, chapters read, score, and dates. Reading progress pushes automatically as you finish chapters (and queues to retry if you're offline).
- **Plugins stay current.** The Browse badge counts pending plugin updates, checked in the background, with one-tap reinstall and real plugin icons.
- **Pull to refresh a novel's details.** Swipe down on a novel's page to recheck its info and pick up new chapters.
- **Incognito mode now covers novels.** With Incognito on, reading a novel records no history, saves no progress (no resume position or read state), and skips tracker sync, and opening a novel source no longer updates Last Used, matching how manga behaves.
- **Keep the screen on while reading a novel.** A new switch in the novel reader's Display settings holds the screen awake, just like the manga reader.
- **Lock the novel reader's orientation.** Pick a per-novel orientation (Default, Portrait, Landscape, or a locked variant) from the reader's Display settings, with a global default under Settings → Reader, just like the manga reader. "Default" follows the global default.
- **Novel downloads respect "Download only over Wi-Fi".** With that setting on (Settings → Downloads), novel chapter downloads now wait for Wi-Fi instead of using mobile data, the same as manga.
- **Failed novel downloads retry before giving up.** A chapter download that hits a network blip or a momentarily busy source now retries a few times with a short backoff, instead of failing on the first stumble.
- **Novels now appear in Statistics.** An All / Manga / Novels switch on the Stats screen shows reading time, library size, chapters, and tracker stats for novels too, or both content types combined.
- **Mark chapters read when you skip ahead (novels).** Turn on "Mark chapter read when skipping ahead" for Novels (Settings → Reader) and tapping Next in the reader marks the chapter you skipped past as read, just like the manga reader.
- **Per-novel notes.** Keep a private markdown note on any saved novel from its details screen (overflow menu → Notes), using the same editor as manga. Saved with the novel and included in backups.

**Library**
- **Cross-source merge for manga.** A series from several sources shows as one cover with combined unread counts and one deduplicated chapter list behind a source switcher; merge by hand or automatically by title, with Manage sources and a Preferred sources ranking.
- **Dynamic grouping.** Group the library by source, tag, author, language, status, or tracking status instead of by category, with collapsible groups, in both views and for both manga and novels.
- **Single-list view with a category hopper.** An optional one-scroll view of collapsible categories with a floating jump-to hopper, plus per-category sort, refresh, and select-all.
- **Pull down to update the whole library in single-list view.** Swipe down from the top of the one-scroll category view to start a library update (the same as the overflow menu's Update library), for both manga and novels.
- **"Downloaded only" mode now covers the novel library.** Turn it on (More menu) and the novel library hides novels with no downloaded chapters, matching manga; the Filter sheet's Downloaded chip locks on while the mode is active.
- **Category sort order and hidden categories.** Order categories Off / A to Z / Z to A everywhere they appear, and hide a category without deleting it (it round-trips through backups, including Komikku).
- **Delete categories with undo.** Long-press to multi-select categories (Select all / Invert) and delete several at once, or delete one from its row; either way an Undo snackbar lets you take it back. Works on both the Manga and Novels category tabs.
- **Library update-errors screen.** Opt in under Settings → Advanced for an Update errors list of entries that failed their last update, grouped by reason.
- **Panorama grid and source-icon badges.** A comfortable grid that shows wide covers uncropped, and an optional source-icon badge on covers.
- **Adult-content and category filters** in the library filter sheet.
- **Add to your library from global search.** Long-press a result in global search to add it (with the category picker and possible-duplicate check) or remove it if it's already saved, the same as the per-source browse screen. Works for both manga and novels.

**Manga details & recommendations**
- **Related-manga recommendations carousel.** A Related row suggests similar titles (with in-library badges) from the source and, when enabled, from AniList, MyAnimeList, MangaUpdates, and Shikimori; tap to open or global-search, and a See all grid bulk-adds with category handling.
- **A Recommendations settings screen** (Settings → Library → Recommendations) toggles tracker recs per tracker, builds a taste profile from your tracker libraries, and offers style, serendipity, auto-refresh, and library / status filters (all off by default).
- **Two-finger range selection** on manga and novel chapter lists: press two rows to select everything between them.

**Reader**
- **New options:** resume reading position, pages to preload (default 4), and mark a chapter read when you skip ahead.
- **A customizable bottom bar and an in-reader chapters list** to jump to, bookmark, or download chapters without leaving the reader.
- **Cover-color theming.** Tint the reader and manga details with each manga's cover color (Settings → Appearance, on by default).

**Networking**
- **Cloudflare bypass proxy support.** Route a blocked source through a self-hosted bypass proxy instead of the in-app WebView (Settings → Advanced → Networking); the WebView solver stays the default and the fallback.

**Backup & restore**
- **Your novel library is now backed up.** A backup captures your favorited novels with their chapters, read state, categories, history, tracker links, and cross-source merges; restoring on a fresh install brings the whole novel library back. Restoring over an existing library keeps whichever copy is newer, so an older backup won't overwrite edits you've made since (matching how manga restore works). Older backups made before this still restore fine.
- **Installed sources come back on restore.** A backup now records which manga extensions and novel plugins you had installed, so a restore reinstalls them automatically; anything whose repo is missing is listed in the restore log so you know what to add back by hand.

### Changes
- **See whether an extension update is for manga or novels at a glance.** On Browse → Extensions, the Manga and Novels chips now carry a count of their pending updates, so you can tell which side the update is on instead of just seeing one number on the tab.
- **Adult-source entries show their source logo in your library.** Saved entries from the built-in adult sources now use the source's mark as their badge, instead of a generic icon, matching the Browse source list.
- **Hide a novel source you don't use.** Long-press a source in Browse → Sources and Disable it: it dims in the list and drops out of global search, while staying installed and updating. Long-press again to re-enable.
- **Add a novel to your library straight from History.** A novel in the History tab that you haven't saved now shows an add-to-library button (like manga history rows); it favorites the novel and drops it in your default novel category, or asks.
- **New novels can auto-land in a default category.** Pick a default novel category under Settings → Library → Categories (next to the manga one); novels you add then go straight there instead of always asking, the same as manga.
- **Filter a novel's chapters by downloaded.** The novel chapter Filter sheet adds a Downloaded toggle (show only downloaded, or only not-downloaded) next to Unread and Bookmarked, the same as manga.
- **See which novels failed to update.** Turn on "Track novel update errors" (Settings → Advanced) and novels that fail an update are recorded; the Update errors screen gains All / Manga / Novels chips so both libraries share one list. Tracking is independent per type.
- **Update just a category of novels, and refresh novels from the Novels library.** A category's refresh button and pull-to-refresh on the Novels chip now update novels (they previously kicked off a manga update by mistake), and you can update a single novel category like manga.
- **Track a novel privately.** A tracked novel's Tracking sheet now has a "Track privately" toggle in the per-tracker menu (for trackers that support it, like Kitsu and AniList), keeping that entry off your public tracker profile, the same as manga.
- **Adult-source settings have their own place in Settings.** With adult sources enabled, the source's settings now appear as their own top-level Settings category (with its logo) between Security and Advanced, instead of being tucked inside Advanced. The "Enable adult sources" switch stays in Advanced, and the category hides again when you turn it off.
- **The built-in adult sources show their logo.** They now display their source mark in Browse instead of a blank placeholder icon.
- **More adult-source settings.** The settings screen adds Incognito mode (keeps that reading out of your history), Language filtering and Front-page categories (which sync to your account), and an updater-statistics view.
- **Adult-source favorites backup is gentler and more reliable.** Backing up a large library to your account now paces itself with a gradual backoff instead of a fixed delay, so it is less likely to trip the source's rate limits, and each favorite push retries a few times so a brief network hiccup no longer silently drops it.
- **Adult-source entry details show their full tags from the library.** Opening a saved adult-source entry from your library now expands the description and tag cloud by default, the same as when browsing the source. These entries have no description and their tags are the content, so they no longer hide behind a single sideways-scrolling row.
- **Renamed the fork to Reikai.** Installs upgrade in place (same package ID), and the launcher shows the new R-monogram icon and "Reikai" label.
- **The library Display options sheet is now tab-aware,** so a filter or category change made on the Novels tab no longer reaches into the manga library.
- **Extensions no longer tied to a repository are labeled "Orphaned"** instead of "Obsolete", with a clearer note that they won't receive updates.

### Fixes
- **Changing an adult source's update settings no longer crashes the app.** On optimized (preview / release) builds, changing the update checker's "Automatic updates" schedule crashed the app; it now applies normally.
- **Saved adult-source entries no longer get re-fetched on every library update.** They are now skipped by the regular library update (their dedicated update checker still handles them), so updates finish faster and stop needlessly hammering those servers.
- **No more duplicate built-in adult source if you also install its stock extension.** With built-in adult sources enabled, the matching stock extension is now hidden and its sources skipped, so it can't shadow or double up the built-in one.
- **The adult-source settings category shows up the moment you enable adult sources.** Turning on Settings → Advanced → Enable adult sources now reveals (and turning it off hides) the category on the main Settings screen immediately, instead of only after leaving Settings and coming back.
- **No more empty "Favorites backup" header in the adult-source settings.** Its options all need an account login and were hidden when logged out, leaving just the header; the whole section now appears only once you are logged in.
- **The restore screen opens reliably after you pick a backup file.** Choosing a backup (Settings → Data and storage → Restore, or from onboarding) sometimes needed a second tap before the "what to restore" options appeared; it now shows on its own.
- **Restoring a backup no longer lists your extensions twice.** After a restore, each reinstalled extension could appear both as a normal (trusted) entry and as a phantom "untrusted" duplicate. The reinstall now runs cleanly and a trusted extension correctly clears any stale untrusted entry, so the list is right immediately (no app restart needed).
- **Restoring a backup no longer drops random manga into the Default category.** A timing issue let some manga restore before their categories existed, so they landed in Default; categories now finish restoring first, so every manga keeps its categories. (A long-standing Tachiyomi-lineage bug.)
- **Manga merge groups now survive a restore to a fresh install.** Merges were saved as internal ids that change on restore, so groups could come back wrong; they are now saved as stable source + URL references and rebuilt correctly, the same way novel merges already were.
- **Migrating a merged manga or novel keeps the merge.** Moving an entry that belongs to a multi-source merge group used to drop it out of the group (and on the manga side leave a stale reference to the old source); migration now puts the new source in the old one's place, so the series stays merged. Works whether you merged the sources by hand or they auto-grouped by title.
- **A novel's "Download → Next 5/10/25" now advances through the book.** It used to keep re-picking the first chapters (already downloaded) and queue nothing on repeat taps; it now skips downloaded chapters and continues to the next batch.
- **The novel reader no longer crashes on a chapter with repeated paragraphs** (blank lines, scene breaks, recurring phrases).
- **Adding a light novel no longer creates a duplicate library entry** when you add the same novel again.
- **Novel plugins load and uninstall reliably.** Installed plugins now load in parallel and retry on the next Browse/Library open instead of needing repeated cold restarts; installing no longer hangs, uninstalling fully removes a plugin (even one installed from more than one repo), and reinstalling from a new repo replaces the old one. The Browse → Extensions (Novels) tab shows a restored repo right away and, when a repo can't be reached, offers Retry instead of claiming you have no repos.
- **Typing fast in the novel library search no longer scrambles or drops characters.** The search box now updates instantly per keystroke (matching the manga library) instead of lagging behind a background refresh, so a quick query like "shadow" filters correctly instead of coming out as "haodws".
- **Reading no longer fails with a random missing-image error.** When a cached page had gone missing from disk, opening it could throw a FileNotFoundException; the reader now treats it as not cached and re-fetches. (Synced from Mihon.)

### Other
- **Novel library writes are now surgical.** Favorite, cover, chapter-flag, and orientation changes update only the column they touch instead of rewriting the whole novel row, matching how the manga side works.
- **Fixed a startup crash in optimized builds.** Preview and release builds crashed on launch (a code-shrinker rule didn't cover the light-novel package); they now start normally.
- **Reikai is now built on the Mihon base.** The previous release was a fork of Yokai; this cycle rebases the app onto Mihon, so the core manga reader (library, details, reader, tracking, extensions, backups) is Mihon's, with Reikai's own features (light novels, cross-source merge, recommendations, and the library, reader, and theming additions above) rebuilt on top. This is why the core UI looks different; the `.y2k` package id is preserved so existing installs upgrade in place.
- **Support for TachiyomiX 1.6 extensions** (via the Mihon sync): the newer extension format installs and loads, existing extensions keep working, sources can attach hidden metadata carried through backups, and older backups still restore.
- **Synced upstream changes from Mihon:** Coil / OkHttp / Firebase updates, a SQLite driver build that avoids a rare database stall on a cancelled write, lifecycle-bound background tasks, and auto-following extension repositories that moved to the newer index format (now also reading gzip-compressed indexes and stores that keep their extension listing in a separate file).
- **Faster app startup.** Refreshed the bundled startup profiles (synced from Mihon) so common screens warm up sooner on first launch.
- **Faster backup restore.** Restoring a large library now batches its database writes in chunks, cutting restore time on big libraries; the speedup covers both manga and novels.
- **More Mihon upstream sync:** updated translations, refreshed app-shortcut icon colors, a Catppuccin theme tweak for clearer unread and downloaded badges, support for the newer tachiyomix extension metadata, and networking and dependency cleanup.
- **Crash screen points to Reikai's bug tracker.** If the app hits an unexpected error, the crash screen now suggests opening a GitHub issue (instead of Mihon's Discord), and the shared error/log files (crash, restore, library update) and the library CSV export are named for Reikai.

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

## Earlier releases

Versions before `1.9.7.5.1` are inherited from upstream Yōkai (Reikai began as a fork of Yōkai 1.9.7.5). See the [Yōkai project](https://github.com/null2264/yokai) for that history.
