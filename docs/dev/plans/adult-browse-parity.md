# Adult-source browse parity

## Goal

Bring the built-in EH / ExH browse experience to Komikku parity: paginate the gallery list
correctly (load page after page, not just the first), and render each row's captured metadata
(rating, category, page count, language, uploader, date) instead of a bare thumbnail + title.

## Why

The adult-source subsystem is a **post-rebase addition**, ported from Komikku onto the Mihon
base after the core rebase shipped (see [exh-subsystem.md](exh-subsystem.md)). That port is a
lighter slice of Komikku's, and the **browse paging + metadata layer was not fully ported**:
the EH-specific paging path was collapsed into Mihon's generic paging. The user-visible result:
browsing the built-in source loads only the first page (then "No results found"), occasionally
crashes the page on an odd row, and shows none of the rich per-row info Komikku surfaces. A
deep comparison against `refs/komikku` (2026-06-30) traced this to three missing pieces, not a
parser bug.

## Findings (what the port left out)

Komikku carries browse results through a dedicated chain that our port collapsed into Mihon's
generic one:

1. **`MetadataMangasPage` carrier (source-api).** Komikku's `genericMangaParse` returns
   `MetadataMangasPage(mangas, hasNextPage, metadata, nextKey)`, carrying both the per-gallery
   metadata list and an explicit next-page cursor (`nextKey: Long?`, the last gallery's id).
   Our `source-api` has no `MetadataMangasPage`; our `EHentai.genericMangaParse` returns a
   plain `MangasPage(mangas, hasNextPage)`, discarding both the metadata and the cursor (the
   parser still builds the metadata, it is thrown away at the return).

2. **The cursor.** The site's `next=` request parameter is a **gallery-id cursor**, not a page
   offset (both apps build the request as `exGet(url, next = page)`). Komikku feeds it the last
   gallery's id, so page 2 = `next=<lastId>` and it paginates correctly. Our generic
   `SourcePagingSource` sets `nextKey = page + 1`, so page 2 = `next=2` (galleries older than
   id 2 = none), which returns an empty / malformed page: hence "No results found" after page
   1, and that odd page is what tripped the `selectFirst(...)!!` NullPointerException surfacing
   as the "browse crash".

3. **Metadata browse rendering (presentation).** Komikku pages
   `Pair<Manga, RaisedSearchMetadata?>`, enriches it in `BrowseSourceScreenModel.combineMetadata`
   (which DB-joins via `getFlatMetadataById.subscribe(manga.id)`, preferring persisted metadata
   and falling back to the paging metadata: `flatMetadata?.raise(metaClass) ?: metadata`), and
   renders a dedicated `BrowseSourceEHentaiList` row (rating stars, genre/category badge,
   language flag, page count, uploader, date), gated behind an enhanced-view preference. We page
   bare `Manga`, have no `combineMetadata`, and render only `MangaListItem(title, cover, badge)`.

What is NOT the gap: the request / cookie building (`sl=dm_2`, `nw`, domain) is identical; the
gallery-list parser selectors are byte-identical to Komikku's; and the full filter set
(Toplists, Tags, Watched List, Genres, Advanced Options, Reverse, Jump/Seek) is already at
parity.

## Approach (two pieces, both on `feat/exh-parity`)

Key insight: pagination depends only on the **cursor** (the `Long` key), which is independent
of the paging *element* type. So the pagination fix (A) is small and isolated; the element-type
change that the rich rows need (B) is separate.

### A. Pagination + metadata carrier (small, no blast radius)

Element type stays `Manga`; the browse UI is untouched. ~3 files:

- `source-api/.../model/MangasPage.kt`: make `MangasPage` `open` (open vals) and add a
  `MetadataMangasPage(mangas, hasNextPage, mangasMetadata: List<RaisedSearchMetadata>, nextKey: Long?)`
  subclass. `RaisedSearchMetadata` is already in `source-api/commonMain`, so this is in-module.
  Fenced `// RK`.
- `app/.../source/online/all/EHentai.kt` (`genericMangaParse`): return `MetadataMangasPage`
  carrying `nextPage` (the gallery-id cursor it already computes) and `parsedManga.map { it.metadata }`
  (`EHentaiSearchMetadata : RaisedSearchMetadata`; `List` is covariant, so no cast).
- `data/.../source/SourcePagingSource.kt` (base `load`): read the cursor off the carrier,
  `nextKey = (mangasPage as? MetadataMangasPage)?.nextKey ?: if (mangasPage.hasNextPage) page + 1 else null`.
  Fenced `// RK`. Only EH returns a `MetadataMangasPage`, so every other source is unchanged.

A fixes the "loads only page 1" bug and removes the crash at its root (the malformed `next=2`
page never loads). A does NOT need a dedicated `EHentaiPagingSource` or `isEhBasedSource()`
routing, those are only needed for B's metadata pairing. The metadata rides along in the
carrier, unused until B.

### B. Rich browse rendering (the element-type change)

- Change `typealias SourcePagingSource = PagingSource<Long, Manga>` to
  `PagingSource<Long, Pair<Manga, RaisedSearchMetadata?>>`
  ([SourceRepository.kt](../../../domain/src/main/java/tachiyomi/domain/source/repository/SourceRepository.kt)),
  and pair items in the base `load` (or a dedicated `EHentaiPagingSource` + `isEhBasedSource()`
  routing in `SourceRepositoryImpl`, which we'd add here since `isEhBasedSource()` does not exist
  yet, only `Manga.isEhBasedManga()`).
- Port `combineMetadata` into `BrowseSourceScreenModel` (DB-join via `GetFlatMetadataById`,
  falling back to the carried paging metadata) and destructure the pair at its
  `pagingData.map { manga -> ... }`.
- Thread the pair through the browse composables and add a `BrowseSourceEHentaiList` row
  (rating, category badge, language flag, page count, uploader, date), gated behind an
  enhanced-view preference.

Blast radius (B only): the type alias, base `load`, `BrowseSourceScreenModel`, the browse
composables (`BrowseSourceList` / `…ComfortableGrid` / `…CompactGrid`); `GetRemoteManga` and
`SourceRepositoryImpl` inherit the alias. Novels are unaffected (separate, non-paging browse).
B gets its own scout/plan before implementation.

## Key files

- Reference (Komikku): `refs/komikku/source-api/.../source/model/MangasPage.kt`
  (`MetadataMangasPage`); `refs/komikku/data/.../source/EHentaiPagingSource.kt` +
  `SourceRepositoryImpl.kt` (routing); `BrowseSourceScreenModel.combineMetadata`;
  `.../presentation/browse/components/BrowseSourceEHentaiList.kt`.
- Ours: `source-api/.../source/model/MangasPage.kt`; `app/.../source/online/all/EHentai.kt`
  (`genericMangaParse`); `data/.../source/SourcePagingSource.kt` (+ a new `EHentaiPagingSource.kt`
  for B); `domain/.../source/repository/SourceRepository.kt` (alias, B); `app/.../ui/browse/source/browse/BrowseSourceScreenModel.kt`
  and `app/.../presentation/browse/` (B).

## Status

Shipped on **`feat/exh-parity`** (the umbrella branch for bringing the adult subsystem to
Komikku parity; the branch does not open a PR until the whole subsystem is on par). Investigation
done via code-research + scout vs `refs/komikku` (2026-06-30).

- **A (pagination + metadata carrier):** done. `MetadataMangasPage` carries the gallery-id
  cursor; the built-in browse pages all the way through.
- **B1 (metadata plumbing):** done. Browse pages `Pair<Manga, RaisedSearchMetadata?>`;
  `BrowseSourceScreenModel.combineMetadata` DB-joins the persisted metadata and falls back to the
  carried paging metadata.
- **B2 (rich EH rows):** done, on-device verified. A dedicated `BrowseSourceEHentaiList` row
  (cover, title, uploader, rating stars, category badge, language flag + page count, post date)
  renders for EH/ExH sources when the `enableEnhancedEhView` pref is on (default true). The badge
  text color is contrast-derived from the badge background (readable on every genre color, in
  either theme) rather than Komikku's hardcoded per-genre black/white; the rating uses a custom
  Compose star composable instead of the `com.gowtham.ratingbar` dependency. Other adult sources
  (nhentai, pururin, ...) keep the standard grid/list, exactly as in Komikku.

A prior band-aid (skip un-parseable rows in the parser) was reverted during A: the cursor fix
makes malformed-page rows a non-event.

## Decisions & tradeoffs

- Ship A before B: A is the small, isolated cursor fix that removes the crash and restores
  "loads more content"; B is the larger element-type + presentation change on top.
- The band-aid was deliberately not kept: the cursor fix makes malformed-page rows a non-event,
  so a defensive skip is redundant once A lands (revisit only if a genuinely malformed row
  recurs under correct pagination).
- B obtains row metadata both ways, as Komikku does: carried from paging (immediate, for
  galleries not yet persisted) and DB-joined via `combineMetadata` (persisted takes precedence).
- This is the browse half of the [adult-source (EXH) subsystem](exh-subsystem.md); user-facing
  scope is in [docs/adult-sources.md](../../adult-sources.md).
