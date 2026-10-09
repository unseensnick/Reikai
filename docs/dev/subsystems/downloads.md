# Downloads

## Purpose

Downloads keep chapters on the device so they read offline, for manga and light novels alike. The user sees one download queue in one saved order, a per-series sheet for chapter-level control, and the same pause, resume, retry, progress and failure handling on both content types. Downloaded chapters survive a reinstall, a backup restore and a storage move.

## How it works

### Two engines, one queue above them

The download engines stay separate and run side by side. Mihon's `Downloader` (behind `DownloadManager`) fetches manga pages, several sources at once up to the parallel-source limit, one job per source sharing that source's page slots. `NovelDownloadManager` drains novel chapters one at a time: a chapter's text is one `NovelSource.parseChapter` request. What is shared is the orchestration above them: the queue list, its order, its cards, the per-series sheet, the queue actions and the rules behind them.

`DownloadQueueProvider` is the only seam the queue talks to an engine through, with `MangaDownloadQueueProvider` and `NovelDownloadQueueProvider` as the two adapters. Each publishes a `DownloadQueueSnapshot` (queued chapters in its own order, the active series, per-series completed counts, labels) and answers the verbs: reorder series, cancel a chapter or series, Download next, Move to bottom, sort, cancel all, pause and start.

`EntryDownloadQueueViewModel` is the single model behind `DownloadQueueScreen`. It combines the two snapshots, turns each into cards with `toCards`, and lays them out with `arrangeCards`: the saved order (`ReikaiSourcePreferences.downloadQueueOrder`, card keys) decides which positions belong to which content type, and each type fills its positions in its downloader's real order, so the list never shows an order a downloader is not following. Moving a card hands each downloader its share through that downloader's own reorder call, so Mihon's `Downloader` needs no scheduling patch. The list is a priority order: a card moved up is next in its own lane, and manga and novels keep downloading in parallel. The saved order is pruned of series that left the queue, but only for a type whose downloader has loaded its saved queue (`awaitQueueRestored`), so a cold start does not throw away the slots of cards still loading.

### Cards, the series sheet and status

One card per series. A card's total is what remains queued plus what that downloader completed for the series while it stayed queued (`SeriesCompletions`, owned by each downloader and cleared when the series leaves the queue), so the numbers survive the screen closing and a cancelled chapter shrinks the total rather than counting as downloaded. The counts are in memory, so they do not survive the app being killed. A card reads Error only when every chapter it still has queued failed. The current chapter is the one downloading, or for novels the next queued one across the pacing gap, latched by `downloadingNovelId`. The content-type badge (`ContentTypeBadge`, shared with Browse) shows only while both types are queued.

Tapping a card opens `EntryDownloadSeriesSheet` in Mihon's `AdaptiveSheet`: that series' queued chapters in download order, each with its status, page progress and page count for manga, and the failure reason. Row actions are Download next (which also retries a failed chapter), Move to bottom (behind the rest of its series) and Cancel. Chapters are not dragged inside the sheet.

Whether the queue is downloading, paused or stopped is one rule, `downloadQueueState` in `DownloadQueueKernel.kt`, which the More row and the queue screen's pause button both read: Downloading while any downloader with something queued is running, else Paused while anything is queued.

The card order's echo guard is `reconcileCardOrder`: a committed drag or chevron order is held until the queue echoes it, a stale emission over the same cards keeps it, and a change to both the cards and their order resyncs. The drag settles through the shared `rememberSettledReorder`.

### Queueing, retry and failure

Both managers drop a chapter already on disk on enqueue. Queueing a failed chapter again is its retry, from every entry point (a row's indicator, a bulk download, the details list): it goes back to Queued at once and a stopped downloader starts. Manga does this in an `// RK` island in `Downloader.queueChapters`, restarting a busy downloader (`pause` then `start`) so the chapter does not wait for the one in flight; the novel drain picks it up on its next turn.

Both downloaders retry a fetch on `DownloadRetry`'s schedule (three more tries, waiting 2, 4 and 8 seconds), a manga page image at a time and a novel chapter's text as a unit; the novel side never waits less than the source's declared minimum delay, and an empty chapter fails at once. Both check free space against `hasRoomToDownload` (Mihon's 200 MB floor) before fetching. A failure reason is kept on the failed chapter in memory only. The failed row stays in the saved queue, since only a finished download leaves it, so after a restart it is back as Queued without its reason and waits for Resume.

### Network, resume and notifications

Which network lets a queue fetch is one rule, `downloadNetworkIssue` (`DownloadNetworkGate.kt`), asked by Mihon's `DownloadWorker` and the novel drain. A missing network, or Wi-Fi only off Wi-Fi, pauses rather than fails: the chapter in flight is requeued, the worker stays in the foreground and polls, and fetching resumes when the network returns, including a manga queue started offline. Emptying a queue paused this way ends its downloader on both types.

Both types resume as Mihon does after the app is killed: a worker the system was running is rescheduled by WorkManager, and any other queue waits for Resume. The saved queues are `DownloadStore` (manga) and `NovelDownloadStore` (novels, `{novelId, chapterId, order}`), restored once at launch behind whatever was queued meanwhile; a restored row lands only if it is still saved, so a cancel or Cancel all during the restore keeps it out.

The novel notification has manga's shape: Pause and Show entry while downloading, and a paused notice with Resume and Cancel all on its own id (`ID_DOWNLOAD_CHAPTER_PAUSED`, `ID_NOVEL_DOWNLOADER_PAUSED`), because WorkManager takes the worker's foreground notification down when a paused worker stops. A network pause keeps its worker running, so its notice sits on the worker's own id and replaces a user pause's. Each worker's foreground notice is that paused notice when it starts off the network, and each hands the service every new waiting reason.

Error notices follow Mihon on "Hide notification content" and keep the title. Reikai's "Hide adult content in notifications" applies to both types through `downloadErrorTitle` (`ShownEntryName.kt`): an adult entry's error takes the generic downloader title and drops the chapter name. Adult is `AdultContentChecker`'s verdict, which judges the source first, so a plain series on a mixed-content extension counts.

### Pacing

Settings, Downloads, Pacing sets the shortest wait between two chapters from one novel source, globally and per source (`NovelSourceDelaysScreen`), never below the source's declared minimum. `NovelDownloadPacing` adapts above that floor: halving toward it after a success, doubling after a failure up to 30 seconds. The drain adds up to a quarter more at random, so the set delay stays a minimum. Novels only.

### Storage layout and names

The two download roots stay separate: `downloads` and `novel_downloads`. A manga chapter is a folder or `.cbz` under `<source name>/<title>/`. A novel chapter is one self-contained HTML file, `novel_downloads/<plugin id>/<title>/<chapter name>[_<url hash>].html` (`NovelDownloadProvider`), with inline images embedded (`inlineChapterImages`) so it reads offline. The title and chapter parts reuse the manga `DownloadProvider` (`getMangaDirName`, `getChapterDirName`), so both types name alike. The novel source segment is the plugin id because a plugin update can change its display name. The hash follows the shared chapter-name-hash setting, and a lookup tries every name the two file-name settings could have produced (`getChapterNameVariants`), so flipping one orphans nothing. Writes go to a temp name (`Downloader.TMP_DIR_SUFFIX`) and are renamed on success.

Without the hash, two chapters of one name share one file and the first saved keeps it, as Mihon's downloader keeps the first folder: `NovelChapterSaver` returns `NAME_TAKEN` and the download counts as done.

A chapter renamed by its source is renamed on disk during the chapter sync (`NovelDownloadManager.renameChapter`, as `DownloadManager.renameChapter`). A title change moves only the entry's own series folder on both types: `movesDownloadFolder` leaves a folder another entry on the source is named onto (from `SourceTitlesRepository`, library and browsed rows alike) and one whose new name is taken. `renameDownloadFolder` does the novel move, through a temporary name for a case-only change.

### The download index

Whether a chapter is downloaded is read from disk, never from a database flag (`novel_chapters.is_downloaded` is a dead column). Manga uses Mihon's `DownloadCache`; novels use `NovelDownloadCache`, a `source -> title -> file names` tree saved between launches (`novel_dl_index_v1.json`) with the time it was last scanned, a first-scan indexing banner, a storage-move rescan, and Reindex downloads in Settings and a backup restore rebuilding it. `DownloadIndexRules` holds the one-hour rescan interval and the half-written-file rule for both. A rescan holds the index lock across the scan, as `DownloadCache.renewCache` does, so an edit made mid-scan lands on the new tree. A merged list probes each copy under its own entry's folder: `reikai.domain.manga.downloadedChapterIds` and `reikai.domain.novel.downloadedChapterIds` over `NovelRepository.ownersOf`.

### Rows, Downloaded only, download-ahead and removal

Every Reikai row (both readers' chapter sheets, the novel details list, Recents) runs one kernel per engine, `MangaChapterDownloadActions` and `NovelDownloadManager.runChapterAction` (`ChapterDownloadActions.kt`), and reads its mark through `downloadStateOf`. Mihon's details list keeps `MangaViewModel.runChapterDownloadActions`. Lists drawn from queued manga downloads also listen to `queuedDownloadChanges`, because Mihon's queue flow does not emit on a status or progress change.

Downloaded only: both readers page over `downloadedOrCurrent` while download-ahead walks the unfiltered list, and the novel details list follows the switch through `novelChapterListFilters` without saving it over the novel's own filter. Download-ahead picks chapters through `chaptersToDownloadAhead` on both types and runs in incognito. Manga downloads ahead only when the current chapter was read from disk and the next is downloaded too (Mihon's `ReaderViewModel.downloadNextChapters`); novels have no such gate.

Automatic removal (delete after read, delete after marked read, the reader's trim behind it) filters through `removableDownloads`: a category kept from removal keeps its read chapters, and the bookmark switch applies. Manga goes through `DownloadManager.deleteRemovableChapters` and `enqueueChaptersToDelete`, novels through `NovelRemovableDownloads`; both hand over the chapters as written, read. The reader's trim queues novels in `NovelDownloadPendingDeleter`. A manual Delete takes the download whatever the category, keeping only a bookmarked chapter while "Allow deleting bookmarked chapters" is off (`deletableDownloads`). A novel delete is one pass per novel: one folder listing, one index edit, one queue write, the first matching file name deleted per chapter. Deleting a novel's last chapter removes its folder, and its source folder once empty.

## Key files

- `app/src/main/java/reikai/presentation/download/EntryDownloadQueueViewModel.kt`: the one queue model, `reorder`, the saved order and sheet state.
- `app/src/main/java/reikai/presentation/download/DownloadQueueKernel.kt`: `downloadQueueState`, `toCards`, `arrangeCards`, `reconcileCardOrder`, `inOrderOf`, `prunedOrder`.
- `app/src/main/java/reikai/presentation/download/DownloadQueueProvider.kt`: the seam; adapters in `app/src/main/java/reikai/presentation/download/MangaDownloadQueueProvider.kt` and `app/src/main/java/reikai/presentation/download/NovelDownloadQueueProvider.kt`.
- `app/src/main/java/reikai/presentation/download/EntryDownloadCardList.kt`, `app/src/main/java/reikai/presentation/download/EntryDownloadSeriesSheet.kt`, `app/src/main/java/reikai/presentation/download/DownloadQueueSortSheet.kt`: the cards, the per-series sheet and sort.
- `app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueScreen.kt`: the host screen.
- `app/src/main/java/eu/kanade/tachiyomi/data/download/Downloader.kt` (`fail`, `queueChapters`, `completions`) and `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadManager.kt` (`deleteRemovableChapters`, `removeQueuedManga`): Mihon's engine with `// RK` islands.
- `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadWorker.kt`, `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadNotifier.kt` (`onNetworkPause`), `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadCache.kt`: the manga worker, notices and index.
- `app/src/main/java/reikai/novel/download/NovelDownloadManager.kt`: `runQueue`, `downloadChapters`, `restoreJob`, `drainLock`.
- `app/src/main/java/reikai/novel/download/NovelDownloadProvider.kt`: paths and names.
- `app/src/main/java/reikai/novel/download/NovelDownloadCache.kt`: the novel index.
- `app/src/main/java/reikai/novel/download/NovelDownloadStore.kt`: the saved novel queue.
- `app/src/main/java/reikai/novel/download/NovelChapterSaver.kt`: `save`, `NAME_TAKEN`.
- `app/src/main/java/reikai/novel/download/NovelDownloadWorker.kt`, `app/src/main/java/reikai/novel/download/NovelDownloadNotifier.kt`: the novel worker and its notices.
- `app/src/main/java/reikai/novel/download/NovelDownloadPacing.kt`: `floorFor`, `next`.
- `app/src/main/java/reikai/domain/download/SeriesCompletions.kt`, `app/src/main/java/reikai/domain/download/DownloadIndexRules.kt`, `app/src/main/java/reikai/domain/download/DownloadRetry.kt`, `app/src/main/java/reikai/domain/download/DownloadSpace.kt`, `app/src/main/java/reikai/domain/download/DownloadNetworkGate.kt`: the rules both engines call.
- `app/src/main/java/reikai/domain/download/DownloadFolderRename.kt`: `movesDownloadFolder`, `renameDownloadFolder`.
- `app/src/main/java/reikai/domain/download/RemovalExclusion.kt` (`removableDownloads`, `deletableDownloads`) and `app/src/main/java/reikai/domain/download/NovelRemovableDownloads.kt`.
- `app/src/main/java/reikai/domain/download/ChapterDownloadActions.kt`, `app/src/main/java/reikai/domain/download/DownloadStateOf.kt`, `app/src/main/java/reikai/domain/download/QueuedDownloadChanges.kt`: a row's download control.
- `app/src/main/java/reikai/data/notification/ShownEntryName.kt`: `downloadErrorTitle`.
- `app/src/main/java/reikai/domain/reader/DuplicateChapters.kt` (`downloadedOrCurrent`), `app/src/main/java/reikai/domain/reader/ReaderChapterFilters.kt` (`novelChapterListFilters`), `app/src/main/java/reikai/domain/reader/ChapterNeighbours.kt` (`chaptersToDownloadAhead`).
- `app/src/main/java/eu/kanade/presentation/more/settings/screen/novel/NovelSourceDelaysScreen.kt`: per-source pacing.
- `app/src/main/java/mihon/core/migration/migrations/NovelDownloadRekeyMigration.kt`: moves files saved under the old numeric-id layout.

## Invariants and traps

- **A manga download's failure posts its notice before it sets the status.** Mihon's job cancels a download's coroutine the moment its status turns `ERROR`, and Reikai's notice suspends for the adult verdict, so `Downloader.fail` posts first. The other order left the downloader "running" with only failures queued and refused every retry until restart.
- **A reorder applies an order to the live queue, never a copy of it.** A sort reads the queue and the drain can finish a chapter meanwhile; replacing the queue with the copy downloaded it again and dropped a chapter queued since. Both reorder paths take only the order through `inOrderOf`.
- **Every write to the saved novel queue holds `storeLock` and follows the live queue's change**, and a rewrite waits for the launch restore. Otherwise a rewrite deletes saved rows the restore has not read yet, or saves a row that already left.
- **`SeriesCompletions` is never cleared on a reorder**, which empties the queue in passing.
- **One novel drain runs at a time.** `runQueue` holds `drainLock` for the whole drain, so a Resume during a paused drain's blocking save waits rather than racing it into duplicate files.
- **`DownloadNotifier.onProgressChange` takes a `Mutex`, not `@Synchronized`**: it suspends for the adult verdict, and a monitor does not hold across a suspension.
- **Every foreground worker shares one WorkManager service**, which reposts the first worker's last request when another goes foreground, so each worker must hand the service every new waiting reason or a stale "No network" returns.
- **A new automatic removal caller goes through the removal filter** (`deleteRemovableChapters` or `NovelRemovableDownloads`), never straight to `deleteChapters`, which no longer asks the excluded categories.
- **A stray second copy of a chapter** (written before a file-name setting flipped and after) can return after a rescan on both types, since a delete removes only the first name found while the index drops every name, as Mihon does.

## Decisions

- **Two download roots.** Merging them moves every novel file and risks a plugin folder colliding with a manga source folder, for nothing a user sees.
- **One list, no content-type chips, parallel lanes.** A shared slot budget would make cross-type order strict at the cost of manga and novels no longer downloading side by side; tabs would keep the barrier the list removes.
- **Per-chapter control is a per-series sheet, not an expanding card.** A novel with thousands of queued chapters would nest thousands of rows in a draggable list.
- **Progress counts live in the downloaders.** A count observed by the screen restarts when the screen reopens and counts a cancelled chapter as downloaded.
- **Pacing is novels only.** Manga extensions rate-limit their own clients through `RateLimitInterceptor`; LN plugins have no equivalent. Overlapping a novel source's next chapter with the current one (Mihon's shared page slots) is manga only for the same reason: it would defeat the pacing.
- **Download-ahead's on-disk gate is manga only.** A streamed manga chapter's page loads share the source with the download and would stutter; a novel chapter is one request.
- **A manual Delete ignores the excluded categories; the bookmark switch still applies.** The exclusion governs automatic removal only, so a Delete on a read chapter in an excluded category no longer does nothing.
- **"Downloaded" is a disk scan, not a flag.** A flag drifts from the disk on every reinstall or restore; the `is_downloaded` column is kept only to avoid a table rebuild.
- **A lost connection pauses, never errors.** The chapter in flight is requeued and fetched when the network returns.

## Upstream divergences

`// RK` islands sit in `Downloader` (completed counts, failure reason and order, the retry in `queueChapters`, the network pause in `stop`, the shared space floor), `DownloadManager` (manual versus automatic delete, emptied paused queue, `renameManga` folder guard, `awaitQueueRestored`), `DownloadWorker` (network wait, paused foreground notice), `DownloadNotifier` (adult verdict, `onNetworkPause`, `progressLock`), `DownloadCache` (persisted scan time, shared index rules), `DownloadProvider` (`getChapterNameVariants`) and `DownloadStore`. Recorded in [upstream-sync.md](../upstream-sync.md) "Deliberate divergences": the manual delete, the network wait, the emptied paused queue, `renameManga`, `DownloadCache`'s scan time and the notifier `Mutex`.

## Extending

- **A new queue verb**: add it to `DownloadQueueProvider`, answer it in both adapters, and drive it from `EntryDownloadQueueViewModel`.
- **A new row with a download control**: run `MangaChapterDownloadActions` or `runChapterAction`, read the mark through `downloadStateOf`, and pass the copies the row's view deletes.
- **A new automatic removal path**: filter through `removableDownloads` in the type's manager or `NovelRemovableDownloads`.
- **A rule both downloaders follow** (a limit, a retry, a name): put it in `reikai/domain/download/` and call it from both engines, or pin the two halves with a conformance test over the real managers.

## Tests

Queue: `EntryDownloadQueueViewModelTest`, `DownloadQueueKernelTest`, `DownloadQueueSortTest`, `NovelDownloadQueueProviderTest`, `SeriesCompletionsTest`, `SavedQueueRestoreOrderTest`. Conformance over both real engines: `ChapterDownloadActionsConformanceTest`, `NetworkWaitConformanceTest`, `PausedNoticeConformanceTest`, `EmptiedPausedQueueConformanceTest`, `ManualDeleteConformanceTest`, `MarkReadDeleteConformanceTest`, `TitleRenameFolderConformanceTest`, `RenameOnSyncConformanceTest`. Manga engine: `DownloaderSourceJobTest`, `DownloadWorkerTest`, `DownloadNotifierTest`. Novel engine: `NovelDownloadManagerDrainTest`, `NovelDownloadManagerEnqueueTest`, `NovelDownloadManagerFailureTest`, `NovelDownloadManagerStorageTest`, `NovelDownloadStoreTest`, `NovelDownloadProviderTest`, `NovelDownloadCacheRenewTest`, `NovelDownloadCacheRemoveTest`, `NovelChapterSaverTest`, `NovelDownloadPacingTest`, `NovelDownloadRekeyMigrationTest`. Kernels: `DownloadIndexRulesTest`, `DownloadRetryTest`, `DownloadSpaceTest`, `DownloadNetworkGateTest`, `DownloadFolderRenameTest`, `RemovalExclusionTest`, `RemovableDownloadsTest`, `DownloadStateOfTest`, `DownloadErrorTitleTest`, `NovelDownloadedOnlyTest`, `DownloadAheadTest`.

Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`.

## Related

- Merged series, which picks one copy per merged chapter to download: [merged-series.md](merged-series.md).
