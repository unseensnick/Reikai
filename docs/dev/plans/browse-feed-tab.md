# Browse Feed tab (+ saved searches)

## Goal

Give Browse a Feed surface: rows of recent entries pulled straight from your sources, so you can see what a source has just added without opening each one. Requires saved searches, which Reikai does not have at all, so the two ship together.

## Why

Requested in `unseensnick/Reikai#54`, asking for Komikku's Feed tab. The issue text was AI-expanded and named details Komikku does not actually have (a 20-item-per-row list, a quick NSFW toggle in search filters); the requester confirmed the intent is a straight port of Komikku's feature.

Reikai's Browse today is source list + global search only. The nearest things to a feed are Updates (chapters of library entries) and History, both library-scoped, so there is no way to watch a source you have not committed to. Saved searches are the same shape of gap: every browse filter set is thrown away when you leave the screen.

Both items were previously parked in `ROADMAP.md` (saved searches as "low value", Feed as depending on it). The 2026-07-04 Komikku parity audit rates saved searches the top browse gap.

## Approach

### What a feed actually is

Not a chapter feed. Each feed row is one source's listing, shown as a horizontal card row of page one of the results:

- No saved search attached: the source's Latest, falling back to Popular when the source does not support latest.
- Saved search attached: that search's results.

So a row answers "what has this source got right now", and the Updates tab keeps owning "what got a new chapter". Komikku surfaces this in two places: a global Feed tab in Browse (mixing rows from any source) and a per-source Feed screen used as that source's landing page.

### Saved searches come first

A saved search is a name plus a query plus the serialized filter state for one source (`saved_search`); a feed row is a source plus an optional pointer to one of those (`feed_saved_search`). Nothing in the feature works without the first table, and the filter serialization is the fiddly part: filters are source-defined objects, so they are stored as JSON and re-applied against a freshly built `FilterList` from that source.

Saved search is a TachiyomiSY feature that Komikku inherited, not a Komikku original. Stock Mihon has none of it (no table, no model, no UI), so this is a full port rather than a diff against upstream. Reikai also once had this layer on the Yōkai side; `design/library-compose` still carries a DB + serializer implementation worth reading before writing a new one.

Komikku's own additions on top of SY: saved-search chips on the browse toolbar (with the selected chip tracking the active listing), feed row ordering (a `feed_order` column plus a reorder screen), and dedup on insert so re-saving an identical search returns the existing row.

### Suggested staging

Each stage is useful on its own, so the work can stop at any of them:

1. **Saved searches on browse.** `saved_search` table + mapper + repository + the get/insert/delete interactors, save/apply/delete from the filter sheet, chips on the browse toolbar. No feed anywhere. This alone closes the audit's top browse gap.
2. **Per-source Feed screen.** `feed_saved_search`, the source-scoped feed as a source landing surface, add-to-feed from a saved-search long-press.
3. **Global Feed tab.** The Browse-level tab, the add-source/add-search dialogs, the row cap, optional row reordering.
4. **Backup + sync.** Saved searches and feed rows in the backup proto and the restore path, so they survive a reinstall. Skipping this means a restore silently drops the whole feed.

### Reikai-specific considerations

- **Novels.** Reikai's Browse is unified across content types via the content-type chips, so a feed that only understands manga `CatalogueSource` will look broken next to them. Open question: whether LN plugins expose enough of a filter contract for a saved search to round-trip, and whether a novel feed row can fall back to Popular the same way. Decide before Stage 1 lands, since it shapes the table's source key (manga sources are `Long` ids, novel sources are `String`).
- **Merged entries.** Feed rows show raw source results, so a title already in a merge group will appear per source. Komikku only offers a hide-entries-already-in-library filter; whether that is enough here is untested.
- **Tab placement.** Komikku hangs Feed off Browse alongside Sources / Extensions / Migrate. Reikai's Browse tab is Reikai-owned, so the feed tab is an addition to that surface rather than a `// RK` island in a Mihon file.
- **Adult sources.** SY's `EXHSavedSearch` model exists because EH tag searches are the heaviest saved-search user. If the EH browse path is meant to benefit, check that its filter state serializes like a normal source's.

### Adjacent gaps from the same issue

Neither is part of the feed, both surfaced while checking it:

- **Migration source-list search.** Komikku has a debounced comma-separated search over the migrate source list (name, source name, source id); Reikai's migrate tab has content-type chips and sort toggles but no search field. Independent of everything above.
- **Quick NSFW toggle in browse.** Not a Komikku feature (they only have the Settings > Browse switch that needs a restart), so it would be net-new. `ROADMAP.md` already carries an NSFW-only filter under source-list & row polish; fold it there rather than here.

## Key files

Reference implementation in `refs/komikku`:

- Data: `data/src/main/sqldelight/tachiyomi/data/saved_search.sq`, `feed_saved_search.sq`, migrations `12.sqm` / `13.sqm` / `33.sqm`; `data/.../source/SavedSearchMapper.kt`, `SavedSearchRepositoryImpl.kt`, `FeedSavedSearchMapper.kt`, `FeedSavedSearchRepositoryImpl.kt`.
- Domain: `domain/.../source/model/SavedSearch.kt`, `EXHSavedSearch.kt`, `FeedSavedSearch.kt`, `FeedSavedSearchUpdate.kt`; the matching repositories; thirteen interactors under `domain/.../source/interactor/` (`GetSavedSearchBySourceId`, `InsertFeedSavedSearch`, `ReorderFeed`, and so on). DI in `SYDomainModule.kt`.
- UI: `ui/browse/feed/FeedTab.kt` + `FeedScreenModel.kt` (holds the `MaxFeedItems` cap), `ui/browse/source/feed/SourceFeedScreenModel.kt`, `presentation/browse/FeedScreen.kt`, `FeedOrderScreen.kt`, `ui/browse/source/browse/SavedSearchItem.kt`, plus the chip block in `BrowseSourceScreen.kt`.
- Backup/sync touch-points: `Backup.kt`, `BackupCreator.kt`, `BackupRestorer.kt`, `BackupOptions.kt`, `SyncService.kt`.

Reikai side: `ui/home/HomeScreen.kt` (tab list), the Reikai-owned browse tabs under `reikai/presentation/browse/`, `reikai/presentation/components/ContentTypeFilterChips.kt`. Prior Reikai implementation of the data layer: branch `design/library-compose`.

## Status

Not started, no committed timeline. Roughly 2,800 lines in the reference across ~35 files, weighted heavily toward UI: the data + domain core is ~700 lines of near-verbatim boilerplate, the feed screens are the real cost. Stage 1 alone is closer to 400-500 lines.

## Decisions & tradeoffs

- **Saved searches are not optional.** They were parked as low value on their own, but a feed row is defined in terms of one, so the port order is forced. Stage 1 exists so the saved-search half can ship and be judged before committing to the feed screens.
- **No per-row item cap.** Komikku's 20 is a cap on how many feed rows you may add (counted separately for the global feed and each source's feed), not items per row; rows just render whatever page one returns. Keep that shape unless there is a reason not to.
- **All sources, not just latest-capable ones.** Komikku deliberately dropped SY's `supportsLatest` filter and falls back to Popular. Worth keeping: the alternative silently hides sources from the add dialog.
- **Feed ordering is deferrable.** The `feed_order` column and reorder screen are a Komikku addition, not part of the core feature; adding the column up front is cheaper than migrating for it later, but the reorder UI can wait.
- **Backup integration is not optional in the long run.** A feed that a restore wipes is worse than no feed, so Stage 4 is a requirement of "done", not a nice-to-have.
