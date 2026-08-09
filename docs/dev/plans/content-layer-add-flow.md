# Content layer: the add-to-library flow

## Goal

One add-to-library flow for both content types: the duplicate check, the favorite write, the category
decision and the picker that renders them are written once and reached by every surface that can add
an entry to the library. A change to how adding works stops being something you have to remember to
make nine times.

## Why

This flow drifts faster than anything else in the content layer, because it is short enough to look
copyable and has no single owner. The evidence, as of 2026-08-09:

- **The two types favorite at different points.** Manga resolves the category decision first and
  favorites only on the branch that needs no picker (`MangaViewModel.toggleFavorite`,
  `HistoryViewModel.addFavorite`), deferring the write to the picker's confirm
  (`moveMangaToCategoriesAndAddToLibrary`). Novels favorite first and prompt afterwards
  (`NovelDetailsViewModel.addToLibrary`, `NovelHistoryViewModel.addToLibrary`,
  `NovelLibraryAdder.addToLibrary`). Neither is wrong on its own; having both means a user backing out
  of the picker adds a novel and does not add a manga.
- **Only one of them aborts on a failed write.** The manga paths return when
  `awaitUpdateFavorite` answers false. The novel history path discards that result and files
  categories onto a row it never favorited, then offers a picker whose confirm writes more.
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

Sequenced so each step is independently shippable and device-verifiable:

1. **The pure kernel.** Split deciding from writing on both adders: a pure `resolveDefaultCategories`
   and a pure picker-payload builder beside the existing writer, which survives for the grouping path.
   Nothing changes behaviour yet, so this ships on compile plus unit tests alone.
2. **The novel paths adopt the manga order.** Browse long-press, global search, details and history
   move to resolve-favorite-file, and each picker confirm gains the favorite it now owes. Live
   behaviour change on four shipped surfaces, so this is its own commit and its own device pass.
3. **The manga paths adopt the shared sequence.** Same order, but the change is smaller: the direct
   branch already favorites first, and only the picker-confirm ordering moves. The five inline copies
   collapse onto the shared verbs.
4. **One duplicate dialog.** A neutral `EntryDuplicateDialog` over per-type row data replaces both
   components at all nine render sites; Mihon's `DuplicateMangaDialog` is deleted and manifested, per
   the delete-and-manifest policy.
5. **The recents engine consumes it**, which unblocks the recents surface's step 8b.

**Tests.** The order is a pure sequence over the four verbs, so it can be pinned without a device:
abort on a failed favorite, no writes at all when the picker is dismissed, the direct branch writing
favorite before categories, and the grouping carve-out still favoriting up front. Every case gets its
twin per the twin rule, and each is verified by mutation.

## Key files

The add paths, which is the inventory this plan has to keep whole:

- Manga: `eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt` (details),
  `eu/kanade/tachiyomi/ui/history/HistoryViewModel.kt` (history),
  `eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceViewModel.kt` and
  `.../globalsearch/SearchViewModel.kt` (browse and global search),
  `reikai/presentation/recommendation/browse/RelatedMangasBrowseViewModel.kt`,
  `reikai/presentation/browse/MangaLibraryAdder.kt`.
- Novels: `reikai/presentation/novel/details/NovelDetailsViewModel.kt`,
  `reikai/presentation/history/NovelHistoryViewModel.kt`,
  `reikai/presentation/novel/globalsearch/NovelGlobalSearchViewModel.kt`,
  `reikai/presentation/novel/browse/NovelLibraryAdder.kt`.
- Shared already: `reikai/domain/category/DefaultCategoryResolution.kt` (the kernel),
  `reikai/presentation/browse/EntryBulkFavoriteViewModel.kt` (bulk add).
- Dialogs: `eu/kanade/presentation/manga/DuplicateMangaDialog.kt` and
  `reikai/presentation/novel/browse/DuplicateNovelDialog.kt`, plus their nine hosts.

## Status

Planned 2026-08-09, not started. The recents surface's step 8b is parked on it: that step needs one
add sequence to delegate to, and building it against two orders would have baked the divergence into
the engine.

## Decisions & tradeoffs

- **Manga's order wins, and novels move.** Ruled by the owner. Manga's is the order upstream uses and
  the one already shared by both manga surfaces, so moving novels is the smaller change and the one
  that keeps the upstream relationship on Mihon's files.
- **The grouping path is not reopened.** It favorites up front by design; see the carve-out above.
- **The two adders are still not twins**, and this plan does not make them one class. The manga adder
  returns neutral results and leaves orchestration to callers; the novel one carries the insert a
  browse item needs before it has a row. What becomes shared is the sequence and the verbs it calls,
  not the carrier.
- **This supersedes the parked "neutral adder contract stays declined" item**, on the evidence above.
  The decline is correct for the polymorphic-adder shape it was written about; what this plan adds is
  a sequence owner, which is a different thing.
