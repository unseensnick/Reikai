# Download-queue unification

## Goal

Collapse the manga and novel download queues onto one shared Reikai-owned Compose component, so a change to either reaches both (anti-divergence), while bringing the novel queue up to manga parity: source grouping with a count, a per-row overflow menu (move / move-series / cancel / cancel-series), and reliable reordering.

## Why

The two queues were near-duplicate implementations that had drifted. The manga queue is a View holdout (`RecyclerView` + `eu.davidea.FlexibleAdapter`, hosted via `AndroidView`); the novel queue was a bespoke Compose list. The novel side lacked per-series cancel, the move actions, and source grouping, and it visibly jumped as downloads completed (its group caption lived on the first chapter row, so finishing that chapter re-parented the caption). Merging both onto one composable makes future divergence structurally impossible and retires a View holdout, following the same Entry* seam used for History/Updates/cover.

## Approach

A shared `EntryDownloadQueueList` (in `reikai.presentation.download`) renders a neutral `EntryDownloadQueueRowUi` model; each content type maps its own queue into it. This is the unified-content-UI seam: domain models and ScreenModels stay per-type, only the pixels are shared.

The model carries two granularities, because manga headers group by source while the "series" menu actions target a single manga:

- `groupId` (the header group: the source for both types). It is a `String` so manga's `Long` source id (`source.id.toString()`) and a novel's plugin string id both fit one field.
- `seriesId` (the cancel-series / move-series target: `manga.id` or `novel.id`).
- `primaryText` / `secondaryText` (entry title + chapter, so a source hosting several entries stays legible), and an optional `progress` slot (a determinate bar for manga, null for novels, which show an indeterminate spinner).

Both types therefore present identically: a source header with a count, rows showing entry title + chapter, and the same 6-action overflow menu.

The group header is its own stable keyed list item (keyed by `groupId`), so a finished chapter animates out beneath a fixed header instead of shifting a caption. The Mihon manga screen is routed around: its View files are left inert (marked, not deleted), and only `DownloadQueueScreen`'s manga branch is redirected off the `AndroidView` onto the shared component (commit 2).

### Reorder (the hard part)

`sh.calvin.reorderable` (3.1.0) is a flat, single-key drag library: no group / nested / collapse API, headers cannot be drop targets, and it captures the dragged item's layout offset at drag-start before any user callback runs. So Mihon's header-drag trick (collapse every group to one row mid-drag via `FlexibleAdapter.collapseAll`, drag among collapsed headers, expand on release) cannot be replicated mid-gesture: collapsing after the capture desyncs the float. In-place draggable group headers are out.

Group reorder is instead a **Reorder Mode** (chosen over a long-press-collapse, which the library can't do, and a plain move-source menu, which is poor when the group you want is buried under a long list): a top-bar button enters the mode, groups collapse to a headers-only list (so every source header fits on screen and a buried group is reachable with no scrolling), each header offers chevrons (to-top / up / down / to-bottom, reliable and jank-free) plus optional drag (safe, since a collapsed headers-only list is the flat homogeneous case the library handles well), and Done exits and expands. Each move commits live; downloads keep draining.

Within-group **row** drag stays in normal mode. Its reliability fix: the list must not re-adopt the manager's queue and clobber a just-committed drag when a stale (pre-commit-order) emission arrives before the reordered echo; adopt membership changes (a finished download) but keep the committed order until the echo matches. Also pass the list's content padding as the reorder state's `scrollThresholdPadding` for correct edge auto-scroll.

Manga per-row progress flows from the existing `DownloadManager.progressFlow()` collected into a per-row snapshot map, so progress ticks update one row without re-diffing the list (the Compose analog of Mihon poking the bound holder).

## Key files

- `reikai/presentation/download/EntryDownloadQueueList.kt` (new): the shared composable + the neutral `EntryDownloadQueueRowUi` / `EntryDownloadProgressUi` model.
- `reikai/presentation/download/NovelDownloadQueueScreenModel.kt`: the novel mapper (`toEntryDownloadQueueRowUi`), source resolution, and `cancelSeries` / `moveToTop` / `moveToBottom` / `moveSeriesToTop` / `moveSeriesToBottom`.
- `eu/kanade/tachiyomi/ui/download/DownloadQueueScreen.kt`: hosts both content types behind the `ContentType` chip; the novel branch renders the shared component, the manga branch is redirected in commit 2. Also the Pause/Resume FAB (manga-only, lifted in Phase 11).
- `eu/kanade/tachiyomi/ui/download/DownloadQueueScreenModel.kt`, `DownloadHolder`, `DownloadHeaderHolder`, `DownloadAdapter`: the manga View reference (menu handlers, the collapse-drag mechanics, `shouldMove`); to be marked inert in commit 2.
- `eu/kanade/tachiyomi/data/download/DownloadManager` (`progressFlow` / `statusFlow`): the manga per-row progress source.

## Status

In progress. Grew out of the Phase 9 "novel downloads cancel-series" item once the decision was to unify rather than patch the novel side. Commit 1 (novel onto the shared component: source grouping, menu, stable header, mappers) is written and compiles but is uncommitted, pending the reorder rework (remove the janky in-place header-drag, fix the row-drag clobber, build the Reorder Mode). Commit 2 (migrate manga onto the shared component) is not started. Both need Fold verification.

## Decisions & tradeoffs

- **Both types group by source, symmetric mappers.** Novels were widened from grouping-by-novel to grouping-by-source so the two present identically; `groupId` became a `String` to hold both id types.
- **No in-place draggable headers.** A library constraint, not a Compose or Voyager one; the whole Compose reorder ecosystem is flat-list oriented (checked Calvin-LL/Reorderable, aclassen/ComposeReorderable). Only hand-rolled gesture code could replicate Mihon's exact feel, judged not worth it.
- **Reorder Mode over the alternatives.** It solves the "bump a buried small source to the front" case (collapse brings all headers on screen), is discoverable (a button, not a hidden long-press), and dodges the drag jank via chevrons while still allowing drag where it is reliable.
- **Route around, do not rewrite Mihon.** The manga View files stay verbatim and inert, so upstream syncs of the download queue remain clean; the only coupling is the manga mapper, which is compile-caught.
- **Novel queue pause/resume deferred to Phase 11.** The FAB pauses/resumes the manga downloader; the novel downloader auto-drains on a worker with no user pause. The ruling is to level novels up (add user pause/resume), but after the list unification ships.

Part of the broader [unified-content-ui](unified-content-ui.md) initiative.
