# Download-queue unification

## Goal

Collapse the manga and novel download queues onto one shared Reikai-owned Compose component: a flat list of one card per series, so a change to either reaches both (anti-divergence), while the queue stays legible when a single title has thousands of queued chapters.

## Why

The two queues were near-duplicate implementations that had drifted. The manga queue is a View holdout (`RecyclerView` + `eu.davidea.FlexibleAdapter`, hosted via `AndroidView`); the novel queue was a bespoke Compose list of per-chapter rows under a source header. Both listed one row per chapter, so a full-novel download (a few thousand chapters) rendered as a few thousand rows, and the novel list visibly jumped as downloads completed. Merging both onto one card composable makes future divergence structurally impossible and retires a View holdout, following the same `Entry*` seam used for History / Updates / cover.

## Approach

A shared `EntryDownloadCardList` (in `reikai.presentation.download`) renders a neutral `EntryDownloadCardUi` model; each content type aggregates its own queue into it. This is the unified-content-UI seam: domain models and ScreenModels stay per-type, only the pixels are shared.

A card is a whole **series** (novel / manga), not a single chapter:

- `seriesId` (the novel / manga id) is the reorder + cancel target and the list key.
- `sourceName` labels the card (there is no source header; the label carries the source instead).
- `downloadedChapters` / `totalChapters` is the aggregate progress. Because completed chapters leave the queue, `totalChapters` is the highest queued count ever seen for that series (kept in an `initialTotals` map) and `downloadedChapters` counts down from it; progress is chapter-count based, not per-page.
- `status` is one of `QUEUED` / `DOWNLOADING` / `ERROR`.

Grouping by series is what keeps a 3000-chapter download to a single card. The tradeoff is that the global queue no longer offers per-chapter reorder / cancel; that is a deliberate cut (see Decisions), parked as a revive item.

### Reorder

The list is flat and homogeneous (one card per series), which is exactly the case `sh.calvin.reorderable` handles well, so reorder is a plain drag plus per-card to-top / to-bottom chevrons and a cancel. All three reorder paths commit the same way: the component hands a new series-id order to `onReorder`, and the ScreenModel expands each series back into its block of queued chapter ids and persists that. A committed order (drag or chevron) is held until the manager's queue echoes it back, so a stale progress-driven emission arriving before the echo can't clobber it; card content (counts, status) always refreshes, only the ordering is guarded.

### Live status without flicker

The novel downloader drains sequentially, finishing a chapter and then pausing (per-source pacing) before the next, so no chapter is in the `DOWNLOADING` state during the gap. Deriving "Downloading" from transient per-chapter state therefore flickers to "Queued" between chapters. Instead the manager exposes `downloadingNovelId`, latched across the pacing gap, and the card reads its status from that. "Error" shows only when a series is genuinely stuck (every remaining chapter errored), so one failed chapter doesn't mislabel a series that is still downloading fine.

### Pause on connection loss

A dropped connection (airplane mode, dead network) is not a download failure. The drain pauses while `activeNetworkState().isOnline` is false and resumes on its own when it returns, mirroring the existing "download only over Wi-Fi" pause; a chapter interrupted mid-download is requeued rather than errored. This keeps a transient outage from burning chapters to a permanent `ERROR` state.

## Key files

- `reikai/presentation/download/EntryDownloadCardList.kt` (new): the shared composable + the neutral `EntryDownloadCardUi` / `EntryDownloadCardStatus` model.
- `reikai/presentation/download/NovelDownloadQueueScreenModel.kt`: the novel aggregator (per-series cards, `initialTotals`, status from `downloadingNovelId`), `reorderBySeries`, `cancelSeries`, `sort`.
- `reikai/novel/download/NovelDownloadManager.kt`: `downloadingNovelId`, the offline pause, and the mid-download requeue.
- `eu/kanade/tachiyomi/ui/download/DownloadQueueScreen.kt`: hosts both content types behind the `ContentType` chip; the novel branch renders the shared component, the manga branch is redirected in commit 2. Also the Pause/Resume FAB (manga-only, lifted in Phase 11).
- `eu/kanade/tachiyomi/ui/download/DownloadQueueScreenModel`, `DownloadHolder`, `DownloadHeaderHolder`, `DownloadAdapter`: the manga View queue; to be aggregated onto the shared card and marked inert in commit 2.

## Status

In progress. The novel side ships first: series cards, drag + to-top / to-bottom / cancel, the latched-status flicker fix, the offline pause, and Tsundoku's 16.dp card gutter. Commit 2 migrates the manga queue onto the same component (aggregate by series, extract callable reorder / cancel on the manga ScreenModel, redirect the branch off the `AndroidView`, mark the Mihon View files inert).

## Decisions & tradeoffs

- **One card per series, both types.** Chosen over per-chapter rows because a full-novel download is otherwise thousands of rows, and a flat series list makes drag reliable (no grouped-header drag, which the library can't do). The cost is losing per-chapter reorder / cancel from the global queue; per-chapter selection still lives on the details screen, and the expandable-card revive is parked in the roadmap (Mihon's per-chapter manga queue files stay in the tree for it).
- **Pause, don't error, on connection loss.** A lost connection isn't a failure; erroring chapters on a transient outage lost downloads and mislabeled recovering series. The drain now pauses and requeues instead.
- **Status from a latched active-series signal.** Deriving "Downloading" from transient per-chapter state flickered every pacing gap; the manager's latched `downloadingNovelId` fixes it, and "Error" is reserved for genuinely stuck series.
- **Route around, do not rewrite Mihon.** The manga View files stay verbatim and inert, so upstream syncs of the download queue remain clean.
- **Novel queue pause/resume deferred to Phase 11.** The FAB pauses/resumes the manga downloader; the novel downloader auto-drains. Levelling novels up (user pause/resume) lands after the list unification.

Part of the broader [unified-content-ui](unified-content-ui.md) initiative.
