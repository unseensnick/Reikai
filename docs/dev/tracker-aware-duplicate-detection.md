# Tracker-aware duplicate detection

When you add a manga to the library, Reikai checks whether you already have the same series saved under a different source or a different title, and surfaces it as a duplicate so you can merge or migrate instead of ending up with two cards. The check uses both the title and the tracker identity, so a series saved under one romanization is recognized when you add it under another.

This is the add-time duplicate check. For how same-series-different-source entries are folded into one library card at display time, see [multi-source.md](../multi-source.md); for tracker links themselves, see [tracker-sync.md](../tracker-sync.md).

## How it works

Adding a manga runs `MangaLibraryAdder.getDuplicates`, which calls the `GetDuplicateLibraryManga` interactor and runs the `getDuplicateLibraryManga` query in `mangas.sq`. The query flags an existing favorite as a duplicate of the manga being added when either of two conditions holds:

- **Title match:** the existing favorite's title contains the new manga's title (case-insensitive substring), across any source. This is the original behavior.
- **Tracker identity:** the two manga share a `(sync_id, remote_id)` row in `manga_sync`, meaning they are the same entry on the same tracker regardless of how each source titles them locally. A `track_dupes` CTE self-joins `manga_sync` on `(sync_id, remote_id)` (excluding the manga's own id) to find every other library manga bound to the same tracker entry.

The tracker half is what catches a different-romanization duplicate (for example "Boku no Hero Academia" against "My Hero Academia"): the titles never match as substrings, but if both carry the same AniList / MAL / MangaUpdates entry, they collapse to the same `(sync_id, remote_id)` and the query returns the existing card.

## Where it surfaces

`MangaLibraryAdder` is the shared add-to-library helper used by both per-source Browse and global search (it returns plain results, not a screen-specific dialog, so one implementation serves both). When `getDuplicates` returns matches, the add flow shows the duplicate confirmation ("add anyway" / cancel) before favoriting. See the global-search long-press add in [plans/novel-parity-backlog.md](plans/novel-parity-backlog.md) for the surrounding flow.

## Precondition and caveat

The tracker half only fires when the manga being added already has `manga_sync` rows. A freshly browsed manga usually has none until a tracker is bound, so:

- **`EnhancedTrackService` sources** (for example MangaDex, Komga, Suwayomi) auto-bind a tracker entry on the details visit, before the favorite tap, so the tracker half works on the add path.
- **Migration** has both the source and target manga with their tracker rows already, so it works there too.
- For a plain source with no prior tracker binding, only the title-substring half applies at add time.

## Remaining gap

The query ships, but the covering index from the upstream change (`idx_manga_sync_sync_id_remote_id` on `(sync_id, remote_id, manga_id)`) was not ported. Only `idx_manga_sync_manga_id` exists in `manga_sync.sq`. The self-join is correct without it but is not index-optimized; adding that index is a worthwhile follow-up if the duplicate check ever shows up in a profile. It would need a new additive migration.

## Key files

- `data/src/main/sqldelight/tachiyomi/data/mangas.sq`: the `getDuplicateLibraryManga` query with the `track_dupes` CTE.
- `data/src/main/sqldelight/tachiyomi/data/manga_sync.sq`: the `manga_sync` schema and its `idx_manga_sync_manga_id` index (the `(sync_id, remote_id, manga_id)` covering index is not present).
- `domain/src/main/java/tachiyomi/domain/manga/interactor/GetDuplicateLibraryManga.kt`: the interactor.
- `app/src/main/java/reikai/presentation/browse/MangaLibraryAdder.kt`: `getDuplicates` and `resolveAddFavorite`, the shared add-to-library path.

## Provenance

The tracker-identity signal follows the upstream pattern in mihonapp/mihon#2978 ("Utilize tracker for library duplicate detection"). The query landed in Reikai through the Mihon base; the covering index did not.
