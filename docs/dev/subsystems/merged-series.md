# Merged series

## Purpose

A merged series is one series the user follows on several sources, shown as a single library card, one details page and one chapter list. Each chapter appears once, read, bookmark and download state count across every source, and reading flows from one source's chapters into the next. Manga and novels share the whole design; only the rule that pairs chapters across sources differs.

## How it works

### The group

A group is a stored row, never derived. `merge_group` holds the identity (`content_type` 0 manga, 1 novel) and the `override_source_ranking` flag. Membership lives in two per-type tables, `merge_group_manga` and `merge_group_novel`, each with `UNIQUE` on the entry id (an entry is in at most one group), a `source_priority` column and `ON DELETE CASCADE` to its own entry table, so a deleted entry leaves its group automatically. `MergeGroupRepository` is the one API over both types.

A group has two member reads, and they answer different questions:

- `getFavoriteMembers` is the library members only. Every read that **displays or aggregates** a group goes through it, resolved once in `EntryMergeManager.computeRelatedIds`: the chapter list, chips, reader, resume, trackers and the migration source picker. An entry removed from the library keeps its membership row, so a re-add rejoins the group, but it stops feeding what the group shows, and opened from History or Browse it resolves as a standalone entry.
- `getMembers` is the whole group. **Data operations** use it: the split undo, backup create and restore, and the repository's own rewrites.

`seriesMergingEnabled` (`series_merging_enabled`, in the library display menu and Settings) is a display switch. Off, `computeRelatedIds` answers every entry alone and the library shows every source separately, but groups are kept and still maintained (replace, remove, reorder, restore). Only `merge` itself is refused while it is off, since nothing would render the new group.

### How members join and leave

- **Library Merge.** Selecting several library cards and choosing Merge calls `merge(ids)`. Merging absorbs the whole group of any id it is given, so merging two collapsed cards pulls in every hidden member. The group of the first id that has one survives, keeping its id, member order and ranking flag; arrivals are appended after its members. Every member then takes the categories of the first library member in that order, the settings owner (`GroupCategories.adoptOwnerCategories`, from `onMerged`), which is the surviving group's, or the first card's when none of them had a group.
- **Add-time prompt.** Adding a series that matches a library entry (by title substring or a shared tracker id) shows the duplicate dialog, `EntryDuplicateDialog`. When `suggestGroupingOnAdd` holds (the master switch plus the type's same-title switch, `auto_merge_same_title` or `novel_auto_merge_same_title`), it offers "Add to existing group": duplicates are collapsed one card per group, the user picks in a selection mode, and only the picks are merged, because the title match is fuzzy and can name a different series. The favorite and the merge commit in one transaction (`MangaLibraryAdder.joinGroup`, `NovelLibraryAdder.joinGroup`), and nothing is written until a category picker the add raises is confirmed, so backing out adds nothing. The new entry takes the group's categories; a group filed in no category takes the default category with it, since the add's category write reaches every member.
- **Migration.** A replace migration swaps the old entry for the target inside the group in one transaction with the favorite swap, the target taking the old entry's slot in the order (`replaceInGroup`). A copy migration merges the target into the group. Migrating onto a sibling of the same group leaves a group of one, which is dissolved.
- **Re-add.** Favoriting a removed member again makes it a library member of its old group with no merge call. `GroupChapterSettings.rejoin` then gives it the group's current chapter settings.
- **Upgrade from 0.3.x.** `MigrateMergePrefsToGroupsMigration` (version 189) rebuilt the old preference-derived groups as rows through `MergeGroupReconstruction.reconstruct`: manual merges plus same-title groups, minus deliberate unmerge pairs, over favorites only.
- **Leaving.** Manage sources splits sources out (`removeFromGroup`, with an Undo built from a `GroupSnapshot` captured before the split, since a group shrinking below two is deleted and its flag with it), or removes them from the library and the group. Library Unmerge dissolves the whole group. Settings, Advanced, clears every group of one type. A group left with fewer than two members is always deleted.

### Categories

A merged series sits in one set of categories. Every category write to a library member goes through `GroupCategories` (`MangaGroupCategories`, `NovelGroupCategories`), which writes the same list to every library member of its group through the type's own category interactor: the library's Set categories (`change`, which keeps a category any member had unless the picker removed it, so a group that disagreed comes out agreeing), the details picker and every add path (`set`, behind `MangaLibraryAdder.moveToCategories` and `NovelLibraryAdder.applyCategories`), and a merge (`adoptOwnerCategories`). The library card is placed by its lead, and the lead follows chapter counts, so members filed apart would let a refresh move the card by itself. Like the chapter setting, the rule follows `computeRelatedIds`: with merging off, or for a member out of the library, a write reaches only that entry.

### Ranking: trunk, settings owner and library lead

Each group's members are ranked by `trunkOrder` in `SourceRanking.kt`: `sourcePriority` first (the member's position in the group's own order when `override_source_ranking` is on, else its source's position in the global `preferred_manga_sources` / `preferred_novel_sources` list), then the most chapters, then the lowest id. Dragging rows in Manage sources writes `source_priority` and turns the override on (`setSourceOrder`); Reset order turns it off (`clearSourceOrder`).

Three roles come out of that ranking, and they are deliberately not the same member:

- **The trunk** is the first member the stitch walks, `stitchOrder` skipping members with no chapters. Manga counts distinct recognized chapter numbers, novels count rows.
- **The library lead** (first in `libraryRanking`) is the member whose card, cover and Edit info the library shows, and the member the library list export writes. It uses the same `trunkOrder`, so it cannot lead on a different member than the stitch except when the ranked first member has no chapters.
- **The settings owner** is the first library member in stored `source_priority` order, the first id `computeRelatedIds` returns. A merged series has one chapter sort, filter and display setting, the owner's (`GroupChapterSettings`, with `MangaChapterSettings` and `NovelChapterSettings` as adapters). Every screen reads through the owner, a change through any member is written to every member, and a merge hands the owner's setting to the members joining (`EntryMergeManager`'s `onMerged` hook). It is not the trunk because, without an override, the trunk follows chapter counts and a new chapter on a sibling would move the setting by itself.

### The stored stitch

The stitch decides which chapters of different sources are the same chapter and the order of the merged list. It runs in exactly two places, `MangaGroupStitcher` and `NovelGroupStitcher`, both only from `ReconcileMergedChapters`, and its output is stored in `merged_chapter_unit` and `merged_novel_chapter_unit`: one row per member chapter with its `unit` (the merged chapter, NULL when the stitch dropped it) and `copy_order` (0 is the copy the list shows). `merged_group_ranking` records the ranking stamp each group was stitched under and how many unit rows it left.

The tables are a cache, never truth. A row records the inputs it was derived from (number, own-source order, and the name for novels), and the stale views (`mergedChapterStaleView`, `mergedNovelChapterStaleView`) find any group whose library members' chapters no longer match, whose rows name a chapter that is no longer a library member's, or whose row count changed. The reconciliation also compares each group's current `rankingStamps` with the stored one, so a reorder or a preferred-source edit restitches like a new chapter does. A stale group is rebuilt whole. Callers:

- `ReconcileMergedChapters.afterPass` wraps every chapter-writing pass (library update, backup restore, the gallery and metadata workers, a chapter-number correction) and reconciles even when the pass fails or is cancelled.
- `currentStitch` rebuilds one stale group before a screen renders it. Both chapter providers (`MergedChapterProvider`, `NovelMergedChapterProvider`) read through it.
- The two libraries reconcile on `stitchInputChanges`: membership, library membership and the preferred-source list.

`MergedChapterOrder` is the one ordering kernel. Each source is walked in its own listed order, best ranked first; a chapter an earlier source already placed moves a cursor to it, a new one lands just after the cursor. Chapter numbers are never compared across sources, because each site counts its own way. A source that matches nothing already placed goes at the end of the walk. The stitched position is stamped onto `sourceOrder` of an in-memory copy (`stampedReadingOrder`); nothing writes it back.

**Pairing differs by type:**

- **Manga** (`ChapterAggregation`) keys on the recognized chapter number narrowed to `Float`, one row per number across the group, which also collapses scanlator variants. Only the trunk keeps chapters with no recognized number. Chapters of a gallery source (each chapter a standalone work numbered 1) are never keyed and never paired.
- **Novels** (`NovelChapterAggregation.matchKey`) key on the normalized title (label words, leading number tokens, punctuation and apostrophes removed; a number after a title word kept), falling back to the one number a wordless name shows. Every trunk row is kept. A sibling's unmatched titled chapter, and any wordless run, is deferred and paired by position: a run between two anchors that holds exactly as many chapters as the order already holds there is the same chapters. Two different titles never fold. Novels also refuse a title match behind the cursor that this source already supplied, and one ahead of the cursor when the source repeats that title nearer by (`boundsBackwardMatch`, `boundsForwardMatch`). If the trunk has no keyable chapter at all, only the trunk's list is shown.

### Reading state across a group

Read and bookmarked are group-wide in every scope: a merged chapter is read when any copy is. Downloaded follows the scope. `GroupChapterFlags` answers all three for a list, over `flaggedOnAnotherSource` in `StoredStitch.kt`, so the three flags are one rule. The flags live on the screen's chapter items, never on `Chapter` / `NovelChapter`, whose rows stay database truth for tracker sync, delete-after-read and backup.

`MergeScope` is the scope. **Group** (the All view, library, History) reaches every copy; **Source** (a source chip, the Updates lane, a new-chapter notification) reaches the row's own copy only. The reader takes it as the `source_scoped` intent extra (`ReaderActivity.newIntent`, `newNovelIntent`); source scope shows the opened source's own chapters, while read-state propagation stays group-wide.

- **Actions** on a merged list (`EntryMergeGroupHost.expandToGroup`, `expandForDelete`) reach every copy of the chosen merged chapters through `expandToUnits`. Recents verbs do the same: mark-read, bookmark and delete reach every copy; download does not.
- **Opening** goes through `CopyToOpen`: the copy asked for when it is on disk, else the best-ranked copy on disk that the scope reaches, else the copy a download would fetch. Both readers swap every group-scope row for its pick, so prev/next, download-ahead and the Downloaded-only filter agree. Progress and history are written to the copy actually read.
- **Downloads** fetch one copy per merged chapter, never one per source. `DownloadTargets` picks the row's own copy while its source is installed, else the best-ranked copy on an installed source, else none. Library download-next and the details actions go through `DownloadCandidates.forGroup`, skipping a chapter any member holds on disk, and queue each chapter under its owning entry's folder.
- **The All view's serving member** (`unifiedViewMember`) is the anchor while its source is installed, else the first installed member. Downloads, Open in WebView and the update interval go through it, so an anchor whose source was uninstalled still works from the All view.
- **Reader.** `MergedChapterLoader` holds one Mihon `ChapterLoader` per source and routes each chapter to its own; tracker sync, delete-after-read and incognito are decided per chapter's own source. Save image and set-as-cover stay on the opened entry. `withOpenedChapter` places a chapter the merged list does not show (a sibling's copy resumed from History) in its unit's slot.
- **Updates.** An update run counts and announces one copy per merged chapter (`collapseNewChapters`, run after reconciliation): a chapter is news only when the group did not already have that merged chapter. Download eligibility stays per entry, so the run deduplicates downloads by `dedupeKey` rather than downloading the announced copy. A run that does not finish queues and counts its arrivals uncollapsed from a `finally`, since they are written and will never be new again; a duplicate download is the cheaper mistake.
- **The reader's transition card** asks `isChapterDownloaded` against the next chapter's own source through the in-memory download cache, not a storage probe, so a cross-source boundary binds without a main-thread disk read; the badge can lag a beat behind a download that just finished.

### Library, counts and tracking

`MangaMergeCollapse` and `NovelMergeCollapse` bucket library items by group and stamp the lead row through one kernel, `stampMergedGroup`: member ids and sources, source badges (`merge_source_icons`), the latest read across members, and the group's counts. The counts are one set from the stored stitch, `countsByGroup` (total, read, bookmarked units over the counted-copy views), feeding the unread badge, the Started / Bookmarked / Unread filters, the chapter sorts and the read/total search terms together. The download badge counts merged chapters with a copy on disk (`downloadUnitsByGroup`). A group not stitched yet shows its lead's own counts. Excluded scanlators (manga only) and members out of the library are filtered at read time, so neither forces a restitch. Statistics and the list export count a group once through `EntryMergeManager.seriesBuckets`.

### The group's cover

A merged series draws its shown member's cover, and when that one has no cover or fails to load, the next member's that does. The shown member goes first because its cover carries the user's custom cover and edited cover address; `coverFallbacks` lists the rest. The library row tries them in `libraryRanking` order (`LibraryItem.coverFallbacks`, read by `libraryCoverModel`), the details header and cover viewer in the group's member order under All only, and the duplicate dialog's group card in the order it lists the duplicates. A source chip draws its own source's cover alone.

The fallback happens as the image loads. `withCoverFallbacks` wraps the candidates in a `GroupCover` model, and `GroupCoverInterceptor`, first in the image loader's chain, runs each candidate through the rest of the chain until one decodes. `FailedCovers` remembers the candidates that failed in this process and tries them last, so a dead cover is not fetched and decoded again every time the series scrolls into view; once every candidate has failed, all are tried again in order. Nothing is written: the lead stays the lead and its stored cover is untouched.

Tracking spans the group while `sync_tracker_links_grouped` is on: `GroupTrackReader` (behind `GetTracksInGroup` and `GetNovelTracks`) reads every member's tracks, one per tracker, the furthest read winning. Before any path breaks a group up or takes a member out of the library, `handOutGroupTrackers` copies the shared binding onto each favorited member, skipping a tracker whose remote id disagrees; it runs from `EntryMergeManager`'s `onBeforeDissolve` and `handOutTrackersBeforeRemoval`, before the unfavorite.

### Removal

All removal goes through `EntryLibraryRemoval` (tracker hand-out, one favorite write, then covers). The details heart decides its targets with `DetailsRemoval`: under a source chip it removes that source; under All on a merged entry it asks with the "All grouped sources" choice (`GroupedSourcesChoice`, shared with the library Remove dialog), ticked by default. Removal keeps the membership row so a re-add rejoins; Manage sources' remove also splits the source out.

### Backup

Groups are written as lists of stable `{url, source}` refs (field 711 manga, 702 novels), since ids change on restore, and every backup carries field 718 to say all groups are stored. `RestoreMergeGroups` resolves the refs and writes each group with `materializeGroup` against one snapshot of local membership, so the result does not depend on order. The backup is authoritative for the entries it names; local members it does not name keep their own group while at least two remain. A backup without 718 is a 0.3.x one, whose same-title groups were never stored, so restore rebuilds them with the upgrade's `MergeGroupReconstruction` over the backup's favorites, its 711/702 merges, its 712/703 unmerge pairs and its own same-title switches.

## Key files

- `data/src/main/sqldelight/tachiyomi/data/merge_group.sq`: the group and member tables, `mangaFavoriteMembers` / `novelFavoriteMembers`.
- `data/src/main/sqldelight/tachiyomi/data/merged_chapter_unit.sq`: the stored stitch, `merged_group_ranking`, the stale views, `countsByGroup`.
- `domain/src/main/java/reikai/domain/merge/MergeGroupRepository.kt`: `merge`, `materializeGroup`, `replaceInGroup`, `removeFromGroup`, `setSourceOrder`.
- `data/src/main/java/reikai/data/merge/MergeGroupRepositoryImpl.kt`: the transactional writes.
- `app/src/main/java/reikai/domain/merge/EntryMergeManager.kt`: `computeRelatedIds`, `relatedIdsChanges`, `suggestGroupingOnAdd`, `seriesBuckets`; `MangaMergeManager` and `NovelMergeManager` fix the type.
- `domain/src/main/java/reikai/domain/merge/SourceRanking.kt`: `sourcePriority`, `trunkOrder`, `libraryRanking`, `coverFallbacks`, `stitchOrder`, `rankingStamps`.
- `app/src/main/java/reikai/data/coil/GroupCover.kt`: `GroupCover`, `withCoverFallbacks`, `GroupCoverInterceptor`, `FailedCovers`.
- `domain/src/main/java/reikai/domain/merge/MergedChapterOrder.kt`: `MergedChapterOrder`, `stampedReadingOrder`.
- `domain/src/main/java/reikai/domain/manga/ChapterAggregation.kt` and `app/src/main/java/reikai/domain/novel/NovelChapterAggregation.kt`: `merge`, `matchKey`.
- `app/src/main/java/reikai/domain/merge/ReconcileMergedChapters.kt`: `await`, `afterPass`, `currentStitch`; stitchers `MangaGroupStitcher.kt`, `NovelGroupStitcher.kt`.
- `app/src/main/java/reikai/domain/merge/StoredStitch.kt`: `renderStoredStitch`, `flaggedOnAnotherSource`, `MergeScope`, `CopyToOpen`, `DownloadTargets`, `collapseNewChapters`, `expandToUnits`.
- `app/src/main/java/reikai/domain/merge/GroupChapterFlags.kt`, `GroupChapterSettings.kt`, `GroupCategories.kt`, `OpenedChapter.kt` (`withOpenedChapter`), `DetailsRemoval.kt`.
- `app/src/main/java/reikai/domain/manga/MergedChapterProvider.kt` and `app/src/main/java/reikai/domain/novel/NovelMergedChapterProvider.kt`: `stitchOf`, `merged`.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/MergedChapterLoader.kt`: per-source loader routing.
- `app/src/main/java/reikai/presentation/details/EntryMergeGroupHost.kt` and `EntryMergeActionHost.kt`: the details read side and the split, remove and reorder actions.
- `app/src/main/java/reikai/presentation/details/HeaderSource.kt`: `unifiedViewMember`.
- `app/src/main/java/reikai/presentation/components/ManageMergeSourcesDialog.kt`, `MergeSourceChips.kt`, `reikai/presentation/browse/components/EntryDuplicateDialog.kt`.
- `app/src/main/java/reikai/presentation/library/MangaMergeCollapse.kt`, `novels/NovelMergeCollapse.kt`, `MergedLibraryRow.kt` (`stampMergedGroup`).
- `app/src/main/java/reikai/domain/track/GroupTrackReader.kt`, `GroupTrackerHandout.kt` (`handOutGroupTrackers`).
- `app/src/main/java/reikai/domain/merge/RestoreMergeGroups.kt`, `MergeGroupReconstruction.kt`, and `app/src/main/java/mihon/core/migration/migrations/MigrateMergePrefsToGroupsMigration.kt`.

## Invariants and traps

- **Never stitch outside the two stitchers.** A surface that pairs chapters by its own rule disagrees with the badge; the library once showed 550 chapters against a list of 541 for that reason. Read the stored stitch through a provider.
- **Never compare chapter numbers across sources.** Two sources of one series routinely number three apart. Numbers are compared only within one entry (`removeDuplicateChapters` takes an `ownerOf`, the duplicate-read pass matches numbers inside the chapter's own entry plus the stitch's copies).
- **Display reads use library members; data operations use the whole group.** Scoping `getMembers` to favorites silently drops a removed member from a restored group, and widening a display read lets a removed source feed chapters into a group it no longer shows in.
- **Group writes that pair with a favorite change commit in one transaction.** A merged entry that is not a library member feeds nothing but stays in the group, invisible and impossible to unmerge from the UI; a cancelled two-step write produced exactly that.
- **A category write to a member goes through `GroupCategories`, never the bare interactor.** Calling `SetMangaCategories` or `SetNovelCategories` directly files one member apart from its group, and the card then moves whenever the lead changes. Migration and restore still write one entry's own categories; see Decisions.
- **The tracker hand-out runs before the unfavorite.** It skips non-favorites, so run afterwards it misses the leaving entry.
- **A new chapter-writing path needs no hook, but it must end in a reconcile.** Staleness is found by comparing derived inputs, so wrap a new pass in `afterPass` or the group is stale until the next library update.
- **A stitch-rule change ships with a migration that clears the ranking stamps** (or adds a column with a default no row carries), so every group restitches once.
- **Manga's number pairing drops what it cannot place.** A sibling's unrecognized chapter, and one of two chapters recognizing to the same `Float`, are absent from the All view while present under their own chip. That is the design, not a bug, unless a concrete series shows otherwise.
- **The widget refreshes on new chapters, not on merges**, so a series just merged keeps one cover per source until its next chapter arrives.
- **A cover that failed stays behind the members' covers until the app restarts or that cover changes.** `FailedCovers` keys on the cover model, whose address and `lastModified` are part of it, so a refresh or a new custom cover is tried in its own place again; a lead whose site comes back does not reclaim the card in the same process. The memo is in memory only and bounded.
- **`GroupCover` has no fetcher.** Every image loader that draws one must carry `GroupCoverInterceptor`; the app has one loader, built in `App.newImageLoader`.
- **Recents rows, the widget and notifications draw their own member's cover with no fallback.** A recents row stands for the member with the latest activity, not the lead.
- **A merged chapter shows the winning copy's page progress.** Partial progress on another copy is not merged.
- **A chapter whose file sits on a copy the stitch does not show still streams when opened.** Reading the hidden copy would open a different chapter row, with its own history and position.
- **A recents row's download indicator answers for its own copy only.** It is evaluated as the row draws, so asking every copy would multiply a disk probe by the group size on each recomposition.
- **Some novel titles stay unmatched on purpose.** Normalization handles apostrophes, curly quotes and zero-width characters; a missing space (`theCold`), a hyphen against a space, and titles the sites really spell differently are left, since closing them needs fuzzy matching.
- **Sorting a chapter list "By source" follows the source's own listing**, through the stamped `sourceOrder`, never the stored chapter number.

## Decisions

- **A stored group row, two per-type member tables.** SQLite cannot foreign-key one column to two parents, and the per-type split buys real cascades, which keeps a reused id from being captured by a stale group. Void if manga and novels ever share one entry table.
- **The stitch is cached as a mapping, not as counts.** Reading never changes which chapters pair, so the mapping survives reads and counts are a live join; a cached count would be stale the moment a chapter is finished. A pure SQL count was rejected because the novel key is a Kotlin-normalized title.
- **Read means read on any source.** Showing the winning copy's own flag left chapters read elsewhere looking unread. `markDuplicateReadChapterAsRead` still writes real duplicate reads, since trackers, delete-after-read and backup read the rows.
- **Grouping is an explicit act.** Same-title matching only offers grouping at add time; a group is never formed silently, so Browse shows only a source's own details.
- **The settings owner is the first library member by stored order, not the trunk or the lead.** Both of those follow chapter counts without an override, which would move the setting when a sibling gains chapters. Storing the setting on `merge_group` was declined: the members' own columns already carry it.
- **Per-group ranking overrides the global list, never replaces it.** A group without an override follows the preferred-source list, so an upgrade changes nothing.
- **Backups carry membership only, not order or the override flag.** Restore keeps the local group's order and flag where one exists; a backup is usually older than the device, and restoring its order would wipe a ranking set since. Revisit if restoring onto a fresh device needs the ordering back.
- **The reader's scope is chosen by the entry point.** Updates and notifications are per-source events, and group scope would open a chapter its own list does not show; History and Library continue the series.
- **A 0.3.x backup is recognized by the missing field 718.** Keying on retired fields instead would miss the users who never touched merging, whose default same-title groups were never stored. A Mihon or Yokai backup also lacks 718, so it groups same-title favorites on restore.
- **A chapter's stored number is left as the source reported it.** It is not a chapter number by contract (most LN plugins assign a list index), and neither LNReader, tsundoku, Komikku nor TachiyomiSY reconciles two sources' numbering; the stitch orders by position instead. Void if sources gain a numbering contract.
- **A source sharing no chapter with the trunk goes at the end of the walk.** Ordering it against the trunk by number would bring back the cross-source comparison the stitch avoids.
- **The Updates feed lists a sibling's copy of a chapter already read on another source, shown as read.** Updates is a per-source feed (what this source published), and read state is group-wide, so the row already reads as read; hiding it would need its own rule for a copy marked unread later. Void if Updates stops being per-source.
- **A merged series' categories are group-wide, and a merge takes the settings owner's.** The library card is placed by the lead, which follows chapter counts, so members filed apart let the card move between categories after a refresh. The owner, not the lead, decides at merge time for the same reason it owns the chapter setting. Existing groups whose members already disagree are not rewritten by a migration: the next category change or merge on the group makes it agree. Void if the card stops being placed by one member.
- **Migration and restore write one entry's own categories.** A migration copies the old entry's categories, which are the group's when it agreed; a restore writes each entry's backed-up categories, so a backup taken from a group that disagreed, or one that names only some members, can leave a group disagreeing until its next category change. Revisit if divergent groups show up after restores.
- **A dead cover falls back as it loads, in the image loader, and is never stored.** A site that is down or bot-checked answers the cover address with an HTML page, which downloads fine and fails only at decode, so neither a check on the stored address nor a fetcher-level fallback sees it; an interceptor sees the decode result. A fallback in the composable was declined because it restarts from the lead each time the cover is drawn again. Writing the fallback into the database was declined because the lead's own cover would be lost the day its site recovers. `merge_group.cover_override` stays unused: the user's override is the shown member's custom cover, which its own fetcher already prefers. Void if covers stop loading through Coil.
- **An upgrade dropped manual merges of entries already out of the library.** No stored field told a once-favorited row from a browsed one, and a freed id could be reused by an unrelated entry.

## Upstream divergences

Merge patches sit in `// RK` islands in these Mihon files: `MangaViewModel` and `MangaScreen` (group host, merged chapter flow, scope), `ReaderViewModel` and `ReaderActivity` (merged list, `source_scoped`, per-chapter owner), the reader transition views (per-source downloaded badge), `LibraryViewModel` (collapse, group download and read), `MigrateMangaUseCase` (group rewrite), `RefreshTracks` (group tracks), `NotificationReceiver` (source scope), `DeleteLibraryMangaDialog`, the backup models, creator and restorers, and `SettingsAdvancedScreen`. Recorded divergences: [upstream-sync.md](../upstream-sync.md) "Deliberate divergences" (`RefreshTracks`, `MigrateMangaUseCase`, backup streaming).

## Extending

- **A new surface showing a group's chapters**: resolve the group with `computeRelatedIds`, read the stitch with the type's provider (`stitchOf` then `merged`), and answer flags with `GroupChapterFlags` in the surface's `MergeScope`. Do both types in the same change.
- **A new action on merged chapters**: expand through `expandToUnits` (or the host's `expandToGroup`) for read and bookmark; download through `DownloadTargets`.
- **A new path that writes chapters**: wrap it in `ReconcileMergedChapters.afterPass`.
- **A new path that takes entries out of the library**: go through `EntryLibraryRemoval`.
- **A change to the pairing rules**: change `MergedChapterOrder` or one aggregation, add a case to its test, and add a migration clearing that type's ranking stamps.

## Tests

Group storage and resolution: `MergeGroupRepositoryTest`, `EntryMergeManagerTest`, `StandaloneResolutionConformanceTest`, `MergeGroupReconstructionTest`, `MigrateMergePrefsToGroupsMigrationTest`. Stitch: `MergedChapterOrderTest`, `ChapterAggregationTest`, `NovelChapterAggregationTest`, `MergedStitchReconcileTest`, `MergedTrunkConformanceTest`, `MergedGroupRankingTest`, `StoredStitchTest`, `RenderMergedReadingOrderTest`. Counts: `MergedCountConformanceTest` (the stitch and the badge agree), `MergeGroupCountsConformanceTest`, `MergedUnitSetConformanceTest`. Reading and downloads: `MergedChapterFilterConformanceTest`, `CopyToOpenTest`, `MergedCopyToOpenConformanceTest`, `MergedDownloadCopyConformanceTest`, `MergedResumeDownloadedConformanceTest`, `SourceScopedDownloadAheadConformanceTest`. Settings, categories and removal: `GroupChapterSettingsConformanceTest`, `GroupCategoriesConformanceTest`, `MergedChapterSettingsConformanceTest`, `DetailsRemovalConformanceTest`, `EntryLibraryRemovalConformanceTest`, `AddToGroupConformanceTest`. Library: `MangaMergeCollapseTest`, `NovelMergeCollapseTest`, `MergedLibraryRowConformanceTest` (the cover's fallback order on both types too). Cover: `CoverFallbacksTest`, `FailedCoversTest`. Backup: `RestoreMergeGroupsTest`, `RestorePrefEraGroupsConformanceTest`, `PrefEraRestoreConformanceTest`, `MangaMergeBackupRoundTripTest`, `NovelBackupRoundTripTest`. Hosts: `EntryMergeGroupHostTest`, `EntryMergeActionHostTest`.

Three cases have no UI path to the state they test and rest on their unit tests alone: the download badge counting a chapter two sources both hold (the app refuses to download a unit the group already has), the bookmark read-back from a single source (bookmark writes reach every copy), and an update run's announcement and queue collapse (it needs a source to publish).

Run one class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`, or `:data:test` / `:domain:test` for the classes under those modules.

## Related

- User doc: [multi-source.md](../../multi-source.md).
- Chapter-number corrections, which restitch a group: [details.md](details.md).
