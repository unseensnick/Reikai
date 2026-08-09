# Content layer: the add-to-library flow

## Goal

One add-to-library flow for both content types: the duplicate check, the favorite write, the category
decision and the picker that renders them are written once and reached by every surface that can add
an entry to the library. A change to how adding works stops being something you have to remember to
make nine times.

## Why

This flow drifts faster than anything else in the content layer, because it is short enough to look
copyable and has no single owner. The evidence, as of 2026-08-09:

- **There are five write orders across twelve add paths, and manga disagrees with itself.** Manga
  details and history resolve, favorite with an abort on a failed write, then file, and an `// RK`
  comment above each states exactly that rule. Manga browse and global search go through
  `MangaLibraryAdder.resolveAddFavorite`, which files categories first and favorites second with no
  abort, while their picker confirms invert again to favorite then file. Both
  `moveMangaToCategoriesAndAddToLibrary` copies file categories and then favorite from a separate
  coroutine whose result nobody reads. `RelatedMangasBrowseViewModel` favorites then files, unchecked,
  over its own inline restatement of the default-category rule. The four novel paths are uniform:
  favorite first with the result discarded, then file or prompt. The bulk-favorite pair forks the same
  way its adders do. So the ruled order below is implemented today by two paths, and moving novels
  alone would not produce it.
- **The abort exists in two paths plus `addToGroup`.** Only manga details and history return when
  `awaitUpdateFavorite` answers false. Everywhere else the result is discarded, which is how a failed
  write leaves categories filed against a row that never entered the library. That failure is
  reachable on manga details and history today, not only on the novel history path.
- **The duplicate decision lives in three composables on manga and in one adder on novels.** Manga's
  browse, global search and MangaDex-follows screens each hold the same block inside `scope.launchIO`:
  check duplicates, then branch to remove, duplicate dialog, or add, building the dialog state
  themselves. Novels answer the same three branches once, in `NovelLibraryAdder.onLongClick`. On this
  axis novels have the better shape, and manga's copies also sit against the convention that keeps
  business logic out of composables.
- **The rule was unified but the sequence deliberately was not.** The browse surface's step 6
  (`content-layer-browse-surface.md`) collapsed nine statements of the default-category rule onto one
  kernel, `resolveDefaultCategoryIds`, and recorded that each caller keeps its own write ordering:
  "only the rule moved, not the sequence". That was the right call then and is what this plan
  finishes.
- **The picker itself is twinned across nine render sites.** `DuplicateMangaDialog` renders from five
  screens and `DuplicateNovelDialog` from four, and `HistoryTab` renders both.

**What changed since the neutral adder contract was declined.** That ruling turned on there being no
shared caller: a polymorphic adder would have been ceremony with nobody to consume it, and the
takeover that would have created one was ruled out. The recents engine is that caller. It has to
sequence an add across a feed that can hold both content types, so it cannot delegate to either
adder's orchestration and cannot invent a third. The premise the decline rested on no longer holds.

## Approach

**The ruled order (owner, 2026-08-09), for both types and every path:** resolve the category decision
as a pure read, then favorite, then file the categories. A picker defers both writes to its confirm,
so backing out of it adds nothing. Favorite-before-file is what makes the abort meaningful: a failed
write leaves nothing behind, rather than categories on a row that is not in the library.

**The one carve-out is add-time grouping**, which keeps favoriting up front on both types. Its
atomicity ruling depends on that: membership is not favorite-filtered, so a merged copy that never got
favorited feeds chapters into the group while staying invisible in the library, with nothing able to
unmerge it. `addToGroup` already reads that way on both sides and is not reopened here.

**The seam is four verbs, sequenced by the caller, not by the adder.** Resolving the categories,
favoriting, filing and building the picker payload become separate steps, so the engine (or any
screen) owns the order and the abort while each type owns only its own writes. This is what the
existing adders cannot express today: `applyDefaultCategoryOrPrompt` both decides and writes, so a
caller cannot favorite between the two halves.

**The sequence owns the duplicate check (owner, 2026-08-09), on the novel shape.** The two checks are
the same query, an id and a title answering with entries plus their chapter counts, so what actually
differs is where the decision runs. Manga moves to the novel shape first, one method per ViewModel
with the composables thinned to a call, and then the two decisions collapse into one entry point
returning a neutral outcome that each screen still renders with its own dialog until step 4. Because
`MangaDexFollowsViewModel` extends `BrowseSourceViewModel`, putting the parent on the shared path
covers two of the three manga call sites at once.

**Identity is a sealed stored-or-unstored reference, never a nullable id.** A novel is checked for
duplicates before its row exists while a manga always has one by then, so a nullable id would mean
"is this a novel?" inside shared code, which is the per-type fork the capability-slot rule forbids.
Creating the row stays inside each type's favorite verb, and the sequence takes the identity it files
categories against from what that verb returns. This is needed by the shared write sequence, not by
the decision: `decideAdd` asks each type for its own lookup, so no identity crosses that seam at all.

**Duplicate rows stay per type until the dialogs collapse.** `decideAdd` is generic over the payload
each type hands its dialog (manga the rows themselves, novels the rows plus resolved source names),
so the branch order is shared without forcing a neutral row type ahead of the component that needs
one. A neutral row lands with the shared dialog.

Sequenced so each step is independently shippable and device-verifiable:

1. **The pure kernel.** Split deciding from writing on both adders: a pure `resolveDefaultCategories`
   and a pure picker-payload builder beside the existing writer, which survives for the grouping path.
   `RelatedMangasBrowseViewModel` folds onto `resolveDefaultCategoryIds` in the same commit, since it
   restates that kernel's semantics inline and the swap is a pure read. No behaviour change, so this
   ships on compile plus unit tests.
2. **One decision, owning the duplicate check.** Manga's three composable copies thin to a call and
   the decision lands in `BrowseSourceViewModel` and `SearchViewModel`, matching the novel shape, and
   both types then run the one `decideAdd` rule. Behaviour-preserving bar three deltas recorded in
   Decisions. Both halves shipped together, because stopping after the first would have left the
   decision written twice rather than three times, which is the same fork.
3. **Both types adopt the sequence.** Manga first: the two picker confirms and the adder's direct
   branch move onto it, which closes the categories-without-favorite window on details and history and
   retires the unordered pair those confirms write today. Then the four novel paths, whose visible
   change is that dismissing the picker no longer adds. The bulk-favorite pair moves with them, since
   its shared generic owns the decision while leaving the order to each subclass. The sealed
   stored-or-unstored reference lands here, where a shared favorite verb first needs one. One
   behaviour change per type, one commit and one device pass each.
4. **One duplicate dialog.** A neutral `EntryDuplicateDialog` over a neutral row type replaces both
   components at all nine render sites. Mihon's `DuplicateMangaDialog` is deleted and manifested per
   the delete-and-manifest policy; the novel twin is deleted outright, having no upstream original.
   Source display data resolves in the adapter for both types here, levelling manga up off its
   composable-side `SourceManager` lookup, since this is the component that made the two differ.
5. **The recents add verb.** `RecentsBehavior` carries no add verb today, so this adds one, implements
   it in both adapters over the shared sequence, and unblocks the `addToLibrary` half of the recents
   surface's step 8b. The other half of 8b has shipped and was never blocked.

**Tests.** The order is a pure sequence over the four verbs, so it can be pinned without a device:
abort on a failed favorite, no writes at all when the picker is dismissed, the direct branch writing
favorite before categories, and the grouping carve-out still favoriting up front. This is the
surface's first conformance suite: one parameterized case set run against both adders rather than a
hand-written twin pair, per the pin-once ladder, each verified by mutation. `MangaLibraryAdderTest`
and `NovelLibraryAdderTest` are that twin pair today, seven matching cases each, all about grouping
and the default-category branch; they are re-hosted into the suite. None of the fourteen pins the
favorite-versus-categories order or the dismiss case, so the cases that matter most here are net-new.

## Key files

The add paths, which is the inventory this plan has to keep whole:

- Manga: `eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt` (details),
  `eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt` (history),
  `eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceViewModel.kt` and
  `.../globalsearch/SearchViewModel.kt` (browse and global search, the first also serving
  MangaDex follows through `exh/md/follows/MangaDexFollowsViewModel.kt`, which extends it),
  `reikai/presentation/recommendation/browse/RelatedMangasBrowseViewModel.kt` (bulk add, and the one
  path that restates the default-category rule instead of calling the kernel),
  `reikai/presentation/browse/MangaLibraryAdder.kt`.
- Novels: `reikai/presentation/novel/details/NovelDetailsViewModel.kt`,
  `reikai/presentation/history/NovelHistoryViewModel.kt`,
  `reikai/presentation/novel/globalsearch/NovelGlobalSearchViewModel.kt`,
  `reikai/presentation/novel/browse/NovelBrowseViewModel.kt`,
  `reikai/presentation/novel/browse/NovelLibraryAdder.kt`.
- The three composables holding manga's duplicate decision:
  `eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt`,
  `.../globalsearch/GlobalSearchScreen.kt`, `exh/md/follows/MangaDexFollowsScreen.kt`.
- Shared already: `reikai/domain/category/DefaultCategoryResolution.kt` (the kernel, six call sites
  across four files), `reikai/presentation/browse/EntryBulkFavoriteViewModel.kt` with its two
  subclasses `BulkFavoriteViewModel` and `NovelBulkFavoriteViewModel`, which share the decision and
  fork the write order.
- Dialogs: `eu/kanade/presentation/manga/DuplicateMangaDialog.kt` (upstream, present in `refs/mihon`)
  and `reikai/presentation/novel/browse/DuplicateNovelDialog.kt` (Reikai-owned), plus their nine
  hosts. Neither is in the off-path manifest yet.
- The consumer: `reikai/presentation/recents/RecentsBehavior.kt` and the two adapters beside it.

## Status

Shipped. Steps 1 to 4 are device-verified; step 5 is the recents verb, which no screen calls yet.

Two pieces of the flow are still written per type, both owned elsewhere: the History add decision, which
`content-layer-recents-surface.md` closes in its step 8b, and the category picker, which is two per-type
dialogs and gets its own collapse.

- **Step 1** (`91999475c`) and **step 2** (`a6f73a2ac`): no user-visible change, so no CHANGELOG entry.
- **Step 3** in two commits, manga (`f44f97322`) then novels (`f4517ec3b`), which is where the
  CHANGELOG entries land. **Step 3's twin-test collapse** rode after it (`43d7dc816`).
- **Step 4** replaced both dialogs with `EntryDuplicateDialog` at all nine render sites.
  `DuplicateMangaDialog` is deleted and manifested; the novel twin is deleted outright.
- **Step 5** put `addToLibrary` on `RecentsBehavior`, implemented in both adapters by handing each
  type's history model the ids it owns, the same shape `removeFromHistory` uses. Each model already
  runs the shared sequence, and the dialogs an add can raise stay on it, so the seam still carries no
  dialog channel. Nothing calls the verb until the recents engine does, in that surface's step 8b.
- **Device pass on the emulator for step 4**: the dialog on a novel browse long-press and on a manga
  one, a card tap opening the migrate dialog, add-time grouping through the picker (which the
  uncategorized group still asks for, and which stays added when dismissed, with both rows landing in
  one group), selection mode reading "1 picked", a merged group collapsing to one card with its
  source-count badge, and an uninstalled plugin's card showing the warning beside its raw source key.
  The long-press change was checked by opening a duplicate and coming back to a dialog still holding
  the pending add. Only the artist row on a card is unverified there: no novel in that library has an
  artist on its source row, and a custom one set through the edit form does not reach the duplicate
  query, on either content type.

**Step 4's behaviour inventory**, walked against both deleted files, which is the completion bar a
takeover has to clear. Carried across unchanged: the sheet host and its dismiss, the bulk-select
toggle and its gate, the title and summary, same-group collapse into one card, range selection from
an anchor, the disabled-until-picked "add to existing group" row, "add anyway", cancel, the chapter
and grouped-source badges, the selected tint plus ring, and the card's cover, title, author and
status. Levelled up on novels, all of it manga behaviour novels never had: the artist row, the
warning icon on a source whose plugin is not installed, one measured card height across the row
rather than each card sizing itself, and a cover crossfade. Deliberately dropped: the novel dialog's
nullable `onMigrate`, whose null branch (a tap that opens instead of migrating, a long-press that
does nothing) no call site could reach; and the novel long-press dismissing the dialog before
opening the duplicate, which abandoned the pending add, where manga's leaves the question open to
come back to. Moved rather than changed: manga's per-card `SourceManager.getOrStub` lookup now runs
in `MangaLibraryAdder.duplicateSourceLabels`, the same call one layer out, which is what took the
last DI call out of a composable here.

- **Device pass on the emulator (2026-08-09), every add path**: browse and global search on both
  types, both details screens, both History rows, add-anyway from the duplicate dialog, and both
  add-time grouping branches (a group with categories, which seeds them and shows no picker, and an
  uncategorized group, which asks and stays added when dismissed). No crashes. The only behaviour
  still resting on unit tests alone is a failed favorite write, which the UI cannot force.

Planned 2026-08-09, re-scouted the same day against current code. The re-scout is what
produced the twelve-path inventory, the duplicate-check ruling and the corrected step order; the
first draft had assumed each type agreed with itself. The recents surface's `addToLibrary` half of
step 8b is parked on this plan: it needs one add sequence to delegate to, and building it against
two orders would have baked the divergence into the engine.

## Decisions & tradeoffs

- **Manga's stated order wins, and both types move to it.** Ruled by the owner, and corrected by the
  re-scout: the order upstream states, and which manga details and history implement, is the target,
  but manga's other four paths do not implement it either. The first draft said moving novels was the
  smaller change; it is not, because manga has three orders and novels have one.
- **The sequence owns the duplicate check, taking the novel shape.** The two checks are one query, so
  the only real difference was that manga ran the decision in three composables. Owning it removes
  those copies and the convention violation with them; the alternative, starting the sequence after
  the check, would have left three copies of one decision that the write-once rule cannot see because
  they read as UI.
- **The grouping path is not reopened.** It favorites up front by design; see the carve-out above.
- **The two adders are still not twins**, and this plan does not make them one class. The manga adder
  returns neutral results and leaves orchestration to callers; the novel one carries the insert a
  browse item needs before it has a row. What becomes shared is the sequence and the verbs it calls,
  not the carrier.
- **This supersedes the parked "neutral adder contract stays declined" item**, on the evidence above.
  The decline is correct for the polymorphic-adder shape it was written about; what this plan adds is
  a sequence owner, which is a different thing.
- **`RelatedMangasBrowseViewModel` is folded in rather than left as a known bypass.** It restates the
  default-category kernel inline, so under write-once it is a gap, and the swap is a pure read that
  costs nothing at step 1.
- **The sealed reference is novel-local, not a seam type (2026-08-09, found by building it).** The
  plan expected identity to cross the shared seam as a stored-or-unstored value. It does not: each
  type brings its own lookup to the decision, and the sequence takes the id it files against from
  what the favorite verb returns. Where the distinction was actually needed is the novel browse
  picker, which can now open before a row exists, so `NovelCategoryTarget` (Stored or Pending) lives
  beside that dialog. Manga needs no such case, its rows exist before a picker can open.
- **Three deltas rode with the shared decision**, all improvements, none of them asked for: a manga
  already in the library no longer runs a duplicate lookup it discards (novels already skipped it),
  the long-press haptic fires on release rather than after that lookup, and the decision runs on the
  view model's scope rather than the composition's, so it survives the screen going away mid-flight.
- **Neither dialog difference turned out to be a capability (2026-08-09, found by building it).** The
  plan expected the artist row and the stub-source warning to be manga-only, so the shared card would
  need typed slots for them. Novels carry an artist end to end (`Novel.artist`, the plugin model, the
  refresh, the edit-info form), and an uninstalled plugin is the same state a stub source is, so both
  were parity gaps rather than divergences and the card needs no capability at all. The same stale
  claim was hiding the artist on the novel details header, which is fixed alongside.
- **The card is generic over each type's row, not over a neutral row type.** `toUi` maps a row to the
  neutral card while the callbacks keep the caller's own type, which is what lets a novel site
  navigate by source and url where a manga site navigates by id. Same shape as `EntrySearchCardRow`.
- **The manga picker-confirm ordering is fixed inside step 3, not ahead of it.** The two confirms file
  categories and favorite from separate coroutines, so the writes are unordered as well as inverted.
  The end state is the same unless the favorite write fails, and the sequence fixes both at once.
