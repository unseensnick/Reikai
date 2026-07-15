# Novel tracking

Bind a light novel to any of the seven novel-capable trackers from its details screen and keep reading progress in sync, reusing Mihon's existing tracker services; merge-group-aware so a track set on one source advances and shows across the whole merged novel.

## Goal

Give novels the same two-way tracker sync manga already has: from a novel's details screen, sign-in-gated, bind the novel to one of the novel-capable trackers and push status, chapters read, score, and start/finish dates back to that service. Progress should update automatically as you read, and the binding should behave sensibly across a merged novel's multiple sources.

## Why

Tracking is a core reader-app expectation: users keep a single list of what they're reading on a service like AniList, and the app should write progress there so they don't double-book. Manga already had this through Mihon's tracker stack. Light novels were the only library content without it, which broke parity and meant a reader who tracks manga had to manually update their novel entries elsewhere. This closes that gap (Roadmap Active item 8).

## Approach

A "Tracking" action in the novel's overflow menu opens a sheet that looks and works exactly like the manga tracking sheet. Search the tracker for the matching title, bind it, then set status / chapters read / score / dates. The app pushes those values to the tracker as you read. Almost everything is Mihon's existing machinery; only a thin novel-specific layer sits on top.

**The Tracking sheet.** The novel details overflow has a Tracking action that opens the shared `reikai.presentation.track.EntryTrackInfoDialog`: one dialog serving both content types, parameterized on an `isNovel` flag and carrying the domain `Track` (novels adapt via `toUiTrack`). Writes go through a thin `TrackWriter` seam, `NovelTrackUpdater` on the novel side. Detail: [content-parity-drift-and-collapse.md](content-parity-drift-and-collapse.md) Phase 6 (`bba220e2b`).

**Reusing Mihon's Tracker services and OAuth.** The novel-capable trackers are Mihon's own `Tracker` service classes. Sign-in (OAuth), the "find this entry by remote id" path, and the per-field update calls are all reused as-is. No new tracker service exists.

**Which trackers, and the `supportsNovels` gate.** Seven trackers search novels: AniList, MyAnimeList, MangaUpdates, Kitsu, Shikimori, Hikka, and MangaBaka (the last three added in `3acb424cb`). Each declares `supportsNovels = true` (`Tracker.kt`), which gates the novel tracking sheet so a manga-only tracker can't be bound to a novel. Bangumi and MdList are deliberately gated out: their APIs can't distinguish a novel from a manga, so a novel search would return manga hits.

**The `// RK` searchNovel path.** The one place Mihon's trackers needed a novel-specific branch is search. Mihon's manga `search()` filters light novels out of the results (it asks the tracker's API for manga-type entries). So each novel-capable tracker overrides a `// RK`-fenced `searchNovel()` that queries for novel/light-novel entries instead; the default degrades to the manga `search()`. Everything downstream of the search result (binding, finding, updating, OAuth) is unchanged Mihon code.

**Persistence to `novel_tracks`.** Bindings are stored in the `novel_tracks` SQLDelight table. The empty table itself shipped earlier (in migration `14.sqm`, which creates the novels schema); this work added the queries plus the `NovelTrack` domain model, its repository, and the interactors that read and write it. The shape mirrors the manga `manga_sync` table and the novel-history layer added just before it. Bind logic (`AddNovelTrack`) and the per-field updates (`NovelTrackUpdater`) are faithful ports of Mihon's `AddTracks.bind` and `BaseTracker`, writing to `novel_tracks` instead of `manga_sync`.

**Auto-sync from the reader and mark-read, with an offline queue.** Reading progress pushes to the tracker from two places, matching manga: the reader (once you pass roughly 97% of a chapter, gated on the per-tracker `autoUpdateTrack` setting) and the details screen's mark-as-read action (respecting the never / always / ask `AutoTrackState` preference). If the device is offline when an update should fire, it is queued and retried later via a delayed-tracking store and a background job (`TrackNovelChapter` plus `NovelDelayedTrackingStore` / `NovelDelayedTrackingUpdateJob`), the novel twins of Mihon's offline tracking-retry path.

**Group-aware tracking (merged novels).** A merged novel is several source-specific rows grouped under one library entry. Tracking is centralized at the group level: a track bound on one source advances and displays when you read or view any sibling source. This is cleaner than manga's approach of copying the track onto every member at bind time. Because Reikai owns the novel reading path (`TrackNovelChapter`), it can keep a single track row while merged with no duplicates and no per-member gating: the group is resolved through `NovelMergeManager.relatedNovelIdsFor`, and reads/subscriptions go through `GetNovelTracks.awaitGroup` / `subscribeGroup` (deduped by tracker).

Survival across an unmerge is handled lazily by `PropagateNovelTrackerLinks` (the novel twin of manga's tracker-link mirroring). At the moment a group is split (library unmerge, or the details Manage-sources split, gated by the `syncTrackerLinksGrouped` preference) it copies the group's trackers onto each favorited member, so every source keeps the tracker after the split. For the broader cross-source tracker-propagation behavior this shares with manga, see [tracker-sync.md](../../tracker-sync.md).

This describes current behavior.

## Key files

Domain layer (net-new, under `reikai.*`), in `app/src/main/java/reikai/domain/novel/`:

- `model/NovelTrack.kt`: the immutable track model.
- `NovelTrackRepository.kt`: repository interface; impl at `app/src/main/java/reikai/data/novel/NovelTrackRepositoryImpl.kt`.
- `interactor/AddNovelTrack.kt`: bind logic (port of `AddTracks.bind`).
- `interactor/GetNovelTracks.kt`: reads, including the group-aware `awaitGroup` / `subscribeGroup`.
- `interactor/InsertNovelTrack.kt`, `interactor/DeleteNovelTrack.kt`, `interactor/RefreshNovelTracks.kt`: write / delete / refresh.
- `track/NovelTrackUpdater.kt`: per-field updates (port of `BaseTracker`).
- `track/NovelTrackConversions.kt`: the `NovelTrack` <-> domain `Track` carrier adapter.
- `track/TrackNovelChapter.kt`: read-progress push entry point.
- `track/NovelDelayedTrackingStore.kt`, `track/NovelDelayedTrackingUpdateJob.kt`: the offline retry queue + job.
- `track/PropagateNovelTrackerLinks.kt`: copies trackers per source at unmerge.
- `NovelMergeManager.kt`: `relatedNovelIdsFor`, the group resolution used by the group-aware reads.

Persistence:

- `data/src/main/sqldelight/tachiyomi/data/novel_tracks.sq`: the queries (the table was created empty in `14.sqm`).

Mihon files patched with `// RK` islands (search path, sheet wiring, DI):

- `app/src/main/java/eu/kanade/tachiyomi/data/track/{anilist,kitsu,mangaupdates,myanimelist,shikimori,hikka,mangabaka}/`: the seven `searchNovel()` paths in each tracker + its API class.
- `app/src/main/java/eu/kanade/tachiyomi/data/track/Tracker.kt`: the `searchNovel` + `supportsNovels` contract additions.
- `app/src/main/java/eu/kanade/domain/DomainModule.kt`: DI registration of the novel track repo + interactors. `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt` registers only the `NovelDelayedTrackingStore` (the offline queue).

UI / sync wiring:

- `app/src/main/java/reikai/presentation/novel/details/NovelDetailsScreenModel.kt`: opens the sheet and drives mark-read auto-sync.

(File list confirmed against the working tree and commit `7c56e07eb`; see `git show --stat 7c56e07eb`.)

## Status

Shipped in commit `7c56e07eb`, on-device verified (Z Fold). Roadmap Active item 8, done 2026-06-20. Unit-tested: conversion round-trip and updater state transitions.

## Decisions & tradeoffs

- **Reuse Mihon's tracker infrastructure rather than build novel-specific trackers.** The seven novel-capable trackers are Mihon's own `Tracker` services; novels ride the same OAuth, find, and update code. Only `search()` needed a novel branch. This keeps the patch surface against upstream small and inherits Mihon's maintenance of those services.

- **Private listing supported (added later).** Mihon's manga tracking can mark an entry "private" on the tracker. The `novel_tracks` table first shipped without that column, so early novel tracks were always public; the `private` column was later added (25.sqm), so the private toggle now appears in the novel sheet and syncs to the service like manga. The vestigial `allowPrivate` gate that once hid it has since been removed.

- **No on-bind start-date backfill.** Binding a novel does not auto-fill a start date the way some flows might. The date fields are still settable manually in the sheet. Keeps bind logic simple and avoids guessing a date the user may not want.

- **One track row while merged, not copy-to-each-member.** Group-aware tracking keeps a single row for a merged novel and resolves the group at read/display time, which avoids the duplicate-row and gating complexity manga carries. The trade is a lazy copy at unmerge (`PropagateNovelTrackerLinks`) so each source keeps the tracker after a split. Possible only because Reikai owns the novel reading path.

- **Dedicated light-novel trackers are not viable (Hardcover the only watch item).** Researched June 2026 and adversarially verified: NovelUpdates has no public API (Cloudflare-gated, breaks chronically; it is a content source in LNReader, not a tracker), MiraiList is pre-1.0 alpha with no API yet, Novel Trackr has no documented API, RanobeDB's API is read-only, and Hardcover has a real read/write GraphQL API but is in beta and its docs forbid deployed-client use (no OAuth/allowlist). Hardcover is the only future watch item: revisit if it exits beta and ships OAuth/allowlisting. Detail in the `novel-tracking` memory.

## Related

- [novel-merge.md](novel-merge.md): the merge grouping that makes tracking group-aware.
- [novel-details.md](novel-details.md): the details screen that hosts the Tracking sheet.
- [tracker-sync.md](../../tracker-sync.md): user-facing reference for tracker propagation across grouped sources.
