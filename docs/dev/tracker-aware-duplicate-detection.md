# Tracker-aware duplicate detection (future feature)

**Status:** not started — captured as a future-feature note after a komikku-update review on 2026-05-12. Read this first if you're considering picking it up.

## TL;DR

Reikai can today only auto-detect that a new manga is "already in your library on another source" when titles match exactly. Same series with different romanizations (e.g. "Boku no Hero Academia" vs "My Hero Academia") still requires a manual merge. This note describes a tracker-identity-based detection that closes that gap, why it isn't a clean port from upstream, and the questions to answer before building it.

## The gap

The library already merges multi-source duplicates at display time — see [`docs/multi-source.md`](multi-source.md). Auto-grouping is title-based; manual merge handles everything else. The remaining friction:

- User has a series tracked on AniList/MAL/MU under one title.
- User browses a different source that publishes the same series under a different romanization.
- User taps **Add to library**.
- Reikai's [`findDuplicateFavorite`](../data/src/commonMain/sqldelight/tachiyomi/data/mangas.sq) compares `lower(title)` exactly and across-source, finds nothing, adds silently. Result: two unmerged library cards for the same series until the user notices and runs a manual merge.

## The signal

Two manga that share a `(sync_id, remote_id)` row in `manga_sync` are, by definition, the same series on the same tracker — regardless of how each source titles it locally. The tracker `remote_id` is canonical. Komikku adopted this as their library-duplicate detection signal in **mihon#2978** ("Utilize tracker for library duplicate detection", upstream commit `89bbdb17fb`, cherry-picked into komikku as `e50767709a` on 2026-02-21).

Komikku's SQL change is small — a CTE in `getDuplicateLibraryManga` that JOINs `manga_sync` against itself on `(sync_id, remote_id)`, plus a covering index `idx_manga_sync_sync_id_remote_id (sync_id, remote_id, manga_id)`.

## Why it isn't a copy-paste port

Three blockers, in increasing order of weight:

### 1. Schema is reachable but the query shape differs

Reikai's [`findDuplicateFavorite`](../data/src/commonMain/sqldelight/tachiyomi/data/mangas.sq) takes `(title, source)`, not `:id`. Komikku's `getDuplicateLibraryManga` takes `:id`. Adding the tracker JOIN requires changing the query signature, the [`MangaRepository`](../app/src/main/java/yokai/domain/manga/MangaRepository.kt) / [`GetManga`](../app/src/main/java/yokai/domain/manga/interactor/GetManga.kt) interactor, and the call site in [`MangaExtensions.kt:169`](../app/src/main/java/eu/kanade/tachiyomi/util/MangaExtensions.kt) — not just the `.sq` file.

### 2. The new manga has no `manga_sync` rows pre-add

The JOIN's premise is that the manga being checked already has tracker rows so it can match against library mangas with the same `(sync_id, remote_id)`. In Y2K's add-from-browse flow, the new manga is typically freshly inserted into `mangas` with `favorite = 0` and no tracker bindings. The JOIN returns nothing.

There are two real-world cases where tracker rows *do* exist pre-add:

- **`EnhancedTrackService` sources** (MangaDex, Komga, Suwayomi) — these auto-bind tracker entries on manga details visit, before the favorite tap. This is the realistic happy path for any port.
- **Migration flows** — both source and target manga already have their tracker rows; the JOIN works as written.

For non-enhanced sources without prior tracker binding, the feature won't trigger at add time. That's the audience to be honest with yourself about.

### 3. Multi-source grouping already does something stronger at a different layer

[`LibraryPresenter.applySourceGrouping()`](../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryPresenter.kt) **merges** same-manga-different-source library entries into a single card, with source-switcher chips and bulk operations. Mihon#2978 only **prompts** the user — "this looks like a duplicate, do you want to migrate / cancel / add anyway?". For the same-title case, Y2K's auto-grouping is strictly more useful than mihon's prompt.

The interesting framing for Y2K, then, is probably **not** a 1:1 port of mihon's add-time dialog but rather:

> Extend `applySourceGrouping` to consider `(sync_id, remote_id)` identity alongside title equality, so different-romanization same-tracker mangas merge automatically.

That moves the value from "extra prompt during add" to "the library card collapses correctly without the user noticing." Possibly the better Y2K-shaped framing.

## Reference

- Komikku — `data/src/main/sqldelight/tachiyomi/data/mangas.sq` (the `track_dupes` CTE in `getDuplicateLibraryManga`)
- Komikku — `data/src/main/sqldelight/tachiyomi/data/manga_sync.sq` (the index)
- Komikku — `data/src/main/sqldelight/tachiyomi/migrations/45.sqm` (the migration that adds the index)
- Upstream PR: mihon/mihon#2978
- Komikku cherry-pick: `e50767709a` (2026-02-21)

## Critical files in Y2K (when picking this up)

- [`data/src/commonMain/sqldelight/tachiyomi/data/mangas.sq:43`](../data/src/commonMain/sqldelight/tachiyomi/data/mangas.sq) — `findDuplicateFavorite`
- [`data/src/commonMain/sqldelight/tachiyomi/data/manga_sync.sq`](../data/src/commonMain/sqldelight/tachiyomi/data/manga_sync.sq) — schema; will need a new index
- [`app/src/main/java/yokai/domain/manga/MangaRepository.kt`](../app/src/main/java/yokai/domain/manga/MangaRepository.kt) — repository signature
- [`app/src/main/java/yokai/domain/manga/interactor/GetManga.kt`](../app/src/main/java/yokai/domain/manga/interactor/GetManga.kt) — `awaitDuplicateFavorite` interactor
- [`app/src/main/java/eu/kanade/tachiyomi/util/MangaExtensions.kt:169`](../app/src/main/java/eu/kanade/tachiyomi/util/MangaExtensions.kt) — call site (`addOrRemoveToFavorites`)
- [`app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryPresenter.kt`](../app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryPresenter.kt) — `applySourceGrouping` (if pursuing the auto-merge framing)
- Next SQLDelight migration slot: `data/src/commonMain/sqldelight/tachiyomi/migrations/29.sqm` (Y2K is at 28; mihon is at 45)

## Open questions to answer before building

1. **Which framing?** Add-time prompt (closer to mihon#2978) or extension of auto-grouping (more Y2K-native, higher steady-state value). The two aren't mutually exclusive but pick one to start.
2. **How much of the audience benefits?** Mostly users of `EnhancedTrackService` sources. Worth a rough estimate of how often the different-romanization case actually fires for non-enhanced sources — if rarely, the auto-grouping framing is the only one worth shipping.
3. **Migration-time port first?** Migration already has both `:id`s available and is simpler to land — could ship as a small standalone change without restructuring the add-flow signature.
4. **Auto-bind coverage?** Confirm `EnhancedTrackService.match()` runs before the duplicate check on the add path, and whether it can be made to run on the auto-grouping recompute path.

## Why this isn't tied to `feat/related-mangas`

`feat/related-mangas` adds tracker-backed recommendations and a taste-profile / rerank layer. Its dedups (cross-tracker taste-profile dedup, same-manga carousel dedup) operate on **recommendation candidates** that aren't in `manga_sync` and on **cross-tracker mappings** (`Media.idMal` etc.) that `(sync_id, remote_id)` can't express. Mihon#2978's SQL pattern doesn't apply to either case.

This feature is a library concern, not a recommendations concern. Build it as its own branch.
