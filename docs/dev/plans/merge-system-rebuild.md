# Merge system rebuild

## Goal

One merge system serving both manga and light novels, built around a real group identity instead of a per-call derivation. Fixes a class of bugs rather than their symptoms: silent membership corruption, lost unmerges on restore, disagreeing group definitions, and a full-library scan on every details and reader open.

## Why

The current design ([manga merge engine](manga-merge-engine.md), [novel merge](novel-merge.md)) computes group membership at read time from SharedPreferences id-pairs plus same-title favorites, and never persists the group as an entity. It works, and it bought real things: no schema, instant reversibility, and auto-merge-by-title with zero setup. But an audit on 2026-07-15 found the costs are no longer acceptable:

- **A live corruption path.** `mangas.sq` declares `_id INTEGER NOT NULL PRIMARY KEY` with no `AUTOINCREMENT`, so SQLite reuses `max(rowid)+1` after the highest-id manga is deleted, and nothing GCs merge prefs on unfavorite or delete. A stale pref pair can silently capture an unrelated new manga that inherits the freed id. `MangaMergeManager.applyMergePrefHealing` exists to mitigate exactly this, and only heals when both entries are tracked, on the same service, with disagreeing remote ids. An untracked phantom survives.
- **Restore silently destroys deliberate unmerges.** Merge groups round-trip correctly (serialized as stable `{url, source}` refs and id-remapped, `BackupMangaMerge`), but unmerges are gated by `takeIf { size >= 2 }` and resolve only against favorites. A dropped unmerge is invisible, and since `autoMergeSameTitle` defaults on, a pair the user deliberately split re-merges itself on restore. Restore also unions with local prefs and never prunes.
- **Two non-equivalent definitions of "the group".** `MergeGroupAlgebra.computeGroupIds` unions manual merges *with* same-title siblings; `MangaMergeCollapse` buckets by merge key `?:` title, so a manual merge *excludes* same-title siblings from that bucket. The library and the details screen can disagree about who is in a group.
- **A hot-path cost.** `computeRelatedMangaIds` calls `getFavorites.await()` (the whole library) plus N tracker fetches plus a preference write, uncached, on every details open and every reader open.
- **No group identity.** No id, no row, no owner. Scope cannot be expressed (opening "the group" versus "this source" is indistinguishable to the reader, which forced an Intent-flag patch that was abandoned in favour of this rebuild). A group cannot own state: no per-group preferred source, title, or reading progress. Preferences and the DB cannot commit atomically, which is why membership changes hand-roll optimistic rollback.
- **`sourceOrder` is overloaded** as a global reading-order index, a per-source column repurposed. Any list built outside the one blessed function collides scales and breaks prev/next (shipped bug, fixed in `3498c37eb`). Komikku hacks the same column; their advantage is that exactly one function ever produces the list.
- **It is roughly 1.2 systems, not 2 and not 1.** Only `MergeGroupAlgebra` (pure id math) is shared. Manager, aggregation, collapse, chips, manage-sources dialog, backup model and about eight preference keys are duplicated per content type, against the standing unified-content goal. Novels are the weaker half: the novel reader trusts a caller-passed `orderedChapterIds`, and History and Updates pass none, so a merged novel opened from either silently degrades to one source. The two sides also restamp `sourceOrder` in opposite directions.

## Approach

Decided direction only; the design is not yet worked out and needs its own scout before planning.

Give the group a persisted identity, shared by both content types (a group row plus members, with a content-type discriminator, rather than Komikku's synthetic merged-manga row). Membership stops being derived per call, so the library scan and the two-definitions split both disappear, and FK cascade removes the stale-pref capture. Scope then falls out of identity: opening the group and opening a member are different things, with no flag. Replace the `sourceOrder` overload with a dedicated ordering key so the two scales cannot collide. Migrate the existing preference-based merges and unmerges, preserving deliberate unmerges.

## Key files (the current surface a rebuild replaces or absorbs)

- `reikai/domain/MergeGroupAlgebra.kt`: the pure set math, the one already-shared piece.
- `reikai/domain/manga/MangaMergeManager.kt`, `reikai/domain/novel/NovelMergeManager.kt`: the parallel managers, including the healing pass.
- `reikai/domain/manga/MergedChapterProvider.kt`: reader group resolve, the shared aggregate + reading-order policy.
- `reikai/presentation/library/MangaMergeCollapse.kt` (+ novel twin): the library's separate, non-equivalent bucketing.
- `reikai/domain/library/ReikaiLibraryPreferences.kt`: the merge / unmerge / auto-merge / preferred-source keys, per type.
- `data/backup/models/BackupMangaMerge.kt` (+ novel twin), `MangaRestorer`, `PreferenceRestorer`: the ref-based round-trip.
- `data/src/main/sqldelight/tachiyomi/data/mangas.sq`: the missing `AUTOINCREMENT` behind the id-reuse hazard.

## Status

**Planned.** Decided 2026-07-15 after the audit above; not started. Needs a scout, then a plan, on its own branch.

## Decisions & tradeoffs

- **Ground-up and both content types from the start**, rather than manga first. Novels are already the weaker half, and doing manga alone would entrench the duplication the unified-content goal exists to remove.
- **Not Komikku's model.** Komikku gives the group identity via a real manga row with a synthetic source plus a `merged` join table, which buys `source == MERGED_SOURCE_ID` as a single dispatch predicate. It works, and their reader-side detail does transfer (their `MergedSource.getPageList` throws, so they solved it in the reader as we do), but it costs fake rows every query must know about. A group table gives the same identity without them.
- **The source-scope patch was planned and dropped.** An Intent extra carrying the details screen's chip selection was fully scouted and would have worked, but it is a workaround for the missing identity. See [merge-aware-manga-reader.md](merge-aware-manga-reader.md).
- **Auto-merge-by-title stays**, but must become visible. It is on by default and derived from the title, so users are grouped without knowing; a reporter on unseensnick/Reikai#49 insisted they were not merged while the code would have grouped them. Whatever replaces it should be inspectable.
