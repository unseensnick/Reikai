# Novel cross-source merge

Fold same-title light novels saved from several sources into one library card with a unified chapter list stitched across sources, a per-source switcher, refresh across all grouped sources, a preferred-source ranking, and manage-sources split/dissolve, the novel twin of the manga merge engine.

This is the developer-facing record for P5 S8 (cross-source novel merge). The user-facing grouping/source-switcher behavior is shared with manga and documented in [multi-source.md](../../multi-source.md); this doc does not repeat it. The shared engine and the manga side are in [manga-merge-engine.md](manga-merge-engine.md); the group-aware tracker propagation referenced here is in [novel-tracking.md](novel-tracking.md). This doc explains how the novel side works and where it deliberately diverges from manga.

## Goal

Give the library one card per novel even when the same title is favorited from several sources, and make that card read like a single novel: one chapter list pooled across sources, a per-source switcher to drill into one source, pull-to-refresh that checks every grouped source, and a manage-sources action to split a source back out or dissolve the group. Let the user rank novel sources so a trusted source leads the stitched list.

## Why

A reader often has the same light novel from several sources at once: one has cleaner text, another is further ahead, a third kept the early chapters the others dropped. Without grouping, the library shows that novel three times, each card a separate partial chapter list. Novel merge collapses those into one entry that shows the union of chapters across sources, lets you switch to any one source, and refreshes them all together. The preferred-source ranking biases the stitched list toward the source you trust without hand-managing each series.

Mechanically this is the novel analogue of the manga merge that already shipped (P4), so most of the work was "do for novels what the manga details path already does," re-typed onto the immutable novel models. The genuinely novel-specific parts are how chapters are matched across sources and how pagination interacts with a pooled list, covered below.

## Approach

### Shared engine, novel-specific manager

A merge group is not a database row. It is computed on the fly from preference sets, exactly as on the manga side:

- **Manual merges** (`novelManualMerges`): a set of strings, each a comma-joined sorted list of novel ids the user merged.
- **Manual unmerges** (`novelManualUnmerges`): normalized `"min,max"` id pairs the user split apart.
- **Auto same-title** (`novelAutoMergeSameTitle`): a toggle. When on, favorited novels with the same case-insensitive title auto-group, unless an unmerge pair forbids it.

So a novel's group is its manual-merge members, plus same-title favorites (if auto is on), minus any unmerged pair. Storing groups as preferences means no schema and no migration. For backup, the id-based group prefs are serialized as stable `{url, source}` refs and rebuilt on restore, since the local ids change on a fresh install (see [novel-backup.md](novel-backup.md)).

The pure set math is the **shared `MergeGroupAlgebra`** (`computeGroupIds` / `computeMerge` / `computeSplit` / `computeDissolve` plus the collapse parsers), operating only on `Long` ids and `Set<String>` pref encodings so it carries no domain types and is reused by both manga and novels verbatim. `NovelMergeManager` wraps it with the novel concerns: reading/writing the novel prefs, resolving same-title favorites, and the **same-title author guard**.

The one novel-specific group rule is that author guard (`novelAutoMergeRequireAuthor`): when on, two same-title novels auto-group only if their normalized authors also match and are non-blank. This stands in for manga's tracker-based healing (manga drops a wrongly-grouped sibling when tracker keys disagree; novel tracking arrived later and the author guard is the cheaper, metadata-only equivalent). The guard is re-applied on every resolution, so a metadata refresh that diverges an author drops that member automatically. Manual merges are never author-filtered.

### Novel library collapse (one card per group)

The library collapses *after* loading favorites, at the grouping layer, not in SQL. `NovelMergeCollapse.collapse` buckets favorites by manual merge key (a sorted comma-joined id group) or, for auto-merge, by title (plus author when the guard is on), splits each bucket wherever an unmerge pair forbids grouping (via the shared `splitByUnmergedPairs`), and emits one `CollapsedNovel` per group. The representative is the most-chapters member (ties broken by earliest `dateAdded`), and a merged entry's "last read" rolls up to the group max so reading any source bubbles the whole group up the Last-Read sort. The summed download count and member ids drive the badge.

### Cross-source chapter pooling

`NovelChapterAggregation.aggregate` stitches each grouped novel's chapters into one unified list. It is pure and stateless (unit-tested in isolation):

1. **Pick the trunk** = the preferred source if one is in the group, else the source with the **most chapters**. (Manga counts *distinct recognized numbers* to defeat scanlator-duplicate rows; novels have no scanlator variants, so raw chapter count is the right measure.)
2. **Keep every trunk chapter.** Each row is a distinct chapter; there is no intra-source collapse.
3. **Gap-fill** a sibling chapter only when its match key is new; a sibling chapter with no usable key is dropped (it can't be matched safely).

Each returned chapter keeps its own `novelId`, so the reader and downloader resolve a chapter to its origin source. If the trunk has no usable keys at all, the aggregator just returns the trunk unchanged (no reliable matching is possible).

**The match key is title-first, the main divergence from manga.** Manga matches by recognized chapter number alone. Novels can't: their numbers disagree across sources far more often (a prologue offsets everything by one), and MTL sources often ship title-only chapters with no number at all. So `matchKey` uses the **normalized title** when the name has real words ("Chapter 1 - Surviving Just to Die" and "0 Surviving Just to Die" both reduce to `t:surviving just to die`, stripping label words like chapter/vol/part, standalone numbers, and punctuation), and falls back to the recognized number (`n:<chapterNumber>`, guarded `> 0.0`) for numeric-only names, returning null when neither applies. This same key also drives read/bookmark propagation across the grouped sources.

### Preferred-source ranking (shared setting, Manga / Novels tabs)

The preferred-sources screen (`reikai.presentation.library.preferredsources`) has a Manga tab and a Novels tab over one neutral ranked-list UI. The novel tab ranks installed novel sources and persists to `preferredNovelSources`. Because novel source ids are **Strings** (manga ids are `Long`), the pref stores an ordered list of String ids and the aggregator reads it as `preferredSourceIds`: a ranked source wins the trunk regardless of chapter count; with the list empty every source ranks `MAX_VALUE` and the trunk falls back to chapter count, so default behavior is unchanged. Ranking is global, not per-novel (KISS).

### Source switcher, refresh, and manage sources (details screen)

On the novel details screen (`NovelDetailsScreenModel`), once the anchor's DB id resolves, `computeRelatedNovelIds(id, title, author)` drives a `relatedNovelIds` flow; the chips and chapter list react to it:

- **Chips** (`NovelMergeSourceChips`): an "All" chip plus one per grouped source. The model builds a `NovelMergeSourceInfo(novelId, sourceName, isCurrent)` per member. Tap selects a source; long-press splits it out (confirmed). Renders nothing for a single source.
- **Chapter list:** group of 1 keeps the existing single-source per-page path untouched. Group > 1 with no source selected shows the **unified** pooled list (`aggregate` fed each sibling's chapters, the per-novel source ids, and the parsed `preferredNovelSources`). Group > 1 with a source selected shows that one sibling's own (paginated) chapter list.
- **Chapter tap** opens the reader with the chapter's owning `novelId`, so a gap-filled sibling chapter opens from its own source.
- **Refresh:** `refresh()` fetches every grouped source (the anchor first, then each member), not just the anchor, reusing the bounded `refreshNovelFromSource` path; the unified flow re-emits as each sibling's rows update.

**Pagination decision (the only real design fork):** the unified view pools whatever chapters are in the DB across all siblings and shows no page bar, because pages don't align across sources so a unified page bar is meaningless. The per-source view keeps its own pagination. Tradeoff: a paginated source that has only been partially fetched contributes only its fetched chapters to the unified view until a refresh-all fills the rest. Single-page sources (the majority) are always complete.

### Split, remove, and "split all"

`NovelManageSourcesDialog` lists each grouped source with its chapter count (a coverage hint, so the user can see which source is most complete before pruning) and offers split, split-and-remove-from-library, or remove-the-whole-group, each with Undo restoring the previous `relatedNovelIds` and the merge/unmerge prefs.

The split-vs-dissolve decision is merge-engine semantics, so it lives in the manager, not the screen. `removeFromGroup` does a subset split (survivors stay grouped) via `computeSplit`. `splitOrDissolve` routes a whole-group selection (no survivor) to `computeDissolve` instead, so "split all sources" actually dissolves the group rather than silently no-opping (the manga side has the same fix). The library bulk "Unmerge" uses `unmergeNovels`, which dissolves each target's full group in one pass including same-title auto-grouped members so nothing regroups on the next resolution.

## Key files

Confirmed present on `design/mihon-rebase`:

- [`app/src/main/java/reikai/domain/MergeGroupAlgebra.kt`](../../../app/src/main/java/reikai/domain/MergeGroupAlgebra.kt): the pure group set-algebra + collapse parsers, shared with manga.
- [`app/src/main/java/reikai/domain/novel/NovelMergeManager.kt`](../../../app/src/main/java/reikai/domain/novel/NovelMergeManager.kt): novel merge/split/dissolve, same-title resolution, the author guard, `splitOrDissolve`, `relatedNovelIdsFor`, and `seriesGroupKeys` for the Updates group-by-series.
- [`app/src/main/java/reikai/domain/novel/NovelChapterAggregation.kt`](../../../app/src/main/java/reikai/domain/novel/NovelChapterAggregation.kt): the title-first cross-source chapter stitcher (trunk pick + gap-fill + `matchKey`).
- [`app/src/main/java/reikai/presentation/library/novels/NovelMergeCollapse.kt`](../../../app/src/main/java/reikai/presentation/library/novels/NovelMergeCollapse.kt): one library card per group at the grouping layer.
- [`app/src/main/java/reikai/presentation/novel/details/NovelMergeSourceChips.kt`](../../../app/src/main/java/reikai/presentation/novel/details/NovelMergeSourceChips.kt): the source-switcher chips.
- [`app/src/main/java/reikai/presentation/novel/details/NovelManageSourcesDialog.kt`](../../../app/src/main/java/reikai/presentation/novel/details/NovelManageSourcesDialog.kt): the split / remove / dissolve dialog with per-source chapter counts.
- [`app/src/main/java/reikai/presentation/novel/details/NovelDetailsScreenModel.kt`](../../../app/src/main/java/reikai/presentation/novel/details/NovelDetailsScreenModel.kt): `relatedNovelIds` / `selectedSourceNovelId` flows, the unified-vs-per-source chapter pipeline, `NovelMergeSourceInfo`, refresh-all, and the split/remove actions.
- `app/src/main/java/reikai/presentation/library/preferredsources/`: the shared preferred-source screen: `PreferredSourcesScreen` (Manga / Novels tabs), `PreferredSourcesContent`, `PreferredSourcesScreenModel` (manga), `NovelPreferredSourcesScreenModel` (novel).
- [`app/src/main/java/reikai/domain/library/ReikaiLibraryPreferences.kt`](../../../app/src/main/java/reikai/domain/library/ReikaiLibraryPreferences.kt): the novel merge prefs (`novelManualMerges`, `novelManualUnmerges`, `novelAutoMergeSameTitle`, `novelAutoMergeRequireAuthor`, `preferredNovelSources`).
- [`app/src/main/java/reikai/domain/novel/track/PropagateNovelTrackerLinks.kt`](../../../app/src/main/java/reikai/domain/novel/track/PropagateNovelTrackerLinks.kt): group-aware tracker propagation (detail in [novel-tracking.md](novel-tracking.md)); the details screen runs it before a split so each surviving source keeps its tracker.

Tests: [`NovelChapterAggregationTest`](../../../app/src/test/java/reikai/domain/novel/NovelChapterAggregationTest.kt), [`NovelMergeManagerTest`](../../../app/src/test/java/reikai/domain/novel/NovelMergeManagerTest.kt), plus the shared [`MergeGroupAlgebraTest`](../../../app/src/test/java/reikai/domain/MergeGroupAlgebraTest.kt).

## Status

Shipped. P5 S8 is done and on-device verified (Roadmap P5 core sequence, marked done in `ROADMAP.md`). The Tier 0/1 cleanup pass extracted the group set-algebra and collapse parsers into `MergeGroupAlgebra` so manga and novels share one implementation.

## Decisions & tradeoffs

- **Shared algebra with manga.** The group set math is identical for both content types, so it lives once in `MergeGroupAlgebra` (pure, heavily tested) and each manager adds only its own concern (manga: tracker healing; novel: the author guard). No duplicated set logic and no schema; for backup, groups are serialized as `{url, source}` refs and rebuilt on restore (see [novel-backup.md](novel-backup.md)).
- **Title-first matching, not number-first.** Forced by novels: chapter numbers disagree across sources far more than manga's, and title-only MTL chapters have no number at all. The normalized-title key is the chosen middle ground; no fuzzy/edit-distance matching (a normalized exact match is predictable and cheap). The label-word stripper is English-only (deferred i18n; most LN sources are English).
- **Most-chapters trunk, not distinct-count.** Manga counts distinct recognized numbers to defeat scanlator-duplicate rows. Novels have no scanlator variants, so raw chapter count is both correct and simpler.
- **Group-aware tracking is owned, so one row not copy-per-member.** Rather than mirroring a `manga_sync`-style row onto every member (the manga copy-on-write approach), the novel side resolves the whole group through `NovelMergeManager` and keeps a single tracker binding the group shares. Before a split, the details screen runs `PropagateNovelTrackerLinks.distribute` so each surviving source still carries the tracker afterward. (Full rationale in [novel-tracking.md](novel-tracking.md).)
- **Pooled view has no page bar; per-source view keeps pagination.** Pages don't align across sources, so a unified page bar is meaningless. A partially-fetched paginated source contributes only its fetched chapters to the pooled view until a refresh-all; single-page sources (the majority) are always complete. This keeps the lazy-pagination work intact.
- **"Split all" dissolves, never no-ops.** A whole-group selection has no survivor, so `computeSplit` would return null and silently do nothing. `splitOrDissolve` routes that case to `computeDissolve` instead, matching the library bulk Unmerge and the manga fix.
- **Author guard instead of tracker healing.** Novel tracking arrived after merge, so same-title mis-grouping is caught by a metadata-only author match (re-applied every resolution) rather than by comparing tracker keys. Manual merges bypass the guard entirely.
- **Net-new novel files; manga path untouched.** The chips, dialog, aggregation, manager, and collapse are their own files in `reikai.*`; the only shared edit is the preferred-sources screen restructure (tabbed Manga / Novels over one neutral list).
