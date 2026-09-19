# Content layer: the download surface (Road B)

> **Standing rules for every session working this surface.** The program rules bind here and are not restated: they live in [.claude/rules/content-layer.md](../../../.claude/rules/content-layer.md), which loads every session. For the program context read [content-layer-architecture.md](content-layer-architecture.md) first. The queue this replaces is recorded in [download-queue-unification.md](download-queue-unification.md).

## Goal

One download queue for manga and light novels: a single list in one saved order, a per-series sheet for chapter-level control, and the same pause, resume, progress and failure handling on both content types. This is the last surface of the content-layer program and the only gate on the 0.4.0 cut.

## Why

The queue screen stacks two separately owned lists. In the All view a manga card cannot be dragged past a novel card, and nothing on screen says why, because the shared card list refuses any move across content types. Behind it the two sides have drifted: the novel notification offers Cancel only and vanishes on pause, the novel reader ignores the global Downloaded only switch, a restored novel queue restarts on launch while a manga one waits, and each queue view model keeps its own copy of the card rules. A card's "downloaded" count is a running maximum the screen observes, so it restarts whenever the screen is reopened and would count a cancelled chapter as downloaded once chapters can be cancelled one at a time.

## Approach

**The download engines stay separate and keep running side by side.** Mihon's `Downloader` downloads up to the parallel-source limit at once and the novel downloader drains one chapter at a time; both keep doing so. What becomes shared is the orchestration above them: the queue list, its order, its cards, the per-series sheet, the actions and the rules behind them.

**One list, one order, parallel where it can.** The list is a priority order, and each engine works through its own share of it in that order. This is the rule Mihon's queue already applies between sources (a lower chapter from an idle source starts before a higher one from a busy source), extended to novels as one more lane. So moving a card up means it is next in its lane, and nothing slows down. The content-type chips are removed; every card carries the Browse content-type badge while both types are queued.

**The engine.** A Reikai-owned queue engine over a manga adapter and a novel adapter replaces both queue view models. It saves one combined order (card keys, pruned as series leave the queue) and hands each downloader its share through that downloader's existing reorder call, so Mihon's `Downloader` needs no scheduling patch. A single view model exposes the engine's state with `stateIn(WhileSubscribed)` and the screen collects it with lifecycle awareness, which is what mihonapp/mihon#3727 (mihon `c68efd0e8`) does upstream.

**Progress comes from the downloaders, not from the screen.** Each downloader keeps a per-series count of chapters it completed while that series stayed queued, dropped when the series leaves the queue. A card's total is what remains plus what completed, so the numbers survive the screen closing and a cancelled chapter shrinks the total instead of counting as downloaded. That makes the view model a pure derivation, which is what lets it take 3727's shape.

**The card** shows the chapter currently downloading ("Downloading ch. 142"). Novels latch that across the pacing gap between chapters, as the card's status already does, showing the next queued chapter while no chapter is marked downloading.

**The per-series sheet.** Tapping a card opens that series' queued chapters in download order in Mihon's `AdaptiveSheet`, the component the in-reader chapter list uses: a bottom sheet on a phone, a centred dialog under the tablet layout. Each row shows the chapter name, its status, a page-progress bar for manga, and the failure reason when it failed. Row actions are Cancel and Download next, which also retries a failed chapter. The header opens the series details. Chapters inside the sheet are not dragged: Sort and Download next already cover chapter order.

**Failure reasons.** Each downloader keeps the error message on the failed chapter. Failed downloads live only in memory on both sides, so the reason lives exactly as long as the failed row and nothing is persisted.

**Notifications.** The novel notification gains manga's shape: Pause while downloading, and a paused notification with Resume and Cancel all.

**The download index.** The two download folders stay separate (`downloads` and `novel_downloads`). The novel cache gains what the manga cache has: an index saved to disk, an initializing signal and remove-source. One conformance test run against both caches pins the rules they share (both non-ASCII filename variants accepted, `_tmp` folders skipped, the one-hour rescan limit).

**Downloaded only in the reader.** The global switch moves into the shared `navigableChapters` kernel, which both readers already call, so the novel reader honours it and the rule exists once.

**Pacing settings.** Scoped against tsundoku's `NovelDownloadPreferences` (a request delay, jitter, burst size and a per-source override map, applied in its HTTP client) and proposed to the owner before any setting is built.

## Key files

- `reikai/presentation/download/`: the engine, its two adapters, the view model, `EntryDownloadCardList.kt` and the per-series sheet.
- `eu/kanade/tachiyomi/ui/download/DownloadQueueScreen.kt`: the host screen.
- `eu/kanade/tachiyomi/data/download/Downloader.kt`, `model/Download.kt`: `// RK` islands for the completed count and the failure reason.
- `reikai/novel/download/NovelDownloadManager.kt`, `NovelDownload.kt`, `NovelDownloadNotifier.kt`, `NovelDownloadCache.kt`: the novel downloader, its notification and its index.
- `reikai/domain/reader/DuplicateChapters.kt`: `navigableChapters`, which gains the Downloaded only rule.
- `reikai/presentation/browse/components/ContentTypeBadge.kt`: the badge, moving to shared components.

## Status

In progress. Steps 1 and 2 landed together, since the engine has no screen to verify on without the new list. Sequence, each step its own commit and each gated on its check:

1. The engine, the adapters, the downloader-side completed counts and the single view model. Check: one test over both adapters for card building, combined-order save and split, cancel and the current chapter, each rule mutated to red once.
2. The screen: chips and their setting removed, one list, the badge, drag and the chevrons across the whole list, the current-chapter line. Check: on the emulator with one manga and one novel queued, a novel dragged above a manga keeps its place across a restart and each engine downloads in the new order.
3. The per-series sheet and failure reasons. Check: engine tests for rows and actions; on device, cancel one chapter, Download next, and a forced failure showing its reason, for both types, plus the sheet centred under Tablet UI.
4. Mihon's per-chapter queue cluster deleted and manifested. Check: the manifest hook and the build.
5. Pause and Resume on the novel notification. Check: on device from the notification shade.
6. The novel download index and the cache conformance test. Check: the test, and a cold start that shows novel downloads without a full rescan.
7. Downloaded only in the novel reader. Check: a failing test first, then on device.
8. Pacing settings, after an owner-approved proposal.
9. The behaviour inventory over everything replaced (both queue view models, the novel notifier, the novel cache), then the docs.

## Decisions & tradeoffs

- **Keep two download folders (owner, 2026-09-19).** Merging them moves every novel file and risks a novel plugin folder colliding with a manga source folder, for nothing a user would see.
- **One list, no chips, parallel lanes (owner, 2026-09-19).** A shared slot budget would make the cross-type order strict at the cost of manga and novels no longer downloading side by side; tabs or sections keep the barrier. Tsundoku, which downloads both through one engine, still orders each type separately and shows them as tabs.
- **The badge shows only while both types are queued (owner, 2026-09-19)**, the rule the badge already follows in Browse.
- **Per-chapter control is a per-series sheet, built in full the first time (owner, 2026-09-19)**, including failure reasons. An expanding card was rejected: a novel with thousands of queued chapters would nest thousands of rows inside a draggable list.
- **Mihon's per-chapter queue cluster is deleted (owner, 2026-09-19).** It was kept alive as the revive path for per-chapter control, which the sheet now provides; `refs/mihon` keeps the files as the diff base.
- **Progress counts move into the downloaders.** The observed running maximum could not survive the screen closing and would have counted cancelled chapters as downloaded.
