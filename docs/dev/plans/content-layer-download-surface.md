# Content layer: the download surface (Road B)

> **Standing rules for every session working this surface.** The program rules bind here and are not restated: they live in [.claude/rules/content-layer.md](../../../.claude/rules/content-layer.md), which loads every session. For the program context read [content-layer-architecture.md](content-layer-architecture.md) first. The queue this replaces is recorded in [download-queue-unification.md](download-queue-unification.md).

## Goal

One download queue for manga and light novels: a single list in one saved order, a per-series sheet for chapter-level control, and the same pause, resume, progress and failure handling on both content types. This is the last of the six surfaces the content-layer program began with.

## Why

The queue screen stacks two separately owned lists. In the All view a manga card cannot be dragged past a novel card, and nothing on screen says why, because the shared card list refuses any move across content types. Behind it the two sides have drifted: the novel notification offers Cancel only and vanishes on pause, the novel reader ignores the global Downloaded only switch, a restored novel queue restarts on launch while a manga one waits, and each queue view model keeps its own copy of the card rules. A card's "downloaded" count is a running maximum the screen observes, so it restarts whenever the screen is reopened and would count a cancelled chapter as downloaded once chapters can be cancelled one at a time.

## Approach

**The download engines stay separate and keep running side by side.** Mihon's `Downloader` downloads up to the parallel-source limit at once and the novel downloader drains one chapter at a time; both keep doing so. What becomes shared is the orchestration above them: the queue list, its order, its cards, the per-series sheet, the actions and the rules behind them.

**One list, one order, parallel where it can.** The list is a priority order, and each engine works through its own share of it in that order. This is the rule Mihon's queue already applies between sources (a lower chapter from an idle source starts before a higher one from a busy source), extended to novels as one more lane. So moving a card up means it is next in its lane, and nothing slows down. The content-type chips are removed; every card carries the Browse content-type badge while both types are queued.

**The engine.** A Reikai-owned queue engine over a manga adapter and a novel adapter replaces both queue view models. It saves one combined order (card keys, pruned as series leave the queue) and hands each downloader its share through that downloader's existing reorder call, so Mihon's `Downloader` needs no scheduling patch. A single view model exposes the engine's state with `stateIn(WhileSubscribed)` and the screen collects it with lifecycle awareness, which is what mihonapp/mihon#3727 (mihon `c68efd0e8`) does upstream.

**Progress comes from the downloaders, not from the screen.** Each downloader keeps a per-series count of chapters it completed while that series stayed queued, dropped when the series leaves the queue. A card's total is what remains plus what completed, so the numbers survive the screen closing and a cancelled chapter shrinks the total instead of counting as downloaded. That makes the view model a pure derivation, which is what lets it take 3727's shape.

**The card** shows the chapter currently downloading ("Downloading ch. 142"). Novels latch that across the pacing gap between chapters, as the card's status already does, showing the next queued chapter while no chapter is marked downloading.

**The per-series sheet.** Tapping a card opens that series' queued chapters in download order in Mihon's `AdaptiveSheet`, the component the in-reader chapter list uses: a bottom sheet on a phone, a centred dialog under the tablet layout. Each row shows the chapter name, its status, a page-progress bar and page count for manga, and the failure reason when it failed. Row actions are Download next, which also retries a failed chapter, Move to bottom, behind the rest of its series, and Cancel. The header opens the series details. Chapters inside the sheet are not dragged: Sort and Download next already cover chapter order.

**Failure reasons.** Each downloader keeps the error message on the failed chapter. Failed downloads live only in memory on both sides, so the reason lives exactly as long as the failed row and nothing is persisted.

**Notifications.** The novel notification has manga's shape: Pause and Show entry while downloading, and a paused notification with Resume and Cancel all. The paused entry has its own id on both types, because WorkManager takes the worker's foreground notification down when a paused worker stops.

**Resuming.** Both types follow Mihon when the app is killed: a worker the system was running is rescheduled by WorkManager, and any other queue waits for Resume. Both wait out a missing connection, including a manga queue started offline, which upstream gives up on.

**The download index.** The two download folders stay separate (`downloads` and `novel_downloads`). The novel cache has what the manga cache has: an index saved between launches with the time it was last scanned, the first-scan indexing banner, and Reindex downloads in Settings and a backup restore rebuilding it. `DownloadIndexRules`, which both caches call, holds the rescan interval and the half-written-file rule. Deleting a novel's last downloaded chapter removes its folder, and its source's folder once empty, as manga does.

**Downloaded only.** Both readers page over `downloadedOrCurrent`, the downloaded chapters plus the one being read, while download-ahead walks the unfiltered list. The novel details list and filter sheet follow the switch through `appliedDownloadedFilter`, as manga's do through `Manga.downloadedFilter`, without saving it over the novel's own filter.

**Pacing.** Settings, Downloads, Pacing sets the shortest wait between two chapters from one novel source, globally and per source, with `NovelDownloadPacing`'s back-off on top. Novels only: manga extensions rate-limit their own clients through `RateLimitInterceptor`, which LN plugins have no equivalent of.

## Key files

- `reikai/presentation/download/`: the engine, its two adapters, the view model, `EntryDownloadCardList.kt` and the per-series sheet.
- `eu/kanade/tachiyomi/ui/download/DownloadQueueScreen.kt`: the host screen.
- `eu/kanade/tachiyomi/data/download/Downloader.kt`, `model/Download.kt`: `// RK` islands for the completed count and the failure reason.
- `reikai/novel/download/NovelDownloadManager.kt`, `NovelDownload.kt`, `NovelDownloadNotifier.kt`, `NovelDownloadCache.kt`: the novel downloader, its notification and its index.
- `reikai/domain/download/SeriesCompletions.kt`, `DownloadIndexRules.kt`: the completed counts and the index rules both downloaders share.
- `reikai/novel/download/NovelDownloadPacing.kt`, `eu/kanade/presentation/more/settings/screen/novel/NovelSourceDelaysScreen.kt`: pacing and its per-source screen.
- `reikai/domain/reader/DuplicateChapters.kt` (`downloadedOrCurrent`), `reikai/domain/novel/model/NovelChapterFlags.kt` (`appliedDownloadedFilter`): Downloaded only.
- `reikai/presentation/components/ContentTypeBadge.kt`: the type badge, shared with Browse.

## Status

Complete, closing inventory included. The queue engine, list and screen (`c62b43ef7`); the per-series sheet and failure reasons (`44514dd71`); Mihon's per-chapter queue deleted and manifested (`6fb6f87d6`); the novel notification's Pause and Resume (`b6c09e28a`) and the manga paused notification's same race (`021a30623`); the novel download index (`18f68220c`); Downloaded only for novels, reader and details (`2273cd3f8`); resuming the same way for both types and Show entry (`e25102054`); pacing (`acc0be116`); the manga page count in the sheet (`c25da8386`) and a chapter's Move to bottom (`7d131a14b`), both found by the inventory.

Both items that were verified from code alone are now shown on device (Fold, 2026-09-19): a restored novel queue waits at Queued behind Resume with no worker running, and a chapter whose address points at a missing page fails after four attempts and shows the plugin's own words in the series sheet. Forcing that failure needs a plugin that throws on a missing page: Novel Fire does ("Could not reach site (404)"), Novel Arrow answers anything with a page, which is why the earlier attempt could not reach the path. A card's counts survive reopening the queue but not the app being killed, since the downloaders keep them in memory.

**Behaviour inventory** of the replaced code: Mihon's `DownloadQueueScreen`, `DownloadQueueViewModel`, `DownloadHolder` and `download_single` menu, and Reikai's previous queue models, card list, novel notifier and novel cache.

- Present: pause and resume, cancel all, the pending-chapter count, the empty screen, drag reorder, series to top and to bottom, a chapter to the bottom (of its series, where upstream's was of its source), cancel a chapter, cancel a series, sort by upload date or chapter number in either direction (within each series, where upstream sorted within each source), per-chapter status and page progress with its count, the novel card's latched status and the source-name fallback, the transient-empty guard on a manga reorder, the notification's cancel and error entries, the index's storage-move rescan and per-chapter edits.
- Deliberately dropped: per-chapter drag and the source header rows (series cards, owner 2026-09-19); the content-type chips (owner 2026-09-19); the novel queue's launch auto-start (owner 2026-09-19).
- Missing: nothing. The two gaps the walk found, the page count and a chapter's Move to bottom, are built.

## Decisions & tradeoffs

- **Keep two download folders (owner, 2026-09-19).** Merging them moves every novel file and risks a novel plugin folder colliding with a manga source folder, for nothing a user would see.
- **One list, no chips, parallel lanes (owner, 2026-09-19).** A shared slot budget would make the cross-type order strict at the cost of manga and novels no longer downloading side by side; tabs or sections keep the barrier. Tsundoku, which downloads both through one engine, still orders each type separately and shows them as tabs.
- **The badge shows only while both types are queued (owner, 2026-09-19)**, the rule the badge already follows in Browse.
- **Per-chapter control is a per-series sheet, built in full the first time (owner, 2026-09-19)**, including failure reasons. An expanding card was rejected: a novel with thousands of queued chapters would nest thousands of rows inside a draggable list.
- **Mihon's per-chapter queue cluster is deleted (owner, 2026-09-19).** It was kept alive as the revive path for per-chapter control, which the sheet now provides; `refs/mihon` keeps the files as the diff base.
- **Progress counts move into the downloaders.** The observed running maximum could not survive the screen closing and would have counted cancelled chapters as downloaded.
- **Resume as Mihon does, wait out a lost connection on both (owner, 2026-09-19).**
- **Pacing is two settings, novels only (owner, 2026-09-19):** a global delay and per-source delays. Tsundoku's on/off switch, random extra delay and burst size were left out, since they pace every request in its HTTP client and ours paces chapters. The random extra wait only adds, so the set delay is a real minimum.
- **Shared rules are pinned by kernels, not a conformance test.** The index rules and Downloaded only each live in one function both types call, the rung the content-layer rules prefer.
