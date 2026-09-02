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
and resolves it in the model. Reikai takes a nullable saved-search id on `EntryCatalogueScreen`
(Voyager-serializable) but stops there: it is applied from a `LaunchedEffect` once the screen is up,
through the adapter's `applySearch`, so neither model needs a saved-search read and neither needed a
`// RK` fence. The cost is one discarded page of the default listing.

That effect is keyed on the saved-search list rather than on the id, because the search is not there on
the first pass, and it holds its own "already applied" flag in `rememberSaveable`. Both matter: keyed
on the chip state instead, saving a new search on that screen re-emits the list and the old search
lands back on top of what the reader was looking at, and held in a plain `remember`, any config change
(a rotation, a fold) does the same.

### Arranging the feed, and adding several at once

The tab has two modes over the same rows. **Reorder** crossfades to a compact drag list, one line per
row titled the way the feed titles it, committing the whole order when the drag settles. **Select**
swaps the shared Browse toolbar for a selection bar through `TabContent.actionModeToolbar`, and from
there a tap picks a cover and a long press previews it, which is the inversion every other browse grid
here uses. A batch spanning both content types is filed one type at a time, since each files into its
own categories.

### A row whose source is not there

A feed row outlives the source it names: an extension can be uninstalled, and a light-novel plugin is
not in the registry at all until `LnPluginInstaller.ensureLoaded` has run once. Such a row keeps its
place and renders as `EntrySearchState.Unavailable`, a state the row fill never fills, so it stays
long-pressable and removable. Dropping it instead hides a row that still counts against the cap, which
leaves the reader unable to remove it and unable to add another.

The novel half needs more than that, because "not there yet" is the common case rather than the rare
one: the model loads the plugins before its first read of the table, then follows
`NovelSourceManager.sources`, so a plugin that arrives (or leaves) later rebuilds the list. The manga
registry fills itself, so it needs neither.

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

Shipped on `feat/0.4.0`, scouted and built 2026-09-02. Saved searches: `117863ce6` the tables,
`40a794b11` the per-type filter encoding, `abc5347cc` the matching fix, `a021fcf7e` save, apply and
delete on the catalogue. The feed: `274df4eb8` the shared row fill, `aaf5827ed` the settings,
`6448ed960` the tab, `fc564f39c` three gaps a walk through Komikku's own feed turned up,
`5adf0340e` adding to the library from a cover, `90dbe8350` the per-source feed dropped,
`e68e1202e` backup and restore, `4a4f7c11b` the tab badge.

Then audited against `refs/komikku` and `refs/tachiyomisy` and completed. The audit's fixes:
`e5b178252` a saved dropdown or sort followed by its option rather than its index, `f92284c36` a
filters-only saved search applied onto a cleared query and no longer re-applying itself over the
screen, `6aa59ceb5` a row kept when its source is not there, the plugins loaded before the first read,
dedup at the repository, the cap honoured on restore, `feed_order` carried in the backup, the
in-library test moved onto the provider, and the tab built only when shown. Running it on device then
turned up two more: `0c0ef6249` the long-press dialogs acting at all, and `b6a696b63` a long press
reading the row's live state rather than what the source returned. `c106b530c` and `123bc49cf` are the
reorder mode and multi-select.

Every behavioural step was verified on device before it landed, and the backup end to end: a backup
taken on the phone restored onto a wiped emulator install brought back the saved search and all three
feed rows, re-linked to the ids the restore produced, and restoring the same file twice changed
nothing. The reorder, multi-select, the mixed-batch prompts and a config change holding a live
selection were then re-run on the foldable's wide layout, where the navigation rail sits outside the
scaffold the selection toolbar replaces. Unfolding mid-selection was watched on the device itself: the
bar reflows from the bottom navigation to the rail, and the batch comes through it whole.

**Two fixes rest on reading rather than a run**, both for want of a source that provokes them: a feed
row whose source is uninstalled rendering as unavailable, and a saved dropdown whose option list has
since grown shorter. Neither is reachable on demand.

The schema is version 42 after `41.sqm`. No `versionCode` bump was needed: this adds a SQLDelight
migration, not a preference migration. Komikku's `MoveLatestToFeedMigration` is deliberately not
ported, since it exists only to bridge legacy TachiyomiSY preference keys that Reikai never had.

**Not built, and each recorded below:** the per-source feed and its source-navigation switch, and the
adult-source saved-search specialization.

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
  feature. Komikku does not back that column up, so a restore flattens row order there; `BackupFeedRow`
  carries it as field 4 to avoid repeating that. It restores as a sort key rather than as a value,
  because `insert` assigns the next number: the sequence is what carries the order, not the number.
  (The first cut of this shipped the decision without the field, which the audit below found.)
- **The per-source feed is not built** (owner, 2026-09-02, after seeing it running). Komikku's version
  leads with a Latest row and a Popular row, which is what the catalogue's own chips already give you,
  and the saved searches under them are already reachable as chips on that catalogue. A source opens on
  its catalogue, as it does in Mihon. The switch that would have made a feed the front door is gone
  with it, so nothing dead ships.
- **The adult-source saved-search specialization is not ported.** Komikku's `EXHSavedSearch` exists to
  hold a deserialized filter list for the heaviest saved-search user; revisit if the adult browse path
  turns out to need it.
- **Reordering is a mode inside the tab, not a screen of its own.** The resolved rows live on the feed
  model, and a pushed screen gets its own `ViewModelStore`, so it would resolve every source a second
  time just to draw a list of names. The drag commits when it settles rather than per frame, because
  a write re-reads the table and that rebuild asks every source again.
- **The selection toolbar arrives through a slot on `TabContent`, not through the models.** Komikku
  passes its feed and bulk-favorite models into the shared `TabbedScreen` and branches the top bar on
  the selection alone, which leaves the bulk toolbar sitting over the sibling tabs. A nullable
  composable slot, read off the tab that is on screen, keeps the host generic and cannot leak.
- **The bottom nav stays put during a selection**, where Komikku hides it. Tied to the selection alone
  it stays hidden once you swipe to another Browse tab, which is the same leak as their toolbar, and
  nothing at the bottom of the screen conflicts with a toolbar at the top.
- **`selectionTitle` and the category prompts are shared with the global search.** Both surfaces list
  the two content types over the same row component, so how a mixed batch is named and how it is filed
  is one rule with two callers rather than a twin.
- **A row heading stays tappable while selecting, rather than going inert.** Long-pressing it no longer
  removes the row, and with no long-press handler the gesture falls through to the tap, so the heading
  opens its source. Inert was considered and declined: the arrow is still drawn, and a drawn control
  that does nothing is the silent no-op the content-layer rules forbid, so it would have to be hidden,
  which reshuffles the row on entering the mode. Nothing is lost by navigating either, since the bulk
  models sit on the Browse tab's store and the selection is still there on the way back. The global
  search behaves the same over the same component.
- **The Sources row keeps its Latest button** (owner, 2026-09-02). Komikku hides it when the feed is
  on, but only because their per-source feed replaces the catalogue and always leads with Latest for
  that source. With no per-source feed, the button is the only one-tap route to Latest for a source
  the reader never added to their feed, and the feed ships off.

- **A feed row is deduplicated at the repository, not at the caller.** Komikku dedupes on insert and
  the same rule has two callers here, the picker and a restore, so it lives inside the insert
  transaction. That is also what makes restoring a file twice a no-op on the feed rows.
- **A restore honours the row cap; `MAX_FEED_ROWS` therefore lives in the domain.** A backup is
  untrusted input and each row costs a source round trip on every open, so an over-long feed is cut
  rather than rendered. Our own backups can never exceed it, so only a hand-edited or foreign file is
  affected.
- **A saved `Select` or `Sort` is re-resolved through the option text, not the stored index.** The
  index is a position in `values`, so a source that reorders its options applies the wrong one and one
  that shortens them crashes the filter sheet, which indexes `values` unguarded. Same reasoning as the
  kind-and-name matching above, one level down. It needs an `afterDeserialize` hook on `Serializer`,
  because the reflective mapping pass writes `state` blindly after the type-specific pass runs.
- **The in-library test is a `FeedProvider` slot.** A manga carries the answer; a novel is a source
  plus a path and needs both halves. A branch on the payload type inside the shared model is what let
  the two sides disagree, so the question is asked of the provider instead.
- **The saved query is not sanitized before a feed fetch**, where Komikku calls `query.sanitize()`.
  The catalogue does not sanitize a typed query either, so sanitizing only the feed would make one
  saved search return different results in two places. Revisit only by doing both at once.
- **The add-source picker names the source language, and stops there.** Two same-named sources are
  otherwise the same entry twice. Komikku also shows an icon and sorts pinned sources first; the icon
  needs the picker to stop being a list of strings and pinned-first needs both providers to carry
  pinned state, neither worth it for a dialog opened once per added row.
- **A saved search whose filters cannot be read applies what it can, silently.** Komikku toasts and
  aborts. Ours degrades per element by design, so there is nothing to abort, and warning would mean
  threading an error channel through `applySearch` for a case only a corrupt payload reaches.
- **A novel saved search carrying a query cannot also carry filters.** A plugin's search endpoint takes
  no options, so the two cannot reach one request; a query wins and the filters stay in the draft.
  Recorded here rather than only in a code comment because the write-once rule requires the mechanism
  named in the plan doc. The catalogue and the feed apply the same rule, so the surfaces agree.

Part of the broader [unified-content-ui](unified-content-ui.md) initiative.
