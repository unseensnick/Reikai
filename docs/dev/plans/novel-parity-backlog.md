# Novel parity backlog (shipped)

A consolidated record of the smaller-to-medium features that bring the light-novel surfaces up to parity with the manga ones, on top of the core LN vertical.

## Goal

Close the everyday gaps between novels and manga so that, once the core light-novel vertical shipped (browse, details, reader, downloads, library, updates, merge, tracking, backup), a reader cannot tell which content type they are looking at from what the app lets them do. Each item here is the novel twin of an existing manga capability, ported onto Mihon's models and reusing Mihon's machinery wherever it already covers both.

## Why

The core vertical made novels first-class library content. Parity is the difference between "novels work" and "novels feel native." The features below are individually small, but their absence is what a manga user notices first: no reading history, no way to move a novel to a better source, no per-title note, no stats, no screen-on lock. Grouping them in one record (rather than a plan per feature) keeps the navigable history without a doc explosion, since none of them needed a multi-phase plan of its own.

## Approach

Grouped by the area of the app each touches. Each entry describes current behavior plus the one mechanism that makes it work. The cross-cutting design principle throughout: reuse Mihon's shared machinery (preferences, the tracker push, the category-filter row, the markdown note editor) and add a novel-specific path only where Mihon's manga code filters novels out or is hard-typed to manga.

### History

**Novel reading history in the History tab.** Novel reads now appear in the same History tab as manga, behind an All / Manga / Novels chip, the way the Updates tab consolidates both content types. Mihon's untouched manga `HistoryScreenModel` and a new `NovelHistoryScreenModel` both render through one `ReikaiHistoryScreen`: one search box and one clear-all drive both, rows interleave by read time (newest first) under shared date headers. The novel reader records history on chapter switch and on exit via a session timer that accumulates `time_read` (the novel twin of `ReaderViewModel.updateHistory`). Data sits in a `novel_history` table plus a `novelHistoryView` that collapses to one row per novel (the chapter with the latest read time). Resume reopens the recorded chapter if it is still unread, else the next one. Soft-delete one row, reset by novel, and clear-all are all scoped to the active chip, so a Novels-scoped clear leaves manga history intact. This is the History half of the old "unified Recents," and is the direct twin of the Updates consolidation (see `unified-updates.md`).

### Migration

**Migrate a novel to another source.** A favorite-gated Migrate action in the novel details overflow, the novel twin of Mihon's manga migration. It searches the title across novel sources (reusing the global-search fan-out behind a shared results composable, hiding the current source); picking a target materializes it (fetch plus chapter sync), then a Mihon-style "Select data to include" dialog offers a Copy versus Migrate choice. `MigrateNovelUseCase` copies per-chapter read, bookmark, and scroll-progress matched by chapter number, moves categories, and favorites the target. On Migrate (not Copy) it also unfavorites the old novel and splits it out of any merge group. The result is a new target row with the old one unfavorited (not an in-place edit, not a delete): safer, recoverable, and matching Mihon. A follow-up re-queues for download any chapters that were offline on the old source, matched by chapter number, re-fetching the files rather than copying them (the manager skips chapters the target already has). See `novel-details.md` for the details-screen surface and `novel-browse.md` for the global-search fan-out it reuses.

Note: full batch / library migration (pick and order target sources, then a per-novel review list) is scouted but not yet built; only the per-novel Migrate action above is shipped.

### Library modes

**"Downloaded only" mode for the novel library.** Novels now honor the app-wide "Downloaded only" toggle. `NovelLibraryScreenModel` forces the downloaded filter on at the match step only, so the global mode does not light the per-session filter dot (matching how `LibraryScreenModel.applyFilters` behaves for manga), reusing the existing entry-level `downloadCount > 0` predicate. The Filter sheet's Downloaded chip locks on while the global mode is active.

**Incognito mode for novels.** Novels honor the global Incognito toggle. While incognito, the novel reader does not record history, scroll position, read state, or last-read, and skips the reader's tracker push; the browse model does not record the last-used novel source. This is gated on the global state only (`GetIncognitoState.await(null)`): novel sources are String-keyed with no installed extension, so per-source incognito does not apply. The details mark-read tracker sync stays ungated, matching manga.

### Reader

**Per-novel reader orientation lock.** Each novel can pin its reader orientation (Default, Portrait, Landscape, Locked-portrait, Locked-landscape) from the reader's Display sheet, with a global default in Settings to Reader. Default means "follow the global setting." It reuses the manga `ReaderOrientation` enum and a new `novels.viewer_flags` Long column (the novel twin of `Manga.viewerFlags`), written surgically via the `NovelUpdate` partial-update on the opened entry (the merge anchor). The reader resolves per-novel versus global, applies the orientation in a `LaunchedEffect`, and restores it on leave. OS caveat: Android 16 (targetSdk 36) ignores an app's fixed-orientation request on large screens (unfolded foldables and tablets), so the lock is a no-op there by OS policy; it works on phones and folded covers.

**Keep screen on (novel reader).** A "Keep screen on" switch in the novel reader's Display settings drives a `DisposableEffect` that sets and clears `FLAG_KEEP_SCREEN_ON` on the activity window. Because the novel reader is a Voyager screen inside MainActivity, the flag is toggled on enter and cleared on leave.

**Mark chapter read when skipping ahead (novel).** Matching manga, tapping Next marks only the departed chapter as read, never the in-between range, never on backward paging, never in incognito, and skips chapters already read. It is gated on a new opt-in preference (default off). The reader's existing inline tracker push fires for the departed chapter when auto-update is on.

See `novel-reader.md` for the reader architecture these settings hang off.

### Downloads

**Reorder and sort the novel download queue.** The queue surface is now drag-to-reorder and sortable, matching the manga side. It renders as one flat reorderable list (via `sh.calvin.reorderable`); the novel title shows as a caption only at a group boundary (its novel differs from the row above), so it reads grouped while staying a single flat list the reorder library can drive. Dragging commits once on drop through `NovelDownloadManager.reorderQueue`, which sets the live `queueState` and re-persists the order via a new `NovelDownloadStore.replaceAll`, so a reorder survives a cold restart (the old in-memory-only `startDownloadNow` bump did not). The Sort menu (upload date / chapter number, asc/desc) sorts each novel's chapters among themselves and keeps the novels' first-appearance order, mirroring the manga per-series sort. In the unified queue's ALL view the one Sort action applies to whichever queues are shown, so manga and novels sort together by the same key. The active drain re-reads the queue each step, so a reorder takes effect on the next pick and the in-flight chapter is untouched.

### Stats

**Novels in the Stats screen.** The Stats screen has an All / Manga / Novels chip (the same shared `ContentTypeFilterChips` used elsewhere) that switches which content's stats show; All combines both. `StatsScreenModel` reads the manga and novel libraries once and folds precomputed pieces per type, so switching the chip needs no recompute. Novels show the full card set: read-duration from a `novel_history` total-read-duration query, trackers via `GetNovelTracks`, and a novel global-update count over the novel update prefs. Only the `local` source count is manga-only (zero for novels). Every additive card reads as manga plus novels.

### Browse

**Long-press to add from global search (manga and novel).** The per-source Browse long-press-to-favorite workflow now works in global search too, where it previously just opened the details screen. Full Browse parity: long-pressing a favorited result offers a Remove confirm; an unfavorited result with same-title library entries pops the duplicate "add anyway" dialog; otherwise it adds, showing the category picker when categories exist. The favorite / category / duplicate orchestration lives in two shared helpers, each reused by both that content type's Browse model and its global-search model: `NovelLibraryAdder` and `MangaLibraryAdder`. For manga, the helper resolves the source per result (`sourceManager.getOrStub(manga.source)`) since global search spans sources. See `novel-browse.md` for the novel browse surface.

### Cross-cutting: the NovelUpdate partial-update

Per-novel Notes introduced the additive `NovelUpdate` partial-update path: a free-text markdown note on each saved novel's details screen (overflow to Notes, plus an Edit button in the expanded description once a note exists), reusing Mihon's `MangaNotesTextArea`. Rather than rewrite the whole row, it saves through a new `novels.sq partialUpdate` driven by a `NovelUpdate` model (the novel twin of `MangaUpdate`), touching only the changed column. The orientation lock above also rides this path. Caveat: `genre` (a list) and `update_strategy` cannot go through the coalesce partial path (SQLDelight drops their column adapters for the novels table), so callers patching those stay on the full-row `update(Novel)`. The note round-trips through backup. See `novel-backup.md`, which documents the proto round-trip the partial-update fields participate in.

**Surgical writes centralised in interactors (matching manga).** Once the partial-update path existed, the remaining full-row `update(Novel)` writers (favorite toggles, cover-changed stamp, last-update stamp, chapter sort / filter / display flags, reader orientation) were moved onto it and grouped into three interactors mirroring Mihon's: `UpdateNovel` (twin of `UpdateManga`, with `awaitUpdateFavorite` / `awaitUpdateCoverLastModified` / `awaitUpdateLastUpdate` plus a generic `await(NovelUpdate)`), `SetNovelChapterFlags` (twin of `SetMangaChapterFlags`), and `SetNovelViewerFlags` (twin of `SetMangaViewerFlags`, orientation only since the novel reader is text-based). Each write now touches a single column instead of a read-modify-write of the whole row, and the favorite paths pick up manga's `dateAdded` semantics (set on favorite, zeroed on unfavorite). Two paths deliberately stay full-row: edit-info and restore, which legitimately write columns back to null (`coalesce` can't express that) and touch the adapter-typed `genre` / `update_strategy`. One nuance: the merge-group bulk favorite toggle uses the favorite-only generic `await` rather than `awaitUpdateFavorite`, because its undo restores a removed merge source and must preserve the original `dateAdded` instead of re-stamping it.

## Key files

History
- `app/src/main/java/reikai/presentation/history/ReikaiHistoryScreen.kt` (the host behind the All / Manga / Novels chip)
- `app/src/main/java/reikai/presentation/history/NovelHistoryScreenModel.kt`
- `data/src/main/sqldelight/tachiyomi/data/novel_history.sq` (the `novel_history` table) and `data/src/main/sqldelight/tachiyomi/view/novelHistoryView.sq` (the per-novel view)

Migration
- `app/src/main/java/reikai/domain/novel/interactor/MigrateNovelUseCase.kt`
- `app/src/main/java/reikai/presentation/novel/migrate/NovelMigrateSearchScreen.kt`
- `app/src/main/java/reikai/presentation/novel/migrate/MigrateNovelDialog.kt`

Library modes
- `app/src/main/java/reikai/presentation/library/novels/NovelLibraryScreenModel.kt` (downloaded-only force, incognito gating on the browse side)

Reader
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderScreenModel.kt` (orientation resolve, mark-read-on-skip, incognito gating)
- `app/src/main/java/reikai/presentation/novel/reader/NovelReaderSettingsSheet.kt` (Display settings: orientation, keep-screen-on)
- `app/src/main/java/reikai/domain/novel/NovelPreferences.kt` (`readerKeepScreenOn`, `readerMarkReadOnSkip`, orientation default)

Downloads
- `app/src/main/java/reikai/presentation/download/NovelDownloadQueueList.kt` (flat reorderable list with boundary captions) and `NovelDownloadQueueScreenModel.kt` (flat state, `reorder` / `sort`)
- `app/src/main/java/reikai/presentation/download/DownloadQueueSortSheet.kt` (the `TabbedDialog` + `SortItem` sort modal, matching the library / chapter sort sheets)
- `app/src/main/java/reikai/novel/download/NovelDownloadManager.kt` (`reorderQueue`) and `NovelDownloadStore.kt` (`replaceAll`)
- `app/src/main/java/eu/kanade/tachiyomi/ui/download/DownloadQueueScreen.kt` (`// RK` unified Sort across both queues)

Stats
- `app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsScreenModel.kt` (`// RK` graft for the content-type fold)

Browse
- `app/src/main/java/reikai/presentation/novel/browse/NovelLibraryAdder.kt`
- `app/src/main/java/reikai/presentation/browse/MangaLibraryAdder.kt`

Cross-cutting
- `app/src/main/java/reikai/domain/novel/model/NovelUpdate.kt` (the partial-update model)
- `app/src/main/java/reikai/presentation/novel/notes/NovelNotesScreen.kt`
- `app/src/main/java/reikai/domain/novel/interactor/UpdateNovel.kt`, `SetNovelChapterFlags.kt`, `SetNovelViewerFlags.kt` (the surgical-write interactors)

## Status

All shipped and on-device verified (Z Fold / Fold6).

| Item | Area | Commit |
|---|---|---|
| Novel History tab (Roadmap 5) | History | `bc0b78d37`..`2d2807015` |
| Migrate a novel to another source (Roadmap 7) | Migration | `9756d2a76` (download re-queue follow-up `fc6dabb03`) |
| "Downloaded only" novel library mode | Library modes | `f7d85efcd` |
| Incognito mode for novels | Library modes | `25d046329` |
| Per-novel reader orientation lock | Reader | `37bfa661f` |
| Keep screen on (novel reader) | Reader | `6a11e28af` |
| Mark chapter read when skipping ahead | Reader | `045d60ab5` |
| Download retry on failure | Reader / downloads | `f4dc3c8cc` |
| Download only over Wi-Fi | Reader / downloads | `a8a21bdcf` (resume-when-back `05e0b2620`) |
| Download-queue reorder + sort | Downloads | `f5f526094` |
| Novels in the Stats screen | Stats | `5215e531e` |
| Per-novel Notes | Cross-cutting | `9147b9f21` |
| Long-press add-to-library in global search (manga + novel) | Browse | `1d2aa4b8a` |
| Surgical novel writes via UpdateNovel / SetNovelChapterFlags / SetNovelViewerFlags | Cross-cutting | `b6b1429d6` |

## Decisions & tradeoffs

These are deliberate parity trims, places where the novel side intentionally does less than the manga side because the extra behavior was manga-specific or low-value.

- **No add-to-library button on novel history rows.** Manga history rows have one for non-library entries; the novel row does not. Re-adding goes through the cover, which opens the details screen. The manga add-favorite path drags in duplicate-check, category, and tracking dialogs that are manga-specific.
- **Migration does not carry history or tracks.** A Copy/Migrate carries per-chapter read / bookmark / scroll-progress and categories, but not History-tab rows or trackers, parity with Mihon's manga migration (tracks shipped as their own feature, separately).
- **Migration creates a new target row, never edits in place or deletes.** Migrate unfavorites the old novel and splits it from any merge group rather than overwriting it. Safer, recoverable, and matching Mihon.
- **Incognito for novels is global-only, with no per-source variant.** Novel sources are String-keyed with no installed extension, so a per-source incognito toggle has nothing to bind to. The details mark-read tracker sync stays ungated, matching manga.
- **Notes are saved surgically, not as a full-row rewrite.** This is why the `NovelUpdate` partial-update exists. The `genre` and `update_strategy` columns cannot use it (SQLDelight drops their adapters on the novels table), so callers patching those keep the full-row update.
- **Orientation lock is a no-op on large screens by OS policy.** Android 16 (targetSdk 36) ignores fixed-orientation requests on unfolded foldables and tablets; the feature works on phones and folded covers and cannot be made to work otherwise without lowering the target SDK.
