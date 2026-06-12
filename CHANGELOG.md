# Changelog

All notable changes to this project will be documented in this file.

The format is simplified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/):
- `Additions` - New features
- `Changes` - Behaviour/visual changes
- `Fixes` - Bugfixes
- `Other` - Technical changes/updates

## [Unreleased]

### Additions
- **Related-mangas carousel on manga details.** Below the description (and at the top of the chapter pane on tablet), a horizontal **Related** row suggests similar titles, ranked for relevance, with an **in-library badge** on ones you already have. Suggestions come from the source itself and, when tracker recommendations are enabled, from **AniList, MyAnimeList, MangaUpdates, and Shikimori** (these work even for manga you don't track). Tap a source suggestion to open it; tap a tracker suggestion to look it up across your installed sources via global search. A **See all** action opens the full set in a cover grid where you can **long-press (and drag-select a range) to multi-select and add several to your library at once** (with category handling), and optionally **group the grid by where each suggestion came from** ("From this source", "Because you're reading X", and so on); when filters are on, a **show-hidden toggle** reveals the suppressed items. Near-duplicate suggestions that differ only by accents, fullwidth characters, or punctuation are collapsed instead of showing twice. A new **Settings → Library → Recommendations** screen lets you turn tracker recommendations on or off per tracker, opt into using each connected tracker's library (**AniList, MyAnimeList, Kitsu, Shikimori, Bangumi**) as a **taste profile** that reorders the row toward what you read. For a manga you've **tracked**, it also adds suggestions drawn from your trackers: titles similar to the ones you've rated highly that your tracker links to this manga ("Because you're reading…"), and a search of this source for the genres you enjoy ("Matching your taste"), both toggleable in settings (shown only when a tracker is logged in). The row also has **Recommendation style** and **Serendipity** controls, an optional **weekly or monthly auto-refresh**, and a **Refresh now** action that now shows **when each tracker library was last pulled**. Optional **Filters** can hide suggestions you already have in your library, or that you track with a given status (reading/completed, dropped, on-hold, plan-to-read), matched against both your library and your tracker lists by tracker id where available (falling back to title), so a title you finished on a tracker but never saved locally is caught too. All filters are off by default.
- **Manga from the same series across multiple sources now merge into one.** When the same title is in your library from several sources, the library shows it as a **single cover with combined unread counts**, and opening its details shows one merged, de-duplicated chapter list pooled from every source (matched by chapter number); each chapter opens and downloads from its own source, and marking read or bookmarked applies across the group. A **source switcher** above the chapter list flips between the combined view and any single grouped source. Use **Manage sources** in the overflow menu to split a source back out, split the whole group apart at once (select every source), remove a source from your library, or remove the whole group, all with Undo. **Long-press to select manga in the library and tap Merge** to group them by hand (works in both the tabbed and single-list views), or **Unmerge** to split a merged selection back apart. A **tracker added to one source is shared with the others in the group**, and each source keeps its own link after you unmerge (toggle under **Settings → Tracking**). A tracker check automatically separates same-titled entries that are actually different series, and **Settings → Advanced** has actions to clear manual merges or separate every merged series. **Settings → Library → Preferred sources** lets you rank your sources so the top-ranked one leads a merged chapter list (otherwise the source with the most chapters does).
- **FlareSolverr support for bypassing Cloudflare.** When a source is blocked by a Cloudflare challenge, you can route it through a [FlareSolverr](https://github.com/FlareSolverr/FlareSolverr) proxy server instead of the in-app WebView, which handles stricter protection and devices with an outdated WebView. Turn it on in Settings → Advanced → Networking: enable FlareSolverr, enter your server URL, and use the Test button to confirm it works and sync the user agent. The WebView solver stays the default and the fallback when FlareSolverr is off.
- A new reader option, **Settings → Reader → Reading → "Mark chapter read when skipping ahead"** (off by default), marks the chapter you were on as read when you jump to the next chapter, so skipping forward no longer leaves unread chapters behind. Forward skips only, and it respects incognito mode.
- The library has an optional **single-list view** that shows every category as a collapsible section in one scroll, with a floating **category hopper** to jump between categories (previous/next, or pick one from a list) that can be dragged to the start, center, or end of the screen. Turn it on in the library display settings ("Show all categories in one list"); the swipeable category tabs remain the default. The single-list view follows your chosen **display mode** (compact grid, comfortable grid, cover-only grid, or list).
- Browse now **remembers whether you were on the Manga or Light novels tab**, so backing out of a source returns you to the same side instead of resetting to Manga.
- **Browsing a light novel source now opens the full novel details screen** (blurred cover backdrop, expandable description with genres, merged-source switcher, edit info, downloads, chapter filter/sort) instead of a basic preview. Opening a novel from browse no longer silently adds it to your library; use the "Add to library" button.
- **Browsing a light novel source now shows a cover grid** (comfortable or compact density, or a list), with a toolbar toggle to switch between list and grid. The grid is sized to match the manga browse, and the browse list/grid choice is remembered separately from your Novels library layout. Results **load more as you scroll** (stopping at the end of the source), and novels **already in your library are marked with an "In library" badge**. **Long-press a result to add it to (or remove it from) your library**, with an Undo when removing. A new **Default category** setting for novels (in the Novels library category settings) controls how an added novel is filed: last used, always ask, the Default category, or a specific one. A failed source load now offers a **Retry** alongside the existing "open in WebView" fallback, and **returning from that WebView re-runs the load automatically** (so solving a Cloudflare check then backing out shows the results without tapping Retry).
- **Light novel global search.** On Browse → Light novels, typing a query and pressing search now looks it up **across all your installed light novel sources at once**. Each source shows its own row of results that fills in as it finishes (with its own loading, "no results", or error), and tapping any cover opens that novel's details.
- The Compose library search now uses a **random library title as its example hint**, and when a search turns up **no matches it offers a "Global search"** action to look the query up across your sources (restoring two affordances from the legacy library).
- **Reading a light novel now opens a full-screen reader.** Chapters render with their formatting (paragraphs, emphasis, images) and LNReader-style typography, with a Display / Theme settings sheet for **font size, line height, margins, a choice of bundled fonts, and text alignment**, plus **light / sepia / mint / dark / black themes or follow-system**, all applied live. **Tap to toggle an immersive view** that hides the system bars, your **scroll position is saved and restored** per chapter, and you can move to the **next or previous chapter** (across grouped sources) without leaving the reader, with the next chapter prefetched so flipping forward is instant.
- **Light novel chapters can now be downloaded for offline reading.** Tap the download icon on a chapter in a saved novel's details to save its text to your device; a downloaded chapter opens instantly with no network and works in airplane mode. Tapping a chapter's download icon now opens a menu to **start it now, cancel a queued one, or delete a finished download**. Download many at once by long-pressing to select chapters then tapping Download, or via the toolbar download icon (download next, next 5, next 10, unread, or all). Downloads run in the background with a progress notification (with a Cancel action), keep going if you leave the screen or close the app, and resume after a restart; a running queue can also be stopped from the details download menu. Downloads are paced per source and automatically ease off if a source is slow or starts refusing, so a large batch stays gentle on the site. From the Novels library you can also select novels and tap Download to queue their unread chapters, and a new **Settings → Downloads → Download new novel chapters** toggle auto-downloads chapters as the background library update finds them.
- You can now **hide individual chapters** on a novel's details screen: long-press to select, then **Hide** in the selection menu, useful for a source's own duplicate listings. Hidden chapters stay hidden across refreshes, and a **Show hidden chapters** entry in the overflow reveals them (dimmed) so you can unhide.
- **Light novels now have a preferred-source ranking.** The Preferred sources screen (Settings → Library) is split into **Manga** and **Light novels** tabs, so you can separately rank the sources you trust most for each library. For novels with the same title saved from several sources, the top-ranked source becomes the backbone of the merged chapter list.
- Saved novels with the same title across multiple sources now show a **single merged chapter list** with a **source switcher** above the description. The unified view pools every grouped source's chapters, matching the same chapter across sources by its title (or number), so duplicates collapse even when sources number chapters differently. Tapping a source chip switches to that source's own chapters, name, and status; each chapter opens from the source it came from; marking chapters read or bookmarked applies across all the grouped sources; and pull-to-refresh updates every source. Chapter numbers are recognized from the chapter name when a source doesn't provide them, so sort-by-number works too. Long-press a source chip to split it back out, or use **Manage sources** in the overflow to split grouped sources apart or remove them from your library. Splitting or removing a source now shows an **Undo** first, splitting the source you're viewing lands you on a remaining grouped source, and the library button's long-press menu on a merged novel offers **Remove all sources from library**.
- **Saved novels now open from your device instead of re-fetching every time.** A library novel's details and chapter list load straight from local storage, so they open instantly and work offline, with read and bookmark state shown per chapter. New chapters still arrive automatically through the background library update, and a refresh button in the details toolbar re-checks the source on demand (keeping your read progress).
- Saved novel details now use the same layout as the manga details screen: a blurred cover backdrop behind the cover, title, author, status and source, an expandable description with genre chips, and the chapter list, plus an **Add to library** toggle with category assignment. Backing out of the reader returns to the novel's details instead of the library, and the description, cover, author and status now refresh from the source on pull-to-refresh.
- The Compose novel details screen now supports **chapter multi-select** (long-press to start, long-press again to range-select, tap to toggle) with a contextual bar to mark read/unread, bookmark, mark previous as read or unread, select all, and invert. A toolbar **mark-all-read** toggle (with confirmation) and a **chapter search** are also available.
- **Two-finger range chapter selection.** On a manga or novel details screen, press two chapter rows at once to select every chapter between (and including) them, a quick alternative to long-pressing the start then the end.
- The Compose novel details screen gained **WebView** and **Share** actions (opening the novel's own page when the source supports it, the source homepage otherwise) and a **Resume / Start reading** button that jumps to the first unread chapter.
- The Compose novel details overflow now has **Edit categories** and **Edit info** (override title, author, artist, description, genres), handy for adding a description to sources that don't return one. Manual edits are kept when you refresh from the source; clearing a field reverts it to the source value on the next refresh.
- The Compose novel details chapter list gained a **Filter / Sort / Display** sheet (filter icon in the chapter header): sort by source order, chapter number, or upload date (tap to flip direction); filter by unread or bookmarked; and show the source chapter title or a "Chapter N" label. "Set as default" carries your choice to other novels, with a reset; per-novel overrides are remembered.
- The Compose novel details screen reached closer parity with the manga screen: the **Continue/Start reading button now names the chapter** and **collapses as you scroll**, chapter rows show their **upload date** and **reading progress (% read)**, you can **swipe a chapter to mark it read or bookmark it**, **long-press the title/author/genres to copy**, and **tap the cover to zoom, save, or share** it. **Edit info** now also covers a **status override** and a **Reset to source** button. For a novel merged from several sources, switching to a specific source chip now shows that source's own description, status, and cover coherently, and editing while on that chip edits that source.
- **Marking novel chapters read** now offers an **Undo**, and **rewinds your reading position** when you mark a chapter unread. A new **Settings → Downloads → Remove novel chapters when marked as read** toggle deletes a chapter's offline download once it's read; tapping Undo restores the download too (it isn't actually removed until the Undo prompt disappears).
- **Pull-to-refresh on a novel's details now auto-downloads newly found chapters** when "Download new novel chapters" is on, and **prompts before deleting downloads** of chapters that were removed at the source. A new **Settings → Downloads → Delete removed novel chapters** setting controls whether that's always, never, or ask.
- The library update **frequency** and **device restrictions** under Settings → Library → Global updates now drive novel auto-updates too, so one schedule covers your whole library. A separate **Skip updating novels** setting sits beside the manga one: by default it skips novels with unread chapters (matching manga, so a large unread backlog isn't auto-updated all at once), and unchecking "With unread chapter(s)" lets the background update fetch new chapters for novels you haven't caught up on.
- The library filter/display sheet gained a **Lewd** content filter and an include/exclude **category filter** (its Edit picker also opens the category manager), plus a new **Group** tab to dynamically section the library by category, tag, source, status, tracking status, author, language, or leave it ungrouped.
- A **Categories** section in the Display tab gathers the category options: a **sort order** (manual, A→Z, or Z→A) that applies everywhere categories are listed (library, the hopper's jump-to list, the category filter, and the category manager, which hides its drag handles while auto-sorted), **always show the current category** in the toolbar (it follows your scroll), **move collapsed dynamic groups to the bottom**, and **show empty categories while filtering**.
- You can now **hide a category** from the library without deleting it. Tap the eye icon next to a category in the category editor (its name dims to mark it hidden) and it drops out of the library and the hopper. Turn on **Show hidden categories** in the Display tab's Categories section to bring them back. The hidden state is included in backups (and round-trips with Komikku).
- New **Panorama comfortable grid** library display mode (Library settings → Display): manga with **wide cover art** show the whole cover instead of a cropped center, while normal portrait covers look unchanged. Works in both the swipeable category view and the single-list view.
- Library covers can now show a **source icon** badge, toggled in Library settings → Display → "Source icon". It applies in every library view.
- Optional **library update-errors screen**: turn on **Settings → Advanced → "Track library update errors"** and a new **Update errors** entry appears in the library's overflow menu, listing the entries that failed their last update (grouped by reason) so you can review, dismiss, or retry them. Off by default; Mihon's usual update notification is unaffected.
- In the **single-list library view**, each category header now has a **per-category sort** (shows the current sort with a direction arrow; tap to change it), a **refresh** button (updates just that category), and, while selecting, a **select-all** circle that toggles every entry in the category. Dynamic groups show only select-all.
- The category **hopper** gained options in that same section: **hide it**, **hide it while scrolling**, and a configurable **long-press action** on its center button (search, expand/collapse all categories, open display or group settings, or open a random entry from the current category or the whole library). A normal tap still opens the jump-to-category picker.
- When **"show all categories" is off**, the library shows a **scrollable category tab row** at the top to switch categories, in addition to the hopper and swipe. Swiping now **slides whole categories together like a pager**, each with its own independent scroll position.
- The Compose library's manga details screen was **visually reworked** to match the upstream/Komikku layout: a blurred cover backdrop behind a side-by-side cover and info block, an icon-over-label action row (Add to library, Tracking, WebView, Share), and an expandable description with genre chips. The toolbar adds a **chapter search** (filter the chapter list by name) and a **mark-all-read** button that flips to mark-all-unread once everything is read (both confirm first). The cover scales up on tablets and unfolded foldables.
- **Pull-to-refresh on the Compose manga details screen now updates the cover, description, and other details** (not just the chapter list), **auto-downloads newly found chapters** when that's enabled for the title, and **asks before deleting downloads** of chapters that were removed at the source.
- **Marking chapters read** on the Compose manga details screen now **advances your trackers**, **deletes the downloads** when "remove after marked as read" is on, **rewinds page progress** when you mark something unread, and offers an **Undo**.
- In the chapter multi-select bar, the read action **flips to "Mark as unread" when every selected chapter is already read** (a mixed selection stays "Mark as read"), mirroring the bookmark toggle. For a mixed selection, a dedicated **Mark as unread** also sits in the selection overflow menu (manga and novels), so you can always force chapters unread.
- The Compose manga details **continue-reading button now names the chapter** ("Continue reading Chapter 12" / "Start reading Chapter 1" / "All chapters read") and **collapses to an icon as you scroll down**, and the chapter list gained a **draggable fast-scroller** for jumping through long lists.
- The Compose manga details **tracking sheet now hides trackers that don't fit the source** (Komga, Kavita, Suwayomi) and **auto-matches** a compatible one when you add it, instead of making you search.
- The Compose manga details header now **tints its blurred backdrop with a color drawn from the cover** (a red cover reads reddish, a blue one bluish; honors the "Color details page from manga cover" setting), and the backdrop **drifts slightly slower than the chapter list as you scroll** for a subtle depth effect.
- The manga details recommendations carousel now **ranks titles that several sources agree on higher**: a series suggested by the source plus one or more trackers floats toward the top, since cross-source agreement is a strong signal. Applies when recommendation reranking is on.
- The manga details recommendations carousel now **hides titles that are already in your library**, including ones recommended via a tracker. Per-status toggles under Settings → Library → Recommendations let you choose which tracker statuses are suppressed (Reading, Completed, Dropped hidden by default; On-hold and Plan-to-read kept by default).
- The manga details **related/recommendations carousel now loads instantly when you reopen a title**. Results are cached for 30 minutes; reopening within that window skips the network entirely, and after it the cached cards show immediately while a fresh set loads quietly in the background.
- The Compose library's manga details screen now shows the **related-manga recommendations carousel** below the description. Each card carries a chip marking where the suggestion came from (the source, or the tracker for tracker-only picks), and a **See all (N)** link opens the full list (which now shows the same source/tracker chips). Tapping a card opens that title, or a global search for tracker-only suggestions.
- The Compose library's manga details screen now **updates live**: favorite status, read/unread and bookmark marks, chapter sort/filter, the scanlator filter, and newly fetched chapters all refresh in place, so you no longer have to back out and reopen the page to see changes (including read progress after closing the reader).
- The Compose library's manga details screen now shows a **unified chapter list for merged titles**. When a manga is grouped from several sources, their chapters combine into one deduplicated list (one row per chapter number, with chapters missing from the main source filled in from the others) ordered by chapter number. Tapping a chapter reads it from whichever source it came from, and marking chapters read or bookmarked from the list applies across all the grouped sources. A **source switcher** above the description lets you flip between the unified list and any single source; long-pressing a source removes it from the group. Picking a source switches the whole page to that source (its cover, description, chapters, WebView/Share, and its own recommendations), while the unified view shows everything pooled, including a **combined recommendations carousel** that ranks titles several of the merged sources agree on higher. The status line reads "Unified" in the pooled view. A new **Preferred sources** screen (Settings → Library) lets you rank the sources you trust most; when a merged title includes one, that source's chapters become the backbone of the unified list instead of whichever source happens to list the most chapters.
- The Compose library's manga details screen can now **manage merged sources**. For a title merged from multiple sources, a **Manage sources** entry in the overflow menu opens a checklist of the grouped sources (the one you're viewing is marked): split selected sources back out into separate entries, or remove them from your library. Single-source titles don't show the entry. Splitting or removing sources now shows an **Undo** snackbar (the change only applies once it dismisses), splitting the source you're currently viewing **jumps you to a remaining source** in the group, and if opening a merged title auto-cleans stale merge links you'll see a brief notice.
- The Compose library's manga details screen now supports **favorite, categories, edit info, share, open in web view, and migrate** (Phase 5). Tap the heart icon in the toolbar to add or remove from your library; if you have categories set up, a checkbox picker appears to assign them. Edit info overrides the title, author, artist, description, and genre from the overflow menu. Share and Open in web view work on online sources. Migrate launches the existing migration flow.
- **Private tracking** for AniList, Kitsu, and Bangumi: bind a title privately from the tracker search screen (the eye-off button next to "Track"), or toggle "Track privately / Make public" from a tracked entry's overflow menu. Private entries stay hidden on your public profile and show an eye-off badge in the tracking sheet.
- The Compose library's manga details screen can now **track titles**. A new tracking icon in the details toolbar opens a sheet listing every logged-in tracker; bind a title by searching the service, then set reading status, score, chapter progress, and (where the service supports them) start/finish dates, each on its own page. Remove a tracker from the title, optionally removing it from the service too. The sheet refreshes from the services when opened and updates live as you make changes.
- The Compose library now has a **library update errors** screen. When a library update fails for some manga, the top-bar overflow shows an "Update errors (N)" entry; opening it lists the affected manga grouped by error message (cover, title, source). Tap a row to open the manga, tap **Retry** to re-run the update, or long-press rows to multi-select and dismiss them. Errors clear automatically once a manga updates successfully.
- The Compose library's multi-select now supports **range-select**: long-press a cover to start selecting, then long-press another cover in the same category to select every item in between. Tapping still toggles one at a time.
- The Compose library's multi-select **download menu** now offers Download next / next 5 / next 10 / unread / all / bookmarked, instead of only "Download unread". "Next" entries skip chapters you've already downloaded before counting, so repeating them keeps queuing the next batch.
- **Legacy library now surfaces your novel library** through a Manga / Light novels tab strip at the top, so users who haven't opted into the Compose library design can still see and manage their novels in one place. The Light novels tab renders the same covers grid, category headers, and selection action bar as the Compose library's Novels tab (Move to category, Display options, Edit categories), and the Manga tab keeps the existing legacy library exactly as before. Tap a novel cover to open its details; long-press to enter multi-select.
- **Move novels to categories** from the Novels-tab library action bar. Long-press a novel cover to enter selection mode, select more covers, and tap **Move to category** in the contextual bar to open a sheet with tri-state checkboxes that mirror the manga side: checked = shared by every selected novel, indeterminate = shared by some, unchecked = none. Confirming commits the per-novel membership atomically (entries that landed in no user category fall into the synthetic Default). The category list honours the **Category sort order** preference (Off / A→Z / Z→A) and merge-group siblings move with their leader. The "+ New category" inline button on the sheet works for novels too.
- **Edit novel categories** alongside manga categories from **Settings → Library → Edit categories**, now a tabbed screen with **Manga** and **Novels** tabs. Each tab keeps the full editing surface: drag-and-drop reorder, inline rename, multi-select delete with an undo snackbar, ActionMode select-all / deselect-all, and a "Create new category" inline row. The Display options sheet's *Add or edit categories* entry opens the same host on whichever tab matches your active library.
- **Light novel plugin update detection.** The Browse-tab badge now counts pending light novel plugin updates alongside manga extension updates, so a single number tells you when anything in Browse needs updating. When updates are detected, a notification fires on its own channel (next to the manga extension channel in system notification settings). Browse → Extensions → Light novels shows an outlined **Update** button on outdated rows; tap to reinstall in place. The check runs in the background every 12 hours and also opportunistically on app launch (cache-gated to 6 hours so quick relaunches don't re-hit registries).
- Light novel sources and the LN plugin install list now show each plugin's real icon (fetched from the lnreader registry) instead of a generic book glyph. Plugins installed before this update show the icon after the next time you open the LN sources tab.
- The Novels library tab now has the **category hopper** — the floating quick-jump button the manga library already has: tap for a category picker, long-press up/down to jump to the first/last category or center for a configured action (search / expand-collapse all / display options / group by / random series), and drag to reposition it. Configure its visibility and long-press action under the Novels Display options → Categories tab; it's independent from the manga hopper.
- Compose library now supports **multi-select action mode**. Long-press any cover to enter selection mode; tap more covers to add or remove. The contextual bar offers Move to categories, Mark as read / unread (with confirmation + undo), Download unread, Share, Remove (with the same checkbox dialog as the legacy library and an undo snackbar), Migrate (hidden when every selection is local-source), Merge, and a brand-new **Unmerge selected** action that splits the selected manga back out of their merge group. Library data refreshes automatically after each action via the Compose pipeline. The new Unmerge action is also added to the legacy library multi-select on parity.
- Compose library (Settings → Advanced opt-in, dev/debug builds only) now displays real manga covers grouped by category instead of placeholder cells.
- The Compose library's manga details screen has a **tabbed chapter filter / sort / display** sheet (Filter / Sort / Display, matching Komikku), opened from the filter icon in the chapter-count header. Filter by downloaded, unread, or bookmarked (each cycles show-all / show-only / hide); sort by source order, chapter number, or upload date (tap the active option to flip direction); choose whether rows show the source title or "Chapter N". On series with multiple scanlator groups, a **Scanlator** filter hides specific groups. The ⋮ menu's "Set as default" / "Reset" carry your choices to every series. On tablets and unfolded foldables the sheet floats as a centered dialog.
- The Compose library's manga details screen can now **download chapters**. Each chapter row has a download button showing live state (queued, downloading with progress, downloaded, or error); tap a not-downloaded chapter to queue it, or tap an in-progress / downloaded one for a menu (**Start downloading now**, **Cancel**, **Delete**). A dedicated **download** icon in the toolbar offers Download next / next 5 / next 10 / unread / all, and below them **Remove downloads** in bulk (all / read / non-bookmarked).
- The Compose library's manga details screen now supports **chapter multi-select**. Long-press a chapter to start selecting, tap others to add or remove, and long-press a second chapter to select the whole range in between. The contextual bar offers Mark as read / unread, Bookmark / Remove bookmark, Download, Delete, Mark previous as read / unread, Select all, and Invert selection (plus **Open in browser / Share URL** when a single chapter is selected); system back clears the selection.
- The Compose manga details **chapter list** now shows each chapter's **upload date, scanlator, and "page X of Y" read progress** on a second line, a **"Missing N chapters"** marker between gaps (when sorted by chapter number), and **swipe gestures** , swipe a row to mark it read or to bookmark it (honoring the Reader "chapter swipe" setting).
- Tapping a manga's **cover** opens a **full-screen viewer**: pinch to zoom, **Save** the image to your Covers folder, or **Share** it. For library titles you can also **set a custom cover** from your photos or **reset** to the source's.
- The manga **description now renders Markdown** (bold, lists, tables, tappable links) and its text is **selectable**. Tapping the **title, author, artist, or a genre** searches for it globally; long-pressing **copies** it to the clipboard.
- **Edit info** on a manga gained a **status override**, **tag editing as add/remove chips**, and a one-tap **"Reset to source values"** that clears every override.
- More Compose manga-details parity with the legacy screen: the chapter **filter and sort "set as default" / "reset" now act independently**; the **filter icon highlights** while a filter is hiding chapters; **local (imported) titles hide the per-chapter download button**; **pull-to-refresh is skipped when offline**; **refreshing a tracker syncs its read progress** back onto your chapters; and the **favorite button's long-press** opens a menu to edit categories or remove the title (or all its merged sources) from your library.
- Compose library now supports **pull-to-refresh** and **per-category refresh** matching the legacy library. Pull down on the grid to refresh; with Show all categories on, every category refreshes; with it off, only the category you were last on refreshes (Compose keeps that in sync with the legacy `lastUsedCategory` preference, so toggling between libraries respects each other). Each category header gets a trailing refresh icon when more than one category is shown; tapping it queues that category and shows the same "Updating ___" / "Adding ___ to update queue" / "___ is already in queue" snackbar wording the legacy library uses. Snackbar Cancel stops the update. The refresh icon now appears on **Default** and on **dynamic** category headers (Source / Language / Tag / Author / Status / Tracker status) too — tapping it updates just the manga in that bucket. The per-header spinner now lights up the moment you tap, instead of waiting for the worker to start.
- Compose library now renders **dynamic groupings** — set Group library by to *Source*, *Language*, *Tag*, *Author*, *Status*, or *Tracker status* and your manga groups by that property instead of by category. Each dynamic header gets a collapse chevron (tap to fold/unfold that bucket); the *Move collapsed to bottom* preference reflows the list so folded buckets sink under the open ones in real time. Per-bucket refresh and the new per-category sort picker (below) work on dynamic headers too.
- Compose library category headers now have a **per-category sort picker**. Tap the sort label under any header to open a sheet with all sort modes (Title, Last Read, Date Added, Unread, Total Chapters, Latest Chapter, Date Fetched, Drag & Drop, Random); tapping the active mode flips ascending/descending, with the direction arrow on the header updating immediately. Mirrors the legacy library's per-header sort behavior. The Default category and dynamic categories share the library-wide sort, matching legacy parity.
- New **Category sort order** setting in the Display options sheet (Categories tab) and in Settings → Library. Three options: *Off* (manual drag-and-drop order, the existing behavior), *A→Z*, *Z→A*. Applies to the library list, the legacy hopper's jump-to-category sheet, and the **Move to / Add to categories** bottom sheet shown from manga details, library multi-select, the "Always ask" add path, and bulk-add from the related-mangas browse view — so the order you pick is the order you see everywhere you pick categories. The Default category stays pinned at the top of the library list regardless.
- New **Auto-merge same titles** toggle in the Display options sheet. When on (the default), library entries with identical titles across multiple sources collapse into a single card automatically — saves having to manually merge duplicates after switching extensions. Turn it off to keep each source-bound copy as its own row. Manual merges and unmerges from the action bar always win regardless of the toggle.
- Compose library covers in a merge group now show a **source-count chip** on the cover's top-right (matching the existing chapter / download chips on the top-left): the number tells you how many source-bound entries the rendered card stands in for. Hidden for standalone manga. Matching legacy parity for what was previously a Compose-only blind spot.
- Compose library grid and list views now animate item moves smoothly: merging two cards fades the sibling out and slides surviving cards into the gap; unmerging fades them back in; a per-category sort change reorders cards with translation; collapsing a category folds its items vertically instead of popping them out. Same data-pipeline cost, just a smoother visual transition so reshapes don't feel laggy.
- Cover chips (unread count, download count, language flag, source count, "Local" tag) are a notch larger and more readable across all Compose grid and list layouts. The dot-mode unread indicator scales proportionally so the dot variant stays in visual balance with the bigger pills.
- Compose library now has a unified **Display options** bottom sheet with Filter, Display, Badges, and Categories tabs, plus a filter icon and overflow menu in the toolbar. The Filter tab uses single-select chips for each preference (All / Include / Exclude or per-type), supports drag-to-reorder, and conditionally shows the Series type and Tracker rows. The Display / Badges / Categories tabs match the legacy layout / badge / category preferences. The overflow menu mirrors the legacy app-wide overflow (incognito toggle, Settings, Stats, About, Help). Filter selections share the same preference keys as the legacy library, so toggling the Compose library on or off preserves filter state. **Uniform grid covers** and **Use staggered grid** now apply live: turning the staggered switch on (with uniform off) re-lays the grid with variable cover heights based on each image's intrinsic ratio. The unread badge honors all three modes (hide / count chip / dot), and the download / unread badges no longer require the language flag to be on. List-layout rows now show the same badge chips as the grid. Category headers in the grid now show the manga count when "Show number of items" is on and can be tapped to collapse / expand under default grouping, with a chevron indicating state. The category hopper now supports long-press gestures: up = scroll to top, down = scroll to bottom, center = the configured Category hopper long-press action (search / expand-collapse all / display options / group by / open a random series). Cover thumbnails for manga with unread chapters show a continue-reading shortcut overlay, gated on the "Hide start reading button" preference and hidden on the cover-only layout. Tapping a manga cover or list row now opens the details screen (same fade transition as the legacy library). Local-source manga show a tertiary-coloured "Local" chip on the cover. Library search now matches series type, so typing "manhwa" / "manhua" / "comic" finds entries of that type even when no genre tag literally says so. An empty library renders a broken-heart placeholder with a "Getting started guide" link; if a filter or search narrows the library to nothing, the placeholder reads "No matches for filters" with no link. The "Show all categories" preference now keeps every category header visible even when search or filter narrows the library to nothing in that category; turning it off (with more than one category) renders only the active category at a time, with the hopper and active-category chip switching between them. The Display sheet now visibly disables the "Use staggered grid" row while "Uniform grid covers" is on. Filter sheet read-progress chips ("Not started" / "In progress") now match the legacy library's mapping.
- Light-novel source catalogs can now be **filtered**. A filter button in the browse toolbar opens the source's own genre, category, and sort options (plus a "Show latest releases" toggle), so sources whose listing needs a selection return results instead of coming back empty.
- Light-novel sources that have **settings** (a login, a base URL, content toggles) can now be configured: a gear button in the browse toolbar opens the source's settings form, and your saved values persist for the source to use.
- A new reader option, **Settings → Reader → Reading → "Mark chapter read when skipping ahead"** (off by default), marks the chapter you were on as read when you jump to the next chapter, so skipping forward no longer leaves unread chapters behind.

### Changes
- **Browse no longer tints in-library covers with a green overlay.** Results already in your library are now marked with just the "In library" badge (no full-cover wash or dim), in both the manga and light novel browse.
- The manga details chapter download menu gained a **Download next 10** option (alongside the existing next / next 5 / unread / all).
- The manga details related-mangas carousel's **"See all" is now a persistent button in the "Related" header row** (right side) instead of a trailing card that only appeared when there were more than 30 results, so it no longer pops in and out. The description's **More / Less toggle moved to the left**, keeping its fade-into-text affordance.
- **Clean up downloaded chapters** (Settings → Advanced) now runs as a background job, so it keeps going if you leave the screen and reports the number of folders cleared with a notification when it finishes instead of a toast.
- The library Display options sheet is now **tab-aware**. Filter chips, the Categories tab toggles, and the Edit/Add categories button each operate on whichever library is active (manga or novels), so collapsing categories or applying a filter on the Novels tab no longer reaches into the manga library. A new **Settings → Advanced → Share library display settings between manga and novels** toggle (on by default, status-quo behaviour) controls whether the display settings — grid size, badges, layout, show all categories, grouping, category sort order, "move dynamic to bottom", auto-merge same title, and the category hopper — stay in sync across both libraries or each tab keeps its own. Settings tied to a specific library's category set (which categories are collapsed, the last-used category) always stay independent. Manga-only toggles (*Always show current category*, *Series type* / *Content type* filter chips) are hidden on the Novels tab in both modes. The novel library's render path now reads each visual preference from its own store when independent mode is selected. A one-time migration seeds the novel keys from the existing manga values on first launch so flipping to independent mode starts from the user's existing look.
- **Compose library now uses a compact, pinned top bar.** The Compose library (and the Light novels tab hosted inside the legacy library) shows a single app-bar row: a tappable **library-name label** on the left (reads "Manga Library" / "Light Novels Library" with a dropdown caret; tap to switch between the two libraries) and search, filter, and overflow icons on the right. Tapping search turns the row into a back-arrow + text field; the bar stays put when you scroll (no more collapsing headline). The scrollable category tab row still appears below the bar when "show all categories" is off. The filter / overflow icons use the Material Tune and More-vert glyphs, matching the other Compose surfaces.
- Settings → Appearance → **Use large toolbar** toggle is removed. The unified library bar is always large, and the other Compose surfaces (Settings, About, LN screens, in-app WebView) keep their existing small bar.
- Compose Settings, novel surfaces, and the in-app WebView still share the single small top bar component for consistency across leaf screens.
- Browse → Extensions sheet now uses three peer tabs (Manga / Light novels / Migration) instead of the previous nested Manga / Light novels sub-tabs inside Extensions. The toolbar title and search bar now follow the active tab correctly (the search filters Manga, Light novels, or Migration). Light novel rows now match the manga extension row style, group into a separate Installed section above the language buckets, react to bottom-sheet drag the same way manga does, surface install failures inline with a retry-on-tap, and add a tap-to-open quick-actions menu on installed rows (Open site, Clear data, Uninstall). Uninstalling a Light novel plugin now also clears its plugin storage so reinstalling starts from a clean state.
- Browse → Light novel sources now groups sources by **Last used** and **Language** (matches the Manga sources layout), the rows match the manga source row style, and the Browse toolbar search bar filters the visible LN sources by name.
- Renamed the fork to **Reikai**. The Yōkai-Y2K placeholder name is retired — installs upgrade in place (same package ID), the launcher shows the new R-monogram icon and "Reikai" as the app label. The `.y2k` package suffix stays under the hood.
- Settings → Appearance rebuilt on the new Compose pattern. Long-press the row to reach the legacy version. App theme picker, app icon (Beta), follow-system theme switch, pure-black dark mode, expanded toolbar, manga-details cover tinting, hide-bottom-nav-on-scroll, and the side navigation controls are all back. The theme picker is the same horizontal tile rail as the legacy widget (mini-preview swatches with toolbar + content + bottom-nav dots in each theme's actual colors). Theme, night mode, pure-black, expanded toolbar, and side-nav-mode changes apply instantly via activity restart, matching the legacy behavior. Switching themes now cross-fades into the rebuilt UI instead of cutting to a blank frame, and tapping a tile that wouldn't change anything visible (e.g. picking a dark variant while the app is rendering light) no longer triggers a recreate at all.
- Settings → Security rebuilt on the new Compose pattern. Long-press the row to reach the legacy version
- Settings → Advanced rebuilt on the new Compose pattern. Long-press the row to reach the legacy version. Cleanup downloaded chapters, revoke trust for all extensions, the Reader options (hardware bitmap threshold, color profile picker, reader debug mode), and the external local-source toggle are all back; debug builds keep the "Crash the app!" and "Prune finished workers" actions
- Settings → Downloads rebuilt on the new Compose pattern. Long-press the row to reach the legacy version. All four sections (Wi-Fi / CBZ / split tall / download-with-id; remove after read with the per-category exclude list; download new chapters with the include/exclude category dialog; download-ahead and automatic-removal lists) are back, and the category lists now update live without re-entering the screen
- Settings → Tracking rebuilt on the new Compose pattern. Long-press the row to reach the legacy version. All six trackers (MyAnimeList, AniList, Kitsu, MangaUpdates, Shikimori, Bangumi) plus the auto-detected enhanced services (Komga / Kavita / Suwayomi when their sources are installed) are back. Browser-OAuth services open the in-app tab; credentials services (Kitsu, MangaUpdates) get a Compose login dialog with a password-visibility toggle. Tracker rows now refresh automatically after returning from OAuth instead of waiting for the resume hook
- Light-novel sources now load **once per app launch** and stay loaded across screens, instead of reloading every time you open LN browse, a source, or a novel's details. Those screens open faster, and a memory leak from the old per-screen loading is gone.

### Fixes
- The light-novel reader no longer **crashes when a chapter has repeated paragraphs** (blank lines, scene breaks, recurring phrases). Paragraphs were keyed by their text, so identical ones collided; they're now keyed by position.
- Adding a light novel to the library no longer **creates a duplicate entry**. Adding the same novel from a source (e.g. after browsing and searching for it again) could insert a second copy that lived on independently; adds now resolve to the existing entry and just flag it as a favorite.
- The legacy manga details top bar no longer **turns solid (its scrolled-down color) after a pull-to-refresh** while the page is still at the top. The refresh briefly reported the list as scrolled while it relaid out, which flipped the bar to its colored state and left it stuck there; the bar color is now re-checked once the list settles.
- The legacy manga details page no longer **gets the "Library" top bar painted over it** (wrong title, expanded bar, mismatched color), most visibly after switching sources on a grouped manga. The library, which stays loaded behind the details page, was restyling the shared top bar while off-screen; it now only does so when it is actually the visible screen.
- The related-mangas carousel and its "See all" grid no longer show the **same title twice** when two sources spell it with different punctuation (e.g. a straight vs curly apostrophe). Title de-duplication now ignores punctuation and symbols, which also tightens library suppression and the cross-source agreement count.
- The Compose library's download badge now shows the real downloaded-chapter count on each cover and updates live as downloads finish or are deleted. Previously the badge was wired up but never populated, so it stayed hidden even with the badge enabled and chapters downloaded.
- Novels added to the library now show their **chapter/unread count badge**. Adding a novel previously only flagged it as a favorite without saving its chapter list, so the badge had nothing to count and stayed empty; the parsed chapters are now persisted when you add a novel and refreshed whenever you open its details, so the count appears right away. The novel library also gets a **background auto-update** (every 24 hours by default, configurable under the Novels library settings), matching the manga library, so novels already in your library fill in and stay current even if you never open them; a pull-to-refresh on the Novels tab updates them immediately.
- Duplicate category detection now ignores leading and trailing whitespace, so naming a category "foo" and "foo " no longer creates two indistinguishable entries. Existing rows with stored whitespace still trigger the duplicate prompt when you try to add a matching name; renaming any such row normalises it.
- The library no longer shows the "Library is empty, add from Browse" placeholder when every category is collapsed. Headers stay visible with empty bodies; tap a chevron to expand. The placeholder still fires for a genuinely empty library and the "No matches for filters" message still fires when a search or filter narrows everything out.
- Switching between the **Manga** and **Light Novels** libraries now keeps your **scroll position** (and the category page you were on) for each library, instead of jumping back to the top every time you switch.
- The library **Display options** sheet no longer occasionally **gets stuck while swiping back** through its tabs (e.g. from Categories toward Filter), which previously forced you to close and reopen the sheet.
- Light novel Browse tint flip is now density-correct. The hysteresis threshold that suppresses microscroll flicker near the top of the list used a fixed pixel value; on high-density screens it filtered too little (jitter showed through) and on low-density it filtered too much (tint flip felt delayed). The threshold now scales with density.
- Tapping a source in Browse → Light novel sources now opens that source's catalog directly, instead of dropping you on a separate "pick a source" list first. Back from the catalog returns to the Light novel sources list.
- The Manga / Light novels tab row in Browse no longer disappears when quickly switching to another bottom-nav tab and back. Previously the only way to bring it back was to force-kill and reopen the app.
- Browse and Recents now keep their pill tab rows when switching directly between the two bottom-nav tabs (including a cold-start landing followed by a tap to the other). Previously the outgoing tab's hide animation overrode the incoming tab's freshly-installed tabs, so Recents → Browse left Browse without its Manga / Light novels pills until a round-trip through Library. As a side effect on Browse, the search bar no longer slides partway behind the status bar on scroll (the missing tab row was making the app bar overshoot its visible height by ~48dp).
- Compose library merge action no longer silently no-ops when you tap *Merge* in the action bar. The dispatch was racing against the auto-clearing of the selection — by the time the merge coroutine read the selected manga, the selection had already been wiped, so the merge ran with an empty set and nothing happened.
- Compose library Delete and Move-to-categories on a merge-group leader now act on every member of the group in one tap, instead of just the leader (which left siblings orphaned and re-promoted to leaders on the next library refresh, forcing repeat taps). Default-grouping behaviour now matches the legacy library exactly.
- Refreshing a dynamic category header (Source / Language / Tag / Author / Status / Tracker status) from idle now correctly spins that header's refresh indicator. The library updater was silently dropping synthetic category ids from its in-queue set whenever it started fresh from no running job — the per-header spinner only worked when you tapped refresh during an already-running update. Affects both the Compose and legacy library.
- Opening the search bar in Settings no longer crashes the app. The experimental "Change App Icon" dropdown was sharing a SharedPreferences key with the Compose-library toggle, causing a `ClassCastException` when search enumerated every settings screen at once
- Repeatedly switching app themes no longer leaks the previous activity. Every legacy controller that uses the shared scroll-with-toolbar helper (Recents, Library, Browse, History, and the manga details page) accumulated a Conductor lifecycle listener on each theme switch that held onto the destroyed activity's RecyclerView. The listeners now unregister themselves when the controller's view is torn down
- Library no longer pins the previous activity in memory after a theme switch or after toggling the new library design. LibraryPresenter's static cache for fast re-render was being kept across every `Activity.recreate()`, but its values hold a Context reference, so the cache survived as a 3.8 MB leak whenever no fresh LibraryPresenter ran to drain it. The cache is now only saved on real configuration changes (rotation, locale, follow-system dark-mode flip) where the next onCreate is guaranteed to read it.
- Rapidly switching themes no longer accumulates retained activities through three other legacy code paths. LibraryController held a strong reference to the previous activity's "show all categories" search-toolbar icon, the library fast-scroller's running scrollbar animator pinned itself to the main thread's AnimationHandler (and through it the FlexibleAdapter holding LibraryItem entries with Context refs), and `MangaShortcutManager.updateShortcuts` launched a coroutine that captured an Activity Context in its closure for the duration of image-loading + system-service calls. All three now release on view destroy or use the unbound Application context.
- The library's per-row item objects (`LibraryItem`) now hold the unbound Application context instead of the Activity context. They were being emitted into a SharedFlow whose suspended `combine` operator outlives any single activity, so every theme switch was pinning the previous MainActivity through the cached library state — and through it, the entire prior view tree (CoordinatorLayout, FastScroller, adapter, items) — for the entire app lifetime. Resource and string lookups inside `bindViewHolder` work fine with Application context.
- More light-novel sources now work. A batch of plugin-compatibility fixes means sources that previously returned empty results or errored now load and read correctly, including sources that use protobuf/gRPC APIs and Cloudflare-protected sites, which now fall through to a configured Flaresolverr server (Settings → Advanced) the same way the manga side does.
- Backing out of a light-novel's details page in Browse now returns to that source's catalog instead of jumping all the way out to the source list.
- Cloudflare-protected sources that fetch via a POST request now resolve through Flaresolverr too; the bypass fallback previously only replayed GET requests, so those sources failed even with a Flaresolverr server set. Applies to both the manga and light-novel sides.
- The reader's **next/previous-chapter buttons now work on long-strip (webtoon) chapters**: jumping chapters advances on the first tap and updates the chapter title and progress correctly, instead of stalling or needing repeated taps.

### Changes
- Extensions no longer tied to a repository are now labeled **Orphaned** (previously "Obsolete"), with a clearer note that they won't receive updates.

### Other
- Synced upstream changes from Mihon: library updates (Coil, OkHttp, Firebase), a SQLite driver build that avoids a rare database stall when a write is cancelled, and tying background tasks (downloads, library updates, source loading) to the app's lifecycle so they no longer outlive it.
- Extension repositories that moved to the newer index format are now followed automatically.

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
