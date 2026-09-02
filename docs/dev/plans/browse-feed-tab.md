# Browse Feed tab (+ saved searches)

## Goal

Give Browse an opt-in Feed tab: one row per source showing what that source has right now, either its
Latest listing or a saved search you attached to it, for manga sources and light-novel plugins alike.
Saved searches ship with it, because a feed row is defined in terms of one: a saved search is a named,
re-applyable query plus filter set for a single source, which Reikai has never had in any form.

## Why

Requested in `unseensnick/Reikai#54`, asking for Komikku's Feed tab. The issue text was AI-expanded and
named details Komikku does not have (a 20-item-per-row list, a quick NSFW toggle in search filters);
the requester confirmed the intent is a straight port of the feature as Komikku ships it.

Reikai's Browse is Sources, Extensions and Migrate plus global search, so there is no way to watch a
source you have not committed to. Updates and History are both library-scoped, and they answer "what
got a new chapter", not "what has this source got". Saved searches are the same gap from the other
side: every browse filter set is discarded when the screen closes. The 2026-07-04 Komikku parity audit
rates saved searches the top browse gap.

Saved search is a TachiyomiSY feature Komikku inherited rather than wrote. Stock Mihon has none of it,
no table, no model, no UI, confirmed by grep over `refs/mihon`, so this is a full port with no upstream
diff to lean on. `refs/tachiyomisy` is cloned as the ancestor to compare against when deciding whether
a behaviour is Komikku's own.

## Approach

### What a feed row is

Not a chapter feed. Each row is one source's listing, rendered as a horizontal card row of page one:

- No saved search attached: the source's Latest, falling back to Popular where the source has no
  latest listing.
- Saved search attached: that search's results.

A row answers "what has this source got right now", and Updates keeps owning "what got a new chapter".
One surface, the Feed tab in Browse, mixing rows from any source. Komikku also gives each source its
own feed as a landing page; Reikai does not (owner, 2026-09-02), see Decisions.

### The feed reuses the global-search engine rather than copying it

This is the decision that shapes everything else. Reikai already has the machinery a feed needs.
`GlobalSearchEngine` fans work across both content types with a per-provider concurrency limiter,
holds each row as `Loading`, `Success` or `Error`, writes each result under a single state update and
re-sorts as results land. `BrowseSearchRow` is already the neutral per-source row, carrying a
`SourceKey`, the source object opaquely, and that tri-state. `SearchResultSection` already renders such
a row as a heading over `EntrySearchCardRow`, per content type, which is exactly a Komikku feed row.

A feed differs from a global search in two ways only: the rows come from a table instead of the source
list, and the per-row verb fetches a listing instead of running a query. Komikku duplicates its search
fan-out inside `FeedScreenModel`; Reikai must not, because both halves would be Reikai-owned and the
engine-twin exemption in `.claude/rules/content-layer.md` covers only a Reikai-to-Mihon twin. The
fan-out is therefore extracted from `GlobalSearchEngine` into a kernel both callers use, and
`SearchResultSection` moves out of the global-search screen into a shared file.

### Saved searches: one table, keyed by the neutral source identity

Komikku's `saved_search.source` is an `INTEGER`, which cannot hold a light-novel plugin's slug id.
Reikai already has the answer: `SourceKey.serialize` produces a prefixed on-disk form (`manga:123`,
`novel:novelbin`) with a matching `parse`, and it is already the format the last-used-source preference
persists. The saved-search table therefore keys on a `TEXT` serialized `SourceKey`, which gives both
content types one table with no twin to keep in step. This is the first time a `SourceKey` is persisted
to SQL rather than to preferences.

`feed_saved_search` follows Komikku's shape: a source, a nullable saved-search reference with a
cascading delete, a global flag separating the Browse tab's rows from a source's own, and Komikku's
`feed_order` column.

### The filter payload is one column and two typed readers

The stored filter state is an opaque `TEXT` column as far as the table is concerned; what it contains
is a typed capability each adapter answers for, never a branch inside shared code.

- **Manga** uses the reflective serializer ported from Komikku (`FilterSerializer` plus its per-type
  serializers). It writes a JSON array positionally parallel to the source's `FilterList`, with each
  filter's values stringified alongside a class-name map so they can be re-parsed.
- **Novels** need almost nothing. The filter draft is already a `Map<String, JsonElement>` in
  `NovelBrowseState.filterValues`, and `buildOptions` already turns it into the JSON the plugin reads.
  A novel saved search stores that map.

**Both survive a source changing its filters, and getting there meant fixing the port rather than
copying it.** TachiyomiSY and Komikku both match the stored array against the live `FilterList` by
position, so a source that adds, removes or reorders a filter applies a saved value to the wrong one,
silently and with no way for a reader to notice. Reikai matches on the filter's kind and name instead,
consuming repeats in order, and leaves a filter with no match at the source's own default. The stored
payload is unchanged, so it stays readable by the encoding it came from. Two things are kept from
Komikku: the per-element catch, so one unreadable filter costs its own value rather than the search,
and the encoding itself. The novel side needed none of this, being keyed already.

The one case a name cannot separate is two filters of the same kind and name in one list, which stay
positional among themselves. That is upstream's behaviour for every filter, narrowed to the only place
it cannot be avoided.

The old Yokai implementation on `design/library-compose` (`FilterSerializer`, `FilterTypeSerializer`)
is **not** the one to port despite being ours. It has the same positional zip, plus two hazards
Komikku's lacks: it mutates the caller's `FilterList` in place, and a type mismatch throws and degrades
the whole entry to no filters at all.

### Applying a saved search

Opening a saved search means opening the catalogue with its query and filters already set, which the
catalogue cannot do today: `BrowseSourceViewModel`'s init overwrites its filters with a fresh
`source.getFilterList()` even when the incoming listing carried some, and its assisted factory takes
only a listing query string. Komikku threads a `savedSearch` id through its browse screen's constructor
and resolves it in the model. Reikai does the same, adding a nullable saved-search id to
`EntryCatalogueScreen` (Voyager-serializable) and to both models' assisted factories, with a `// RK`
fence on the manga model so a supplied search survives init.

### The preferences

Komikku ships four, all defaulting to the feature being on. Reikai ships three, the feature **opt-in**,
with the first inverted in both name and default:

| Reikai | Komikku | Reikai default | Effect |
|---|---|---|---|
| Show Feed tab | `hide_latest_tab`, inverted | off | Whether the tab exists at all |
| Feed tab position | `latest_tab_position` | off | Move Feed first, making it Browse's landing tab |
| Hide in-library entries in the feed | `feed_hide_in_library_items` | off | Feed-scoped twin of the browse setting Reikai has |

Naming the switch "Show Feed tab" rather than "Hide Feed tab" is deliberate: the summary then describes
what enabling it does, and no preference key has a default of `true` that means "off".

Komikku carries a fourth, which makes a source open on its own feed. Reikai has no per-source feed, so
it has no switch either: a source opens on its catalogue, which is where Mihon opens it.

### Tab order

Sources, Feed, Extensions, Migrate, which is Komikku's own default order, plus the position toggle that
moves Feed first. `BrowseTab` currently hardcodes `scrollToPage(1)` for the switch-to-extensions
channel, so that index moves with Extensions, and it has to be derived from the built tab list rather
than written as a second literal, because the position toggle can shift it again at runtime.

### Build order

Nine steps, in order, each landing both content types in the same commit per the write-once rule, and
each ending at a check that can fail.

1. **The data layer.** `41.sqm` plus `saved_search.sq` and `feed_saved_search.sq`, both keyed by a
   serialized `SourceKey`; repositories and interactors under `reikai.data` and `reikai.domain`
   following the `merge_group` stack; Metro annotations only, nothing added to `AppGraph`. Depends on
   nothing, unblocks everything. **Check:** a repository test on an in-memory database copying
   `MergeGroupRepositoryTest`, inserting and reading back one manga-keyed and one novel-keyed row, and
   proving the cascade removes a feed row when its saved search goes.
2. **The filter payload.** Port Komikku's serializer for manga with its per-element try/catch, add the
   novel codec, both behind one typed slot per adapter. **Check:** one test parameterized over both
   content types round-tripping a filter set, plus drift cases (a filter added, removed and reordered)
   asserting partial application rather than a throw. Verified by mutation.
3. **Save, apply and delete on the catalogue.** Toolbar chips, the create and delete dialogs, and the
   saved-search id threaded into `EntryCatalogueScreen` and both models. Depends on 1 and 2. This is
   the point saved searches ship as a feature, with no feed anywhere. **Check:** on device, save a
   filtered search on a manga source and on a novel source, leave, reopen from the chip, and confirm the
   results match the pre-save set.
4. **The preferences and their settings group.** The four switches above, in Browse settings. Depends
   on nothing; sequenced here so the feed steps have their gates to hang on. **Check:** each switch read
   from a screenshot rather than inferred from a row being present, since this settings DSL exposes no
   checked state to a UI dump.
5. **Extract the fan-out kernel.** Lift the row-filling loop out of `GlobalSearchEngine` and make
   `SearchResultSection` shared. Global search behaviour must not change. **Check:** global search's
   existing tests plus a device pass over a multi-source search, before any feed code exists.
6. **The global Feed tab.** One screen over the kernel, rows from the table, the per-row verb being
   latest-else-popular or a saved search, Komikku's cap of 20 rows, add and remove, and the tab wired
   into `BrowseTab` behind its preference with the page index derived rather than hardcoded. Depends on
   1 through 5. **Check:** on device, a feed mixing manga and novel rows, one row on a source without
   latest showing Popular rather than an empty row, and one erroring row leaving its neighbours intact.
7. **Backup and restore.** Proto fields 715 and 716, a creator and a restorer following Reikai's
   streamed backup shape, and a `BackupOptions` gate. Re-link by value, never by id. Depends on 1.
   **Check:** back up, wipe app data, restore, and confirm a manga and a novel saved search plus their
   feed rows return; separately confirm a feed row whose source is not installed restores without
   crashing.
8. **Docs.** This doc's Status, the CHANGELOG entries, the ROADMAP moves, and the two stale
   `NovelSource` KDocs corrected in passing.

## Key files

Reference implementations:

- `refs/komikku`, the port source. Data in `data/src/main/sqldelight/tachiyomi/data/saved_search.sq`
  and `feed_saved_search.sq`; domain models and interactors under `domain/.../source/`; the serializer
  at `source-api/src/commonMain/kotlin/xyz/nulldev/ts/api/http/serializer/FilterSerializer.kt` (Reikai's
  `source-api` is no longer multiplatform, so it lands under `src/main/kotlin`); UI in
  `ui/browse/feed/FeedScreenModel.kt`, `ui/browse/source/feed/SourceFeedScreenModel.kt` and the matching
  screens under `presentation/browse/`; backup in `FeedBackupCreator.kt`, `FeedRestorer.kt` and
  `BackupFeed.kt`; preferences in `UiPreferences.kt` and `SourcePreferences.kt`.
- `refs/tachiyomisy`, the ancestor. Compare against it to tell a Komikku addition from an inherited
  behaviour; `feed_order`, the reorder screens, the insert dedup and the per-element try/catch are all
  Komikku's own.
- `design/library-compose` carries the old Yokai saved-search layer. Read it for context, do not port
  it; see the filter-payload note above.

Reikai side, the files this touches:

- Identity and persistence: `reikai/domain/source/SourceKey.kt` (`serialize`, `parse`).
- The engine being extended: `reikai/presentation/browse/globalsearch/GlobalSearchEngine.kt`,
  `GlobalSearchProvider.kt`, `BrowseSearchRow.kt`, and `EntryGlobalSearchScreen.kt`'s
  `SearchResultSection`.
- The rendering leaves, already shared: `reikai/presentation/browse/EntrySearchSection.kt` and
  `EntrySearchCardRow.kt`.
- The catalogue: `reikai/presentation/browse/catalogue/EntryCatalogueScreen.kt`,
  `EntryBrowseBehavior.kt`, and the two models `BrowseSourceViewModel` (Mihon's, live and synced, needs
  an `// RK` fence) and `reikai/presentation/novel/browse/NovelBrowseViewModel.kt` (`buildOptions`,
  `filterValues`).
- The tab host: `eu/kanade/tachiyomi/ui/browse/BrowseTab.kt` (Mihon's, already `// RK` fenced).
- Backup: `data/backup/models/Backup.kt`, `create/BackupCreator.kt`, `restore/BackupRestorer.kt` and
  `create/BackupOptions.kt`, all Mihon's and all already fenced.
- The test template: `app/src/test/java/reikai/data/merge/MergeGroupRepositoryTest.kt`.

## Status

Not started. Scouted and planned 2026-09-02 against current code, replacing an earlier plan written
before the browse takeover, which had gone stale in most of its specifics.

Sized at roughly 2,000 lines plus tests, against about 2,460 in the Komikku reference, the difference
being the fan-out and row rendering Reikai already owns. Committed to 0.4.0 as a ride-along: it does
not displace either gate item (the tsundoku reader migration and Road B) and does not move the cut.

The schema version is 41 with migrations numbered through 40, so the next migration file is `41.sqm`
and the schema becomes 42. No `versionCode` bump is needed: this adds a SQLDelight migration, not a
preference migration. Komikku's `MoveLatestToFeedMigration` is deliberately not ported, since it exists
only to bridge legacy TachiyomiSY preference keys that Reikai never had.

## Decisions & tradeoffs

- **The feed is a second consumer of the search fan-out, not a second engine.** Copying Komikku's
  structure would put the same concurrency, error and ordering rules in two Reikai-owned files, which
  the content-layer rules treat as ordinary duplication with no exemption available.
- **One saved-search table for both content types, keyed by a serialized `SourceKey`.** The
  alternative, Komikku's integer source column, forces either a second novel table or a lossy id
  encoding.
- **The filter payload is a typed capability, not a nullable column pair.** Each type reads the same
  column its own way, and neither can read the other's.
- **Port Komikku's serializer, not Reikai's own older one.** Ours is on a branch and would need no
  translation, but it is strictly worse: same positional fragility, plus in-place mutation of the
  caller's filter list and total rather than partial failure on a type mismatch.
- **The positional match is fixed, not carried across** (owner, 2026-09-02: a known flaw in a feature
  being ported gets fixed rather than ported). Matching by kind and name costs nothing in stored
  format or compatibility, and it turns the two drift cases from a per-type divergence into shared
  conformance cases both halves now pass.
- **Opt-in, against Komikku's opt-out.** Every one of Komikku's switches defaults to the feature being
  on. Reikai ships the tab hidden, so installing this build changes nothing until the user asks for it.
- **"Feed" stays the name on both Browse and Recents.** Both surfaces are opt-in and default off, they
  live in different bottom-nav tabs and can never be on screen together, and the word means the same
  thing in both. They are kept apart in code by package and preference-key prefix rather than by name.
- **`feed_order` ships as a column, the reorder screen does not.** Adding the column up front is
  cheaper than migrating for it later, and the reorder UI is a Komikku addition rather than part of the
  feature. Komikku does not back that column up, so a restore flattens row order there; including it in
  the backup avoids repeating that.
- **The per-source feed is not built** (owner, 2026-09-02, after seeing it running). Komikku's version
  leads with a Latest row and a Popular row, which is what the catalogue's own chips already give you,
  and the saved searches under them are already reachable as chips on that catalogue. A source opens on
  its catalogue, as it does in Mihon. The switch that would have made a feed the front door is gone
  with it, so nothing dead ships.
- **The adult-source saved-search specialization is not ported.** Komikku's `EXHSavedSearch` exists to
  hold a deserialized filter list for the heaviest saved-search user; revisit if the adult browse path
  turns out to need it.

Part of the broader [unified-content-ui](unified-content-ui.md) initiative.
