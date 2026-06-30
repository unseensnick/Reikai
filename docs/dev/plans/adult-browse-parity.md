# Adult-source browse parity

## Goal

Bring the built-in EH / ExH browse experience to Komikku parity: paginate the gallery list
correctly (load page after page, not just the first), and render each row's captured metadata
(rating, category, page count, language, uploader, date) instead of a bare thumbnail + title.

## Why

Reikai's Mihon-rebase port of the adult-source subsystem dropped Komikku's EH-specific paging
and metadata-carrier layer. The user-visible result: browsing the built-in source loads only
the first page (then "No results found"), occasionally crashes the page on an odd row, and
shows none of the rich per-row info Komikku surfaces. A deep comparison against `refs/komikku`
(2026-06-30) traced this to three dropped pieces, not a parser bug.

## Findings (what the port dropped)

Komikku carries browse results through a dedicated chain that our port collapsed into Mihon's
generic one:

1. **`MetadataMangasPage` carrier (source-api).** Komikku's `genericMangaParse` returns
   `MetadataMangasPage(mangas, hasNextPage, metadata, nextKey)`, carrying both the per-gallery
   metadata list and an explicit next-page cursor (`nextKey: Long?`, the last gallery's id).
   Reikai's `source-api` has no `MetadataMangasPage`; our `EHentai.genericMangaParse` returns
   a plain `MangasPage(mangas, hasNextPage)`, discarding both the metadata and the cursor (the
   parser still builds the metadata, it is thrown away at the return).

2. **`EHentaiPagingSource` + routing (data).** Komikku's `SourceRepositoryImpl` routes
   `isEhBasedSource()` to a dedicated `EHentaiPagingSource` whose `getPageLoadResult` sets
   `nextKey = mangasPage.nextKey` (the gallery-id cursor) and pairs each `Manga` with its
   metadata (`LoadResult.Page<Long, Pair<Manga, RaisedSearchMetadata?>>`). Reikai has no
   `EHentaiPagingSource` and no such routing; every source uses the generic
   `SourcePagingSource`, whose base sets `nextKey = page + 1`.

   This is the pagination bug. The site's `next=` request parameter is a **gallery-id
   cursor**, not a page offset (both apps build the request as `exGet(url, next = page)`).
   Komikku feeds it the last gallery's id, so page 2 = `next=<lastId>` and it paginates
   correctly. Reikai feeds it `page + 1`, so page 2 = `next=2` (galleries older than id 2 =
   none), which returns an empty / malformed page: hence "No results found" after page 1, and
   that odd page is what tripped the `selectFirst(...)!!` NullPointerException surfacing as
   the "browse crash".

3. **Metadata browse rendering (presentation).** Komikku pages
   `Pair<Manga, RaisedSearchMetadata?>`, joins stored metadata in
   `BrowseSourceScreenModel.combineMetadata()`, and renders a dedicated
   `BrowseSourceEHentaiList` row (rating stars, genre/category badge, language flag, page
   count, uploader, date), gated behind an enhanced-view preference. Reikai pages bare
   `Manga`, has no `combineMetadata`, and renders only `MangaListItem(title, cover, badge)`,
   so even the metadata it does parse can never reach the row.

What is NOT the gap: the request / cookie building (`sl=dm_2`, `nw`, domain) is identical; the
gallery-list parser selectors are byte-identical to Komikku's; and the full filter set
(Toplists, Tags, Watched List, Genres, Advanced Options, Reverse, Jump/Seek) is already at
parity.

## Approach (planned, not yet built)

Two independently shippable pieces, pagination first.

- **A. Pagination + metadata carrier** (fixes "loads way more content" and removes the crash
  at its source). Add `MetadataMangasPage` to `source-api`; return it from
  `EHentai.genericMangaParse` with the metadata list and `nextKey`; add `EHentaiPagingSource`
  (plus the `isEhBasedSource()` routing in `SourceRepositoryImpl`) that reads `nextKey` as the
  cursor. Blast radius to weigh: changing the paging `LoadResult` element type to
  `Pair<Manga, RaisedSearchMetadata?>` touches the shared paging contract used by every
  source, as it does in Komikku (fenced `// SY` / `// KMK` there, `// RK` for us).
- **B. Rich browse rendering** (restores the visible per-row info). Port the `combineMetadata`
  join into `BrowseSourceScreenModel` and the `BrowseSourceEHentaiList` row composable, behind
  an enhanced-view preference, re-typed onto Mihon's immutable models.

## Key files

- Reference (Komikku): `refs/komikku/source-api/.../source/model/MangasPage.kt`
  (`MetadataMangasPage`); `refs/komikku/data/.../source/EHentaiPagingSource.kt`;
  `refs/komikku/data/.../source/SourceRepositoryImpl.kt` (routing);
  `refs/komikku/.../presentation/browse/components/BrowseSourceEHentaiList.kt`; the
  `combineMetadata` join in `BrowseSourceScreenModel`.
- Ours (to change): `source-api/.../source/model/MangasPage.kt`;
  `app/.../source/online/all/EHentai.kt` (`genericMangaParse`);
  `data/.../source/SourceRepositoryImpl.kt` plus a new `EHentaiPagingSource.kt`;
  `app/.../presentation/browse/` (screen model + list composable).

## Status

Planned. Investigation done (code-research vs `refs/komikku`, 2026-06-30); no code written. A
prior band-aid (skip un-parseable rows in the parser) was reverted: it hid the crash but left
pagination dead after page 1 and the metadata unrendered, so it did not address the real
defect.

## Decisions & tradeoffs

- Ship A before B: A is the higher-value, more contained fix and removes the crash at its
  root; B is presentation-only on top of it.
- The band-aid was deliberately not kept: the real cursor fix makes malformed-page rows a
  non-event, so the defensive skip is redundant once A lands (revisit only if a genuinely
  malformed row recurs under correct pagination).
- This is the browse half of the [adult-source (EXH) subsystem](exh-subsystem.md); the
  user-facing scope lives in [docs/adult-sources.md](../../adult-sources.md).
