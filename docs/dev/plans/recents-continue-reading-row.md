# Recents: a continue-reading row is about the chapter it will open

Developer-facing record for the second round of feedback on the combined Recents tab
(`unseensnick/Reikai#57`), written 2026-08-14 before the work starts. The surface it changes is
recorded in [content-layer-recents-surface.md](content-layer-recents-surface.md); read that first for
how the engine, the lanes and the modes fit together.

## Vocabulary

Two terms this doc leans on, since the difference between them is the whole subject. The *recorded*
chapter is the one a history row was built from, the chapter you last opened for that entry. The
*target* is what a tap opens: the recorded chapter while it is unfinished, otherwise the oldest chapter
of the series still unread.

The lanes (`RecentsLane.Read` / `Updated` / `Added`) and the four modes are defined in the surface
record. In UI terms Grouped is `RecentsMode.DIGEST`, and the combined modes, where
`RecentsMode.isCombined` holds, are Grouped and Feed.

## Goal

In the combined modes, a continue-reading row names, draws and acts on its target. History keeps the
recorded chapter, because that tab is a log of what happened rather than a list of what to read next.

## Why

The target rule was reversed on 2026-08-13, so a tap now reaches a backlog left behind the bookmark.
The row did not move with it: it names the recorded chapter, its read flag drives the dimming and the
unread dot, its bookmark flag drives the bookmark icon, and its controls act on `item.lane.chapterRef`.

What a user sees is a row saying "Ch. 3", greyed out as though read, whose download button would fetch
Ch. 2. Renaming the chapter on its own was tried and rejected on device for that reason, so the row
moves whole.

## Approach

The target is resolved when a row is drawn, remembered by the engine, and read by everything that
draws or acts on that row. Nothing resolves that has not been on screen or selected.

### The seam answers a row

`RecentsProvider` gains a method returning what a continue-reading row needs:

```kotlin
suspend fun targetRow(item: RecentsItem): RecentsTargetRow?
```

`RecentsTargetRow` holds the target's `ChapterRef`, its `RecentsChapterUi`, its `RecentsChapterState`
(read, bookmark, progress) and its `RecentsDownloadUi`. Null where nothing is left to open.

Both adapters answer it out of the load their target rule already pays for. The rules work over
`RecentsChapter`, which holds only id, fetch time and read state, so each adapter now resolves through
a private `resolveTarget` that keeps the chapter objects the rule chose between and returns the picked
id alongside them; `targetChapter` is the same function reading only the id, so a tap and a row cannot
resolve differently. The pool has to hold the entry's own source list as well as the group's, because
the cross-source stitch drops the copies another source stands in for, and `resumeTarget` can pick out
of either.

The owning entry is the second half, because download state is keyed by that entry's stored title and
source alongside the chapter's name and scanlator, and a merged row can resolve a chapter belonging to
a sibling. Manga needs no query for it: `MergedChapterProvider.Group` already carries `mangaById`, and
populates it with the anchor when the entry is unmerged. Novels need one, `NovelRepository.getById` on
the chapter's own `novelId`, since `groupChapters` returns no such map.

Download state has to come out of the same call. Both adapters build a row's `RecentsDownloadUi` from
the row's payload, keyed on the recorded chapter, so a row naming its target while its indicator
reported the record would be the same defect in another control.

The ref `targetRow` returns names the owning entry, while `targetChapter` stamps the row's own entry on
the same chapter id. Both resolve the same chapter, so the row and its tap cannot disagree about what
to open; only the entry stamped on the ref differs, and nothing downstream reads it except the
per-type verb dispatch, which cares about content type alone.

### Which rows resolve

A resolution is not cheap. Both adapters load the entry's whole chapter list before any rule runs
(`mergedChapterProvider.load` for manga, `groupChapters` for novels), and a merged entry loads one list
per member, so the early return for an unfinished recorded chapter saves nothing.

So a gate decides, and it is the record's read flag widened by one clause: resolve when the recorded
chapter reads as read, or when the entry belongs to a merge group. The second clause covers a chapter
another source of the group has already read, which the target rule counts as read and the record's own
flag does not. An unmerged entry cannot be in that state, since the read-elsewhere carry-over is empty
unless a group has more than one member, and it and the membership map switch off together with series
merging.

The membership map exists in the assembly and is dropped on the way to the renderer: `RecentsRendered`
carries rows and a loading flag and nothing else. Carrying membership through it is the plumbing this
gate needs, and it is one field on a value the engine already builds.

A row whose target turns out to be the recorded chapter draws exactly as it does today.

### The engine remembers it

A `StateFlow<Map<ChapterRef, RecentsTargetRow>>` keyed by the recorded ref, filled as rows resolve.

The recorded ref is unique among the rows that use this memo, which is what makes it a safe key: only
read-lane rows resolve a target, and `collapseByEntry` reduces each lane to one row per entry before
anything is drawn, so no drawn list holds two read rows for one entry. A read row and an updated row
can share a chapter ref in Grouped, and that costs nothing here, because the updated row never asks.

The memo is emptied when the lane data changes rather than when the assembly emits. The assembly folds
the search query in with the lanes, and the lane combination is already its own sub-flow, so the clear
hangs there and a keystroke leaves the memo alone. Hanging it on the lanes is also what invalidates a
row after its chapter is marked read: the write lands, the lane queries re-run, the memo empties. The
history views do re-emit on a chapter write, confirmed on device: marking a row's target read moves
the row straight on to the next chapter. A mode switch empties it too, so a memo filled in Grouped
cannot answer for a History row, which is about its record.

### The row and the bar read it

The read-lane branch of `RecentsMixedLaneRow` draws the target's name, read flag, bookmark, progress,
tap and download indicator. Until a resolution lands the row keeps drawing the recorded chapter, so the
line never blanks.

The bulk bar gates from the memo, collected with `collectAsState` rather than read off the flow, and
falls back to the record for anything unresolved. That fallback changes which buttons appear, never
what they do, because the verbs resolve before dispatching.

### The verbs act per row, without changing the selection

The selection stays a `Set<ChapterRef>` of recorded refs. What changes is one step in front of the
verbs: `RecentsEngine.actingChapters` maps each selected row to the chapter that row is about, taking
the target for a combined-mode read row and the recorded chapter otherwise, from the memo where it is
warm and by resolving where it is not. The screen calls it, because it already materialises the live
items for the selection when it builds the bar and is the one place that has both the rows and a
coroutine to resolve in.

The four verbs therefore take the chapters to act on rather than reading the selection back out of the
engine. That is a signature change the plan first expected to avoid, and it is forced: the mapping
suspends, and a verb that resolved internally would either have to launch and clear the selection out
from under the dispatch, or hide the timing entirely.

Four verbs need this, and `deleteDownloads` is the one easily forgotten: `markReadSelection`,
`setBookmarkSelection`, `downloadSelection` and `deleteDownloads`. The last raises a confirmation
dialog, so the resolved refs are what the dialog carries, decided when it is raised rather than when it
is confirmed.

## Key files

All under `app/src/main/java/reikai/presentation/recents/` unless noted:

- `RecentsProvider.kt`: the seam, where `targetRow` sits beside `targetChapter`.
- `RecentsRowUi.kt`: where `RecentsTargetRow` belongs, beside the projections it composes.
- `MangaRecentsAdapter.kt`, `NovelRecentsAdapter.kt`: the two implementations, including the target's
  download state and its owning entry.
- `RecentsEngine.kt`: the memo and its clear, membership carried through `RecentsRendered`.
- `RecentsScreen.kt`: the read-lane branch of `RecentsMixedLaneRow`, the bar's gating, and the mapping
  in front of the four verbs. `RecentsItem.key()` and `listKey()` also live here, unchanged.
- `RecentsTarget.kt`: the shared rule, unchanged by this work.

## Steps

Steps 1 to 5 landed as one commit: step 1 alone adds a seam nothing calls, and step 3 is what makes
any of it observable, so splitting them would have shipped dead code and an untestable half-move.

1. The seam: `RecentsTargetRow` and `targetRow` in both adapters.
2. The engine: the memo, its clear on the lane data and on a mode switch, and membership carried
   through `RecentsRendered` for the gate.
3. The row: the read-lane branch of `RecentsMixedLaneRow` draws the target. Its other two branches are
   untouched.
4. The verbs: map each selected row to its chapter in front of the four bulk verbs, including the
   delete-downloads dialog.
5. The bar: gate from the collected memo.
6. Docs: fold the outcome into [content-layer-recents-surface.md](content-layer-recents-surface.md)
   and add a CHANGELOG line.

## Tests

`RecentsTargetTest` already pins the rule over `resumeInGroup`, `resumeTarget` and `firstUnreadOf`.
That kernel is the rung both content types are held to, and it needs nothing further here.

Neither adapter can be constructed in a unit test. Their factories need a live `UpdatesViewModel` or
`HistoryViewModel` (the novel twins on the other side), and several of their lane properties dereference
injected dependencies while the object is still being built, which is also why their row projections
are top-level functions rather than members. So a conformance test parameterized over the two real
adapters is not available, and the seam is covered at the engine level over `FakeRecentsProvider` plus
device verification.

What to add: the gate fires for a merged entry whose recorded chapter reads as unread and does not fire
for an unmerged one in the same state; the memo holds one entry per resolved row and survives a search
keystroke; each of the four bulk verbs receives the target's chapter for a combined-mode read row and
the recorded chapter otherwise; a selection mixing read rows with update rows maps only the read ones; a
row whose target is the recorded chapter draws unchanged.

Verify every new test by mutation, and make the mutation reach the call site rather than only the rule.

## Device verification

The emulator has the case: a novel of 4,851 chapters whose recorded chapter is read and whose oldest
unread sits far earlier, and a manga merged across two sources. Both content types, in Grouped and in
Feed:

- The row names the oldest unread chapter, is not dimmed, shows the unread dot, and its progress line
  reflects that chapter.
- Tapping opens the chapter the row names.
- The download indicator reports and acts on that chapter.
- Selecting the row offers mark-as-read, and each bulk verb hits the chapter the row names.
- A merged series behaves the same when its recorded chapter was read on the sibling source.
- The History tab still names the recorded chapter, still dims it, and offers no download control.

A `uiautomator` dump lists only rendered rows, so a scrolled list reads exactly like a filtered one.

## Status

Shipped. The reversed target rule is `fe65fb90f` and the removal of the download control from History
rows is `fde3a86fd`; the row move itself is `8c982480c`, which carried steps 1 to 5.

Verified on the emulator for both content types. A novel whose recorded chapter is finished names the
oldest unread one in Grouped while History still names the record, and a tap opens the chapter the row
names. On the manga side the bulk mark-as-read hit the target rather than the record, and the row then
moved on to the next chapter with its progress line gone, which is also the on-device confirmation
that a chapter write re-runs the lane queries and empties the memo.

Two things the device pass did not reach: the download control actually fetching the target (the
emulator's sources do not download), and delete-downloads on a resolved row.

## Decisions & tradeoffs

- **The selection is not re-keyed.** An earlier shape of this plan moved it from chapter refs to row
  keys, on the grounds that a Grouped read row and updated row can share a chapter ref. They can, but
  it costs nothing here: only read rows resolve targets, and each lane is collapsed to one row per
  entry before drawing, so the memo cannot collide. The ambiguity affects one thing only, selecting one
  of those two rows highlights both, which is how the surface behaves today and is not what this work
  is for. Leaving the selection alone removes a step that would have changed visible behaviour, altered
  the delete-downloads dialog, made added rows selectable unless re-guarded, and moved twelve test call
  sites.
- **Resolution stays lazy and per row.** Resolving the lane in bulk was measured against a library of
  301 manga and 68 novels holding 240,236 chapters, with the biggest series at 3,886 and 7,177
  chapters, assuming every entry has been read. In Kotlin it costs 195,140 chapter objects and 17.9 MB
  of chapter text per pass, 232 ms on a desktop with a warm cache and plain tuples, before SQLDelight's
  mapping or GC. One SQL query ignoring merge groups costs 15 ms manga plus 20 ms novel but answers
  wrongly, since 218 of those manga are in merge groups. One SQL query deduping by chapter number
  across a group's members costs 88 ms for manga alone, and a covering index on
  `(manga_id, chapter_number, read)` did not move it. Only Feed makes this a scaling question at all,
  since Grouped caps its continue-reading section at nine rows and History is excluded.
- **A SQL answer would also be a second definition of reading order.** The rule reads the list in the
  entry's own reading order, which `getChapterSort` derives from the entry's sort flags, and it honours
  the scanlator exclusion, which lives in a join table. `recentsUnread.sq` deliberately answers only
  the boolean question and says so.
- **A bulk action on a large selection pays for its own resolution.** Select-all over a Feed on a
  fully-read library resolves a target per selected read row. That is the same class of cost as the
  writes the action then performs, it happens once on a deliberate action rather than per emission, and
  it runs off the main thread. It is the one place this design is slow, and it is worth watching on
  device.
- **The chapter-state filters keep judging the recorded chapter.** That predicate runs inside the
  engine's rendered transform, over every item, synchronously, before any row has drawn. Reading
  targets there is bulk resolution by another name. So a combined-mode read row can be named by its
  target and filtered by its record.
- **The row keeps the record's timestamp.** It says when you last read this series, and it orders the
  feed. A row can therefore read "Ch. 3, read 11:54 PM" for a chapter you have not opened; if that
  lands badly on device, changing the verb is a separate decision.
- **Mark-as-unread stays offered, and that is correct.** A target can be a chapter you started and
  left, so it has progress, and resetting that progress is a real thing to want.
- **History is not touched.** Its rows name, dim and act on the record. The two tabs name different
  chapters because they answer different questions, and a row's capabilities follow the chapter it
  names in both.
- **The caught-up filter is unchanged.** It asks once per emission whether an entry has any unread
  chapter. The reversed target rule is what made that promise keepable, and nothing here weakens it.

## Not in scope

Showing progress as `Page: n/t` rather than `Page: n`, which is agreed and queued behind this. It needs
a `total_pages` column on `chapters` with its own migration, the reader writing it, a second string and
a backup field, and it is manga only: novels store a scroll percentage and have no page concept.
