# Content layer: the browse surface

## Goal

Redo the browse surface at the behaviour seam. The four multi-source lists (Sources, Extensions,
Migrate-sources and global search) become one Reikai-owned All-first engine serving both content
types, and the per-source grid becomes one shared screen over two live pagers. A browse change is
then written once, and the All chip stops being a hand-wired third rendering of lists it does not
own.

## Why

A 2026-08-02 deep research pass over both browse stacks found the UI seam largely done (the grid
cell, search cards, source-options dialog and selection toolbar are shared) and the shell collapses
deliberately declined (see [content-parity-drift-and-collapse.md](content-parity-drift-and-collapse.md)
2c and Phase 3, both marked do-not-re-flag). What remained forked was behavior: the two
bulk-favorite ScreenModels were line-for-line twins, the two library adders expose the same
favorite/duplicate/categories flow with per-type signatures, and their Remove dialogs are unshared
twins. The spine rule of [content-layer-architecture.md](content-layer-architecture.md) held
unamended for that work: no library-style takeover, verbs stay in per-type code, Mihon files stay
live.

**That verb collapse shipped and did not stop the drift, so the no-takeover ruling is overruled
(owner, 2026-08-27).** It shared what the two stacks had in common and left the All chip assembling
its own lists by hand, which is exactly the behaviour-partial shape the 2026-08-06 redo ruling was
written against. The bill came in as upstream behaviour that nothing catches going missing.
`ReikaiExtensionsTab`'s combined list flattens `ExtensionsViewModel.State.items` past its headers, so
under the All chip the manga extensions lose every section header and with it the Update all button,
pull-to-refresh, the install-permission banner and the loading and empty states; the
back-clears-search handler upstream added in mihonapp/mihon#2906 sits inside the tab content only the
Manga chip reaches. `CombinedSourcesContent` renders manga's pseudo-groups and then appends the novel
stream whole, and both providers emit their own Last used and Pinned headers, so that list shows each
of those twice. `browseContentType` defaults to `ContentType.ALL`, so the broken view is the one that
opens. The trust bug fixed in `c5c5bc0ea` was the sixth defect of this class, and like the others it
was found by accident rather than by any check.

## What shipped first: the verb collapse

Six steps, each independently shippable and device-verified before the next. This is the record of
the work that preceded the takeover, not the forward plan; the takeover section below is.

- **Step 1, the shared bulk-favorite engine (shipped `1392f58c9`).** The whole selection and
  category state machine (toggle / select-all / invert, the default-category-or-prompt decision,
  the one-shot category dialog) lives once in the generic `EntryBulkFavoriteViewModel<T>`.
  `BulkFavoriteViewModel` (manga) and `NovelBulkFavoriteViewModel` are thin facades supplying
  the selection key, the category source, the default-category preference and the add verb, so
  every call site keeps its current types. A sealed cross-type selection key was considered and
  dropped: no browse screen shows a mixed list, and the generic keeps Mihon's `selection:
  List<Manga>` parameters untouched.
- **Step 2, the shared remove dialog + default-category kernel (shipped `66dd5d007`).** The
  scouted "one neutral adder contract" narrowed on inspection: the two adders are not twins (the
  manga one returns neutral results and leaves orchestration to its callers, the novel one owns the
  long-press orchestration and returns dialogs; the carriers differ because a `NovelItem` has no
  row until insert). What was genuinely twinned now lives once: `EntryRemoveDialog` replaces
  Mihon's `RemoveMangaDialog` (deleted and manifested) and the novel twin across all five hosting
  screens, and `resolveDefaultCategoryIds` (`reikai/domain/category/`) is the one
  default-category decision tree, used by both adders and the bulk-favorite engine. Declined while
  their premises hold: the sealed-dialog-type collapse (needs a generic carrier for payloads that
  differ by construction) and extracting `seedCategoriesFromGroup` (~8 identical lines against a
  category-port interface). **The polymorphic adder decline has expired**: it rested on no shared
  caller existing, and the recents engine is one, so the add sequence is being collapsed in
  [content-layer-add-flow.md](content-layer-add-flow.md).
- **Step 3, hide-in-library for novel browse (shipped `cadf22edb`).** The novel pager filters each
  fetched page against the live favorited keys behind the same preference manga reads
  (`hideInLibraryItems`), at the same load-time snapshot semantics. One mechanic manga gets from
  Paging 3 for free is hand-built here: a page that filters down to nothing must not stall the
  manual pager (the scroll trigger only re-arms when the visible list changes), so `loadMore` loops
  to the next page until something visible lands or the catalog ends, and a fully-hidden first page
  hands off to that loop.
- **Step 4, per-language switch for novel sources (shipped `09cb80e27`).** Each language heading in
  the novel sources filter is a switch, mirroring manga's; a disabled language hides its sources
  from the Sources list and global search. Stored as a deny-list (`disabledNovelLanguages`),
  deliberately inverted from manga's enabled-set: novel languages arrive with whatever plugins the
  user installs, so a deny-list keeps every language on by default and a newly appearing language
  visible without a migration. Grounded: the LNReader registry's `lang` field is first-class (a
  fixed 16-language list).
- **Step 5, delete-and-manifest the dead tab builders (shipped `df4d6e752`).** Mihon's
  `sourcesTab()` and `migrateSourceTab()` had no callers (the Reikai chip tabs replaced them);
  both files are deleted and manifested. `extensionsTab()` stays, it is wrapped live by
  `ReikaiExtensionsTab`.
- **Step 6, one owner for where a new favorite lands.** A behaviour-seam takeover of browse was
  scouted and ruled out (see the decision below); what the evidence supported instead was the
  add-to-library verb family, which spans browse, details and history. The default-category rule
  had nine statements: one tested kernel (`resolveDefaultCategoryIds`), three helpers reading it,
  and five inline copies. The novel details screen was the copy that got it wrong, favoriting and
  then prompting whenever any category existed, so a configured default novel category never
  applied there; it now calls `NovelLibraryAdder.applyDefaultCategoryOrPrompt` like its own history
  sibling. The four surviving copies (both details screens' add and add-to-group paths, both
  history screens') now read the kernel. Each keeps its own write ordering, because manga's two
  favorite first and abandon the add when that write fails, where the adder favorites after
  resolving categories; only the rule moved, not the sequence. The add-to-group pair was inventoried
  case for case against its novel twin at the same time, which turned up the `dateAdded` gap below.
  Both adders now carry the same seven cases, each mutation-verified.

## The takeover (ruled 2026-08-27)

**Browse is two surfaces, not one, and they take opposite shapes.** The four multi-source lists
(Sources, Extensions, Migrate-sources, global search) are All-first surfaces in the library's and
recents' sense: many rows from both providers on one screen, with the chips as predicates over one
assembled list. The per-source grid is not one of them. A source is either a manga source or a novel
source, so the content type is fixed before that screen opens and All means nothing there; it takes
the details surface's shape instead, a neutral state and behaviour contract over two adapters.
Treating the grid as All-first, or the four lists as UI leaves, are the two ways to mis-plan this.

**The pager decline has expired; the filter decline holds.** Paging 3 is upstream's own mechanism
rather than a Reikai introduction: `BrowseSourceViewModel` in `refs/mihon` builds its own `Pager`,
upstream's `SourcePagingSource` is a bare typealias over androidx `PagingSource`, and the paging
artifacts are in Mihon's version catalog. Reikai only widened the element type to carry gallery
metadata. So putting novels on Paging 3 reimplements nothing of Mihon's spine, it adopts the base's
mechanism, and it deletes the hand-rolled pager that exists only because the novel side rolled one.
The filter dispatch stays split on mechanism, a typed `FilterList` against a plugin JSON schema, and
is pinned by a typed capability rather than left as a decline.

Nine steps, each independently shippable and device-verified before the next.

- **Step 1, record the ruling.** This file, the depth table in
  [content-layer.md](../../../.claude/rules/content-layer.md), and the sequencing line plus a new
  amendment in [content-layer-architecture.md](content-layer-architecture.md). The three state the
  same ruling and must not disagree, so they move together.
- **Step 2, novels onto Paging 3.** A Reikai `PagingSource` over `NovelSource.popularNovels` and
  `searchNovels`, folding the eager next-page probe into the paging key and hide-in-library into a
  paging filter, retiring the continuation loop in `NovelBrowseViewModel.loadMore`. Independent of
  every other step, and it de-risks step 8. Device-verify a short page and a first page that
  hide-in-library empties, because that loop exists for a real failure.
- **Step 3, `SourceKey` and one last used.** A sealed `SourceKey` (`Manga(Long)` / `Novel(String)`),
  the browse analogue of `EntryId`, keying every section and map on this surface. One shared
  last-used preference written by both browse entry points, replacing `lastUsedSource` and
  `lastUsedNovelSource`. No migration: each has one reader and one writer today, and the section
  refills the first time a source is opened. **On doing it, `GetEnabledSources` was patched instead**
  (`// RK`-fenced): it reads the shared key and still emits the duplicate `isUsedLast` row, which the
  manga provider consumes and the novel one now mimics, rather than the assembler owning the flag.
- **Step 4, the list engine, on Sources.** One assembler over both providers' source rows, doing the
  sectioning once: Last used, Pinned, per-language across both content types, then Other. The chips
  become predicates; loading, emptiness and every value describing the list derive in the engine over
  the active providers, which is what stops them being branch-specific again. `SourcesViewModel` and
  `NovelSourcesViewModel` stay live as providers, the way `LibraryViewModel` does.
- **Step 5, Extensions onto the engine.** One Updates-pending section with one Update all spanning
  both content types, one Installed, one Available, plus pull-to-refresh, the install-permission
  banner, the loading and empty states and the back-clears-search handler, each derived once. Five of
  the six known drops close by construction rather than by being re-added by hand.
- **Step 6, Migrate-sources onto the engine**, with one sort header sorting across both types, which
  retires the recorded no-sort-under-All carve-out rather than living with it.
- **Step 7, global search as one screen.** The engine owns the query and fans it to the providers
  (the recents departure from the library template, taken for the same reason), with one result list,
  one has-results filter, one comparator, one concurrency limiter and one same-query guard.
  `SearchViewModel` and `NovelGlobalSearchViewModel` stay live as providers. The novel entry points
  redirect; the manga ones are unchanged.
- **Step 8, the shared per-source grid.** Neutral state, a behaviour contract, and two adapters over
  the two Paging 3 pagers. Typed capability slots for filter dispatch, source settings, the enhanced
  adult view, the MangaDex extras and the Latest listing. Columns and display mode read once from the
  manga preferences.
- **Step 9, the behaviour inventory.** Every replaced surface walked end to end, each item marked
  present, deliberately dropped with its reason, or missing. Not optional here: six drops of this
  class were already found by accident rather than by a check.

### Rulings settled before starting (owner, 2026-08-27)

- **Language is the outer grouping under All, not content type.** Manga and novel sources share one
  language section, and the row carries a content-type badge to say which it is. The moment content
  type becomes the outer grouping the chips stop being predicates and become navigation again, which
  is the thing being removed. The badge is drawn only under All; under a single-type chip it is noise.
- **One last-used source across both content types**, in upstream's duplicate form: the source shows
  in the Last used section and again in its language group, as manga does today. The two forms
  disagreed (upstream duplicates the row, the novel provider pulls it out) and the manga form wins,
  the same way cosmetic differences were settled on the library settings sheet.
- **Global search is one screen**, not two sharing an engine. The three novel entry points land on
  whatever the sticky chip holds rather than forcing the Novels chip.
- **Global search is in scope**, reversing the earlier exclusion. `SearchViewModel` and everything
  under `migrate/` were excluded when browse was not being taken over; the surface holds the clearest
  duplicate-implementation evidence on this stack, so it comes in as a provider. Migration's own use
  of the smart-search engines stays out.
- **A novel source's Latest listing is a derived capability, not a gated one.** The LNReader plugin
  format declares no latest flag, only a `showLatestNovels` option, and 75 of the registry's 146
  plugins reference it at all, so an ungated chip silently returns the Popular list on roughly half
  of them. Tsundoku takes the other branch, hardcoding `supportsLatest = true` on every JS plugin and
  mapping latest onto the same call with the flag flipped, which is why their Latest tab has the same
  defect. Reikai answers instead: the installer already holds the plugin's source text when it loads
  it, so the capability is decided there. Absent means it cannot honour the option and the chip
  hides; present means assume supported. The error is asymmetric in the safe direction, since
  `showLatestNovels` is an external contract name a minifier will not rename.
- **A plugin's language is normalised to an ISO code (step 4, corrected in step 5).** An lnreader
  registry names the language in the language itself, which `scripts/languages.js` in
  `refs/lnreader-plugins` is the table for: "Español", "Русский", "中文, 汉语, 漢語", and "Multi" for
  the multi-language ones. Step 4 mapped the English names instead, which the registry emits for none
  of them except English, so 125 of the live registry's 278 entries fell through unmapped and Android
  could put no heading on them at all. `toLangCode` now inverts that table, strips the invisible
  left-to-right mark the registry prefixes Arabic with, and sends Multi to Mihon's own "all". The
  deny-list behind the Sources filter screen still keys on the raw value, so hiding a language there
  hides only the plugins declaring it that way; that is a smaller pre-existing gap, left for the
  filter screen's own step.
- **Novel browse adopts manga's display mode and column preferences.** This is one screen serving two
  content types rather than two surfaces, so the surface-scoped-settings rule does not apply, and
  novels currently ignore the column setting entirely.
- **Every Browse list orders its language sections the same way, and it is upstream's extension order
  (owner, 2026-08-27).** The two upstream surfaces disagreed: `SourcesViewModel` sorted the raw codes
  while `ExtensionsViewModel` used `LocaleHelper.comparator`, so the same two languages could swap
  places between the Sources and Extensions tabs. `compareBrowseLanguages` is now the one definition
  both sectioners call: multi-language first, then each language by its own name for itself, then
  sources declaring none. The section *sequence* is unchanged (Last used, Pinned, languages, empty
  last), so this narrows the earlier order ruling rather than reversing it.

### What is deleted and manifested

Fully replaced pure-UI Mihon files: `ExtensionsTab.kt`, both `GlobalSearchScreen.kt`,
`MigrateSourceScreen.kt`, and both `BrowseSourceScreen.kt`. Per-file upstream churn over twelve
months is 1 to 5 commits each, well inside what the migrate takeover was ruled affordable at. The row
leaves stay live and synced (`SourceItem`, `BaseSourceItem`, `ExtensionItem`, `ExtensionUiModel`), as
do every ViewModel and interactor below them.

`SourcesScreen.kt` is **not** deleted, correcting an earlier line here that listed it: `SourceItem`
lives in it and the shared list still draws manga rows through it, so it is partially collapsed and
carries an `// RK` note saying what moved out. Its `SourceOptionsDialog` went when the shared list
stopped calling it.

**Three of the four browse grid variants go too** (found on doing step 8c, correcting a line here
that counted them as row leaves). `BrowseSourceComfortableGrid`, `BrowseSourceCompactGrid` and
`BrowseSourceList` are containers, not leaves: the leaf is `EntryBrowseGridCell`, which all three
already delegated to and which both content types already shared. `BrowseSourceEHentaiList` is the
one that stays, re-typed to the neutral row, because it is a layout rather than a repeat of the
same one.

**`ExtensionsScreen.kt` is partially collapsed, not deleted** (found on doing step 5, correcting the
line above): its screen, list, pull-to-refresh, banner and loading and empty states moved out, but
`ExtensionItem` is one of the row leaves that stays, so the file keeps a live remainder and takes an
`// RK` note instead of a manifest row. `ExtensionsTab.kt` reached the bar the other way round: its
one keeper, `ExtensionUninstallConfirmation`, moved beside `ExtensionTrustDialog` in
`ExtensionsScreen.kt`, so nothing live remained and it is deleted and manifested. `ExtensionHeader`
went with the list rather than staying a leaf: both Browse lists head their sections with Reikai's
`BrowseSectionHeader`, so keeping Mihon's would be a second header component for one surface.

## Key files

- Shared engine: `reikai/presentation/browse/EntryBulkFavoriteViewModel.kt`, facades in
  `reikai/presentation/browse/BulkFavoriteViewModel.kt` and
  `reikai/presentation/novel/browse/NovelBulkFavoriteViewModel.kt`.
- Adders: `reikai/presentation/browse/MangaLibraryAdder.kt`,
  `reikai/presentation/novel/browse/NovelLibraryAdder.kt`.
- Hosts, now that the takeover has landed: `reikai/presentation/browse/catalogue/`
  `EntryCatalogueScreen.kt` and `reikai/presentation/browse/globalsearch/EntryGlobalSearchScreen.kt`,
  plus `exh/md/follows/MangaDexFollowsScreen.kt` and `reikai/presentation/migrate/flow/`
  `MigrationDeepPicker.kt`, which render the catalogue body without the shared screen.

For the takeover:

- The list engine and its assembler land in `reikai/presentation/browse/`, beside the shared chip
  holder `ReikaiBrowseViewModel` and the three tab wrappers being replaced (`ReikaiSourcesTab`,
  `ReikaiExtensionsTab`, `ReikaiMigrateSourceTab`).
- Providers that stay live and synced: `SourcesViewModel`, `ExtensionsViewModel`,
  `MigrateSourceViewModel`, `SearchViewModel`, and their novel counterparts `NovelSourcesViewModel`,
  `LnPluginManagerViewModel`, `MigrateNovelSourcesViewModel`, `NovelGlobalSearchViewModel`.
- Identity: `reikai/domain/entry/EntryId.kt` is the template for the new `SourceKey`.
- The last used source: `ReikaiSourcePreferences.lastUsedSource`, one app-state key for both content
  types. It replaced `SourcePreferences.lastUsedSource` and `NovelPreferences.lastUsedNovelSource`,
  both since deleted.
- The pagers: `BaseSourcePagingSource` in `data/.../source/SourcePagingSource.kt` is the shape the
  novel one copies, over `NovelSource` in `reikai/novel/source/`.
- The latest-capability check reads the plugin source text `LnPluginInstaller` already holds when it
  calls `LnPluginHost.loadPlugin`.

## Status

**The verb collapse shipped in full on `feat/0.4.0`**: step 1 `1392f58c9`, step 2 `66dd5d007`,
step 3 `cadf22edb`, step 4 `09cb80e27`, step 5 `df4d6e752`. Steps 1 to 5 are Fold-verified on both
content types (the add / remove round trips, the hide-in-library toggle, the language switch).

**The takeover shipped in full on `feat/0.4.0`.** All nine steps are in: the four multi-source lists
are each assembled once over two providers with the chip as a predicate, both per-source catalogues
render through one screen, and the CHANGELOG carries what a reader sees. What follows records what
each step decided or turned up, because those are the parts a later reader cannot re-derive.

Step 5 closed the five known drops by construction (section headers and Update all, pull-to-refresh,
the install-permission banner, the loading and empty states, the back-clears-search handler) and
turned up two defects neither list showed before: an lnreader repo names a language in that
language, so every non-English plugin section was headed by nothing at all, and a plugin installed
from one address while a repo offers it at another was listed twice, which one shared list turns
from a cosmetic duplicate into a duplicate-key crash. Both are fixed and mutation-verified.

**Step 6 found the two sorts disagreeing rather than merely being written twice.** Upstream's manga
sort lifts a stub above everything, in either mode, and orders names through a collator;
`sortNovelMigrateSources` did neither, so the same two rules produced different lists. One
`compareMigrateRows` now orders both types, taking upstream's rules, which is also what gives the All
view a sort header at all. Two more novel-side gaps closed with it: a gone plugin now says "Not
installed" like a manga stub, and the manga list's fetch-error snackbar, which had no listener left
after `MigrateSourceTab.kt` was deleted, is wired again.
**Step 7 replaced both search screens with one (`c8e938037`), and the content type became tabs
rather than a second chip row.** Two chip rows would have put an All chip beside an All chip meaning
different things, so `ContentTypeTabs` sits above the source filters, matching the recents strip. The
engine owns the query, the source filter, the has-results toggle, one comparator and one concurrency
limiter, and it fans each query to both providers. A selection can span both types; the categories
prompt then runs twice, labelled per type, because novel categories are a different partition of the
same table and a merged list would let a reader tick a manga category expecting it to apply to a
novel. Which prompts run is decided when the batch is dispatched, since the first one resolving
empties its own selection. Routing goes through the row's `SourceKey`, never the payload: the manga
provider stores the extension-facing `Source`, so the obvious cast compiles and throws on tap.
`novelGlobalSearchHasResults` is retired, one Mihon preference now driving the toggle for both halves.

**Step 8 built the neutral contract (8a), the two adapters (8b) and one catalogue body (8c).**
Both per-source screens now page the neutral row and render through `EntryBrowseCatalogue`,
which owns the loading, empty and fetch-error states and all three grid layouts. Writing the adapters
corrected two things the contract had wrong: a shared `refresh()` verb, which manga would have
answered with nothing, so recovering a failed source moved onto the failure state itself; and a row
carrying its neutral data and payload in separate flows, which doubled the collectors on a paging
list. Novels pick up the column preference and the empty state's Help action by construction. There
are four hosts rather than two: `MangaDexFollowsScreen` and `MigrationDeepPicker` render the body too.

**Step 8d put both catalogues on one screen.** `EntryCatalogueScreen` owns the toolbar, the listing
chips, the body, the selection bar and every dialog the neutral state can describe; each per-type
branch supplies only what nothing neutral can hold, which is the filter sheet, the source settings
and where a tap goes. `BrowseSourceToolbar` moved with it, re-typed off the manga `Source` it read
three booleans from. Both Mihon `BrowseSourceScreen.kt` files and the toolbar are deleted and
manifested; `NovelBrowseScreen.kt` is Reikai's own and just goes. The chip row now reads the same
rule on both sides: `listing == Popular` decides the highlight, where the novel screen also had to
test whether a search was running because it folded search into the same listing enum.

Two things were left alone deliberately. `MangaDexFollowsScreen` keeps its own screen rather than
folding into this one (reversing the plan's recommendation): it already shares the body, and what is
left is a toolbar with no chips and no search, so folding it would mean two flags in shared chrome
whose only job is to blank that chrome. And the manga migration target picker is still
`MigrationDeepPicker` while the novel one is this screen's `migrateForId` mode, so the pick flow is
the one fork this step leaves standing.

Verified on device: both catalogues, both filter sheets, the adult-source rows, toolbar search, genre
search from a details page, long-press add, selection, and both MangaDex filter entries (Follows
opens the follows list, Random pushes a fresh catalogue carrying its `id:` query). The novel
migrate-pick mode is the one path not walked.

**Step 8e derives the Latest capability from the plugin source.** `LnPluginHost.loadPlugin` already
holds the source text, so `derivesLatestSupport` decides there and the flag rides `LnPluginInfo` out
to `NovelSource`. A plugin that reads lnreader's `showLatestNovels` gets the chip; one that never
mentions it loses the chip rather than getting one that answers with the Popular list. Verified both
ways on device: Royal Road, Novel Fire and ReadNovelFull keep Latest, and Novel Arrow, which the
registry confirms never names the option, now shows Popular alone where it used to offer both.

The pin needed a fourth case to be worth anything. Three tests left the marker unpinned, because
truncating it to `showLatest` still matched every positive fixture; a plugin naming a same-prefix
option is what makes the full lnreader name load-bearing.

Two of the three things the takeover left standing were closed afterwards (owner, 2026-08-27): the
manga migration target picker now uses the catalogue's own pick mode, and leaving the search bar
clears the search on both types rather than leaving the source, restoring what the novel screen did.
The control is the back arrow while searching, not the X beside the field, which is upstream's own
reset and only empties the text.
The follows screen keeping its own chrome is the one still open, and `ROADMAP.md` carries it.

## The behaviour inventory (step 9)

Cutting a surface over and checking it on device finds what you thought to test, so each replaced
file was walked instead: every model call and every effect it made was extracted from its
pre-takeover blob and checked off against the code that replaced it. Sources, Extensions, Migrate and
global search came through with everything accounted for, including the two things this method exists
to catch, a `Channel` nobody collects any more (the Sources fetch-error snackbar is still wired) and
an action that lost its call site (the extension trust, uninstall, install, update and WebView
actions are all still reachable). Every item not simply present is below.

**Missing, and fixed here.** The catalogue's top bar lost the empty `pointerInput` Mihon puts on it.
The grid scrolls under that bar, so without a pointer consumer a drag starting on the toolbar or the
chip row grabs the list behind them; novels never had it, and both have it now. And the light-novel
toolbar never showed the query a source was opened with: opening one from global search or from a
novel's source name searched correctly but drew the source name where the field should have been,
which the old novel screen did right and manga still did. Seeding at construction reads too early,
because the model applies its initial query only once the plugin resolves, so the adapter models the
field as untouched-or-typed and the committed query stands in until the screen types.

**Deliberately dropped, and worth a ruling.** Tapping the search field's X on a light-novel source
used to clear the query and stay; it now leaves the screen, because that is what manga and upstream
do with a query the reader typed. One of the two had to win and the takeover took manga's, but
clearing is arguably the better behaviour and both types can do it, so levelling manga up instead is
open.

**Dead, and deleted.** `BrowseSourceViewModel.getColumnsPreference` lost its last caller when the
shared body started reading the column preference itself.

## Decisions & tradeoffs

- **Superseded 2026-08-27: "No takeover, no reopened parks."** It read: the 2c body/toolbar shell
  and the generic search orchestrator stay declined; pagination (Paging 3 vs the manual probe pager),
  the filter dispatch (typed `FilterList` vs plugin JSON schema), `SearchViewModel` and everything
  under `migrate/` are out of scope. Of that list only the filter dispatch and migration's own use of
  the smart-search engines survive; the takeover section above carries what replaced the rest.
- **Superseded 2026-08-27, in full: the behaviour-seam takeover is ruled out while the two pagers
  stay apart.** Both halves have now expired. The adder half went in 2026-08-09 when the recents
  engine became the shared caller it said did not exist. The pager half went when the premise was
  re-read against current code: Paging 3 is upstream's mechanism, not Reikai's, so adopting it for
  novels reimplements no Mihon code and the spine rule never reached it. What the decline got right
  and keeps is the filter dispatch, which is a genuine contract difference. What it got wrong is
  treating low churn as making a takeover merely affordable rather than necessary: the cost of not
  doing it was six upstream behaviours silently dropped under the default chip, none of them caught
  by a check. The original text follows, kept as the record.

  Its churn figure was also wrong, and re-measuring is what showed how little the cost argument
  weighed: twelve months against the synced base is 12 commits on the source-browse paths (ten
  cross-cutting mechanical, two small UI fixes) and 16 on extensions, spread thinly enough that no
  single file exceeds 5. The original text follows, kept as the record.

  The original ruling (2026-08-09). A later plan proposed redoing browse the way migrate,
  details and library were
  redone, on the grounds that upstream churn is near zero (measured: five commits in twelve months
  across the eleven upstream browse paths, one behavioural, all already below the synced base). Low
  churn makes a takeover affordable, not necessary, and the two engines diverge exactly where a
  takeover has to bite: manga paginates through Paging 3 with a typed `FilterList`, novels through
  a hand-rolled probe pager with JSON-schema filters, both source-API-driven. Reimplementing either
  is what the spine rule forbids, and that half stands. The adder half does not. It declined a
  polymorphic adder because no shared caller existed and the takeover was the only thing that would
  have created one; the recents engine has since become one, so the decline expired with its premise
  and the sequence is collapsed in [content-layer-add-flow.md](content-layer-add-flow.md). What the
  two adders share already lives in `resolveDefaultCategoryIds` and `EntryBulkFavoriteViewModel`;
  what stays per-type is the carrier, since the manga adder returns a neutral result for its caller
  to render while the novel one owns the dialogs, and a browsed novel has no library row to favorite
  until it is materialized.
- **Parity gap closed by levelling manga up (owner-ruled).** The novel adder skips the favorite
  write when adding an already-favorited row to a group, so a grouping change does not reset
  `dateAdded` and move the entry in a date-added sort; `UpdateManga.awaitUpdateFavorite` stamps
  `dateAdded` on every write, and the manga adder had no such skip. Manga now does, and reads the
  row through `GetManga` rather than the `Manga` the caller hands it: skipping the write on a stale
  "already favorited" snapshot would merge an unfavorited entry, which is the invisible-member
  failure the atomic pair exists to prevent. The read is safe on every caller path, because browse
  and global search both put their results through `NetworkToLocalManga` before a dialog can reach
  them, and details and history operate on library rows. Nothing changes today, since no manga path
  reaches `addToGroup` with a favorited row; the value is that the two adders are now case-for-case
  twins with matching tests.
- **Parity rulings (owner, 2026-08-02): level up what the plugin format supports, gate the rest.**
  Hide-in-library and enabled-languages level up (both genuinely supportable). NSFW flagging is
  gated: the LNReader plugin format carries no nsfw field anywhere, so novel lewd filtering stays
  genre-tag-only.
- **The two installed-source loaders stay separate, pinned by a named mechanism (2026-08-16).**
  `ExtensionManager` (installed APKs, `PackageManager`, signature trust, dex class loading) and
  `LnPluginInstaller` (URLs, network download, a repo vouching for each, JS eval in the QuickJS
  host) share no stage of their mechanism, so a shared shell parameterized by two "config helpers"
  would be a bag of lambdas doing the entire job. `ExtensionManager` is also an engine file on the
  upstream sync path, which the ownership rule keeps minimally patched. What the two genuinely share
  is three sentences: the load never blocks startup, a reload cannot be overwritten by an in-flight
  load, and a failure is retried on a later trigger rather than needing a restart. The middle one is
  pinned by each side holding its full scan behind a `Mutex` (`ExtensionManager.loadMutex`,
  `LnPluginInstaller.loadMutex`), each naming the other. A conformance test is declined: pinning it
  would mean inventing seams for a `Context` and `PackageManager` on one side and the network plus
  the JS host on the other, to assert what `Mutex` already guarantees per side. Manga needed the
  mutex only once upstream moved its initial scan off the main thread
  (mihonapp/mihon#3788), which let a slow startup scan land after a re-trust and undo it.
- **Re-trusting is driven by the store list rather than by callers (2026-08-16).** Trust is judged
  against the signing keys a scan reads as it starts, so `ExtensionManager` collects
  `GetExtensionStores.subscribe()`, maps it to that key set and re-scans whenever the set changes,
  dropping the first emission (the list the startup scan already saw). Both explicit callers, the
  repo-add screen and the backup restorer, are gone with it, so a repo arriving from any path,
  including one nobody has wired yet, re-trusts on its own. The manual "Re-check extensions" lever
  stays for what that flow cannot see: a scan that failed transiently, or a package change the
  install receiver missed. Novels have no counterpart to build, because a plugin's trust is a repo
  vouching for its URL, checked at load time rather than cached from a signature.
- **The All chip re-wires the manga row's clicks, so it owes Mihon's row dialogs with them.**
  Mihon's extension list owns its own scroll container, so the unified list cannot nest it and
  hand-wires the same click lambdas over a shared `ExtensionItem`. It shipped without the trust
  prompt and the private-uninstall confirmation, and the trust half did nothing observable at all:
  an untrusted row was routed to the extension details screen, which resolves only
  `installedExtensionsFlow` and so pops straight back off a package that is untrusted rather than
  installed. Both dialogs are now hosted by the unified list, with Mihon's two composables made
  public rather than copied. Novels need no counterpart, for the reason the ruling above gives.
- **The ROADMAP browse feature items ride after the collapse** (genre-tap-search, source-row
  polish, find-a-source search), on the shared parts, rather than landing inside this surface.
- **A row's neutral content is a derived view, never its own collector (2026-08-27, from the
  post-takeover review).** `EntryBrowseRow.content` started as `map { }.stateIn(model.viewModelScope)`
  per row. That reads as a cheap mapping and is not: single-argument `stateIn` starts eagerly and
  ends only with the ViewModel, the mapping sits downstream of `cachedIn`, and each fresh collection
  of the pager rebuilds the whole set, so a browsing session accumulated one live collector per
  result ever loaded. `mapState` in `EntryBrowseRow.kt` returns a view that launches nothing, so the
  only collector is the cell drawing the row. Anything derived per row belongs on that rung.
- **The display mode has to travel in each model's state.** Upstream keeps it as a Compose value the
  screen reads from composition; the shared catalogue renders from a flow instead, which cannot
  observe one, so picking a layout wrote a preference nothing re-read. `trackDisplayMode` is the
  kernel both models call, so neither half can grow the bug back alone.
- **A global search is scoped by where it was started from (owner, 2026-08-27).** It read the Browse
  chip, so searching from a manga while Browse was left on Novels returned nothing with no
  explanation. The scope is an assisted value on the engine now; a scoped search does not write the
  Browse chip back, and only Browse's own search still opens on it.
- **A multi-source list shows each half as it lands (owner, 2026-08-27).** The shared loading flag
  was true while any provider was still null, so a slow plugin repo held back manga rows that were
  ready. It is now true only while every active provider is, with `hasPending` keeping a half still
  on its way from reading as "nothing found".
- **A verb that recovers its payload from the live dialog is broken by the dialog closing.** Found
  after the takeover, by pressing buttons rather than reading code. `EntryDuplicateDialog`,
  `EntryRemoveDialog` and Mihon's `ChangeCategoryDialog` all call `onDismissRequest()` before the
  action, so every catalogue verb that read `model.state.value.dialog` for its entry found null and
  returned: migrating onto a duplicate, adding anyway, joining a group, removing, and setting
  categories, on both content types. The two adapters now hold the dialog they mapped. Global search
  was never affected, because its callbacks capture the dialog in the composable.
- **An inventory item counts as present when it has been pressed, not when it has been traced.** Six
  review agents and the step 9 inventory both walked those five verbs and marked them present; the
  call chains exist and read correctly, and the break was ordering between a UI leaf and an adapter.
  A surface whose verbs route through an adapter needs a device pass, not a reading pass.
