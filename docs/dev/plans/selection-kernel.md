# One selection kernel for every multi-select surface

## Goal

Selecting more than one thing behaves the same everywhere in the app, and the rules that decide it
live in one place instead of being restated per screen.

## Why

An audit of the chapter lists found the novel details model keeping a two-slot anchor array whose
second slot was written three times and read nowhere. Tracing that turned up the larger problem: range
selection was implemented **seven times**, in two incompatible algorithms, with no shared helper. The
only thing they had in common was that a user could not predict which one they were about to get.

The two families, before the collapse:

- **A single anchor that unions**, five near-verbatim copies: the library engine, the recents engine,
  the recommendations grid, the manage-sources dialog and the duplicate dialog.
- **A two-slot first/last window that fills gaps**, two copies: `MangaViewModel` (Mihon's, unchanged
  from upstream) and `NovelDetailsViewModel` (Reikai's, half-implemented).

They already disagreed with each other in ways nothing caught. The library engine derived its anchor
from the selection set's last element, so deselecting a row silently relocated where the next range
would start; the recents engine kept an independent anchor that survived a deselect. `selectAll`
cleared the anchor on three surfaces and kept it on the fourth. Inverting a selection discarded picks
outside the visible list on the details screens and preserved them on the engines.

## Approach

`EntrySelection` is the kernel: pure, generic over the row type, and holding the anchor **inside**
`SelectionState` rather than in a field beside the selection. That placement is the point. The two
are only ever correct together, and keeping them apart is exactly how the two engines drifted.

Verbs: `toggle`, `range`, `rangeOrToggle`, `rangeOrToggleBlock`, `selectAll`, `invert`, `retain`,
`clear`. Every multi-select surface calls them, over `EntryId`, `ChapterRef`, chapter ids, source ids
and candidate URLs.

**Family A won** (owner ruling). A range runs from the last row you touched to the one you pressed and
only ever adds. It never tracks a window, because a window can disagree with the selection it
describes, which is what produced the original bug. Five of the seven surfaces already worked this way,
so unifying on it rewrote two call sites rather than five, and it matches the shift-click convention.

The cost, accepted knowingly: `MangaViewModel`'s selection region is now an `// RK` island, so it forks
a file upstream has never changed in its visible history (three commits, all mechanical). That was the
price of one helper serving both content types, which is what the owner asked for.

**A long press on an already-selected row drops it, everywhere** (owner ruling), via `rangeOrToggle`:
extend to the row, unless it is already selected, in which case remove it. It was restored on the
chapter lists first, to match Mihon, and then made uniform across all eight surfaces rather than left
as one gesture with two answers.

**A collapsed group is a block, not a row.** `rangeOrToggleBlock` extends to whichever end of the block
is farther from the anchor, then unions the block, so an anchor sitting inside a collapsed group cannot
leave half of it unselected. A fully selected block is dropped instead.

## Key files

- `app/src/main/java/reikai/presentation/selection/EntrySelection.kt`: the kernel and `SelectionState`.
- `app/src/test/java/reikai/presentation/selection/EntrySelectionTest.kt`: the rules, pinned. Two
  clauses verified by mutation; the device-observed scenarios are pinned as their own cases.
- `eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt` (`chapterSelection`): the RK island.
- `reikai/presentation/novel/details/NovelDetailsViewModel.kt` (`chapterSelection`,
  `retainChapterSelection`): the novel half, plus the prune that drops a vanished anchor.
- `reikai/presentation/library/LibraryEngine.kt`, `reikai/presentation/recents/RecentsEngine.kt`: the
  two engines, whose existing suites are the regression net for the whole migration.
- `reikai/presentation/details/EntryDetailsBehavior.kt` (`toggleSelection`): the shared seam both
  details adapters implement, and the reason the two chapter lists could be unified at all.

## Status

**Shipped and device-verified**, `291c51792`, `7688e9698`, `02a67d015`.

The details pair first, then all eight surfaces, then the two gaps a device pass found: grouped rows in
Updates never called range at all (they called `toggleAll`), and the categories screen had no range
function to call. `CategorySelection` is folded in and deleted, its one uncovered case carried over as
a test first.

Both engine suites pass untouched across the migration, which is what pins that no behaviour moved
where it was not meant to.

## Decisions & tradeoffs

**Family A over family B** (owner). The deciding argument was that the window bug is structural: a
two-slot window can go stale against the selection it describes, and an anchor cannot. Cost is the
upstream fork of `MangaViewModel`.

**A deselect still moves the anchor.** Drop a row, then range on from it, and it comes back, because
the rule is "measure from the last row you touched" with no exception. The alternative (leave the
anchor where it was) buys a better answer in a rare case and pays a less predictable anchor in every
case, with nothing on screen to say where the anchor went. Recoverable in one tap either way.

**Long press deselects an already-selected row on every surface** (owner). Restored on the chapter
lists first after a device pass, where it matches Mihon, then made uniform. The alternative left one
gesture with two answers depending on which screen you were on, which is the drift this whole change
exists to remove. Pinned at the engine level too, not only in the kernel, because which verb a surface
calls is the part that can silently regress: both engine tests fail if either goes back to `range`.

**The kernel absorbed the more general `invert`.** The engines preserved picks outside the visible
list; the details screens discarded them. The preserving form is correct wherever the visible list is a
slice, and identical where it is not, so it won.

**`selectAll` clears the anchor everywhere.** Three of four surfaces already did. After a bulk verb,
no row on screen is one the user pressed, so a range measured from the old anchor would be arbitrary.

**No unit test covers the details models themselves.** Neither can be constructed in a JVM test, which
is why the logic moved into a kernel that can. The models are thin calls into it now, and the device
pass covered them.
