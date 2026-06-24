# Merge-aware manga reader

## Goal

Open a merged manga and read straight through the whole group's chapters in one sitting: the in-reader chapter list and prev/next navigation use the unified cross-source list (with per-source labels), and reaching the end of one source's chapters flows into the next source's, in place. This brings the manga reader to the merge parity the novel reader already had.

## Why

Reikai's [manga merge engine](manga-merge-engine.md) lets several sources of the same series share one cover and one deduplicated chapter list on the details screen, but the reader stayed single-source: tapping a chapter opened only that chapter's own source (`ReaderViewModel.chapterList = getChaptersByMangaId(manga.id)`), so prev/next stopped at a source boundary and the in-reader chapter list showed only one source. The novel reader had already solved this; manga should match.

## Approach

In plain English: the manga reader is Mihon's, built around one manga and one source fixed when you open it. Rather than fork it, we add a small Reikai layer that feeds it the merged chapter list and routes each chapter to its own source, leaving Mihon's reader, page loaders, and viewers untouched so they keep riding upstream. The single-source assumption was woven through several `ReaderViewModel` methods (not behind one seam), so each touch point becomes a one-line delegation fenced with `// RK`.

Mechanism:

- **Unified list.** `MergedChapterProvider.load(anchorManga)` resolves the merge group (reusing `MangaMergeManager.computeRelatedMangaIds`) and returns the unified, reading-ordered chapter list (reusing `ChapterAggregation` + a reading-order restamp), the group's manga keyed by id, and each member's source name. For an unmerged manga it returns exactly the single-source list (zero change for the common case). The aggregate + reading-order policy lives only here; the details screen's merged-chapters flow calls the same `aggregate`.
- **Cross-source loading.** `MergedChapterLoader` holds one Mihon `ChapterLoader` per source, keyed by each chapter's own `mangaId`. Because each delegate is built with that source's own manga + source, Mihon's `ChapterLoader.getPageLoader` resolves downloads and pages correctly with no changes to it; the merge loader just picks the right one per chapter.
- **Per-source side effects.** `ReaderViewModel.mangaForChapterId` resolves a chapter's own manga within the group, and tracker sync, delete-after-read, download-ahead, the downloaded badge/filter, and the chapter dialog's download/delete all resolve through it instead of the opened manga. "Mark same-number duplicates read" spans the whole group. Save-image / set-as-cover deliberately stay anchored to the opened manga.
- **Per-source labels.** The in-reader chapter list (`ChapterListDialog`, already on the shared `MangaChapterListItem`) shows each chapter's source name in its subtitle, the same way the novel reader's sheet does; each row also carries its own source's manga so the row's download indicator is correct.
- **No boundary stutter.** The transition card's "is the next chapter downloaded" check used a synchronous storage probe (`skipCache = true`) on the main thread at every boundary, which fully missed (so was slowest) for a cross-source chapter. It now reads the in-memory download cache (`skipCache = false`), keyed to the chapter's own source, so binding the card stays cheap and happens once.

## Key files

- `app/src/main/java/reikai/domain/manga/MergedChapterProvider.kt` (new): group resolve + unified list + source names; the shared aggregate/reading-order policy.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/MergedChapterLoader.kt` (new): per-source loader router.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt` (`// RK`): merged chapter list, merged loader, `mangaForChapterId`, per-source side effects, `getChapters` labels.
- `app/src/main/java/eu/kanade/presentation/reader/ChapterListDialog.kt` + `.../ui/reader/chapter/ReaderChapterItem.kt`: per-source label in the row subtitle.
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTransitionView.kt` + the pager/webtoon transition holders (`// RK`): the per-source, cache-based downloaded badge.
- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt` (`// RK`): the details merged-chapters flow now calls `MergedChapterProvider.aggregate`.
- Test: `app/src/test/java/reikai/domain/manga/MergedChapterProviderTest.kt` (reading-order restamp; stitch/dedup already covered by `ChapterAggregationTest`).

## Status

Shipped, on-device verified on a Fold6 against a real same-title merge (cross-source navigation loads in place; per-source labels and download indicator correct; details screen unchanged). Commits: `4c811bae7` (loader seam), `977ddf8a0` (per-source side effects + boundary smoothness), `3ba35b67d` (per-source labels + the aggregate consolidation).

## Decisions & tradeoffs

- **Thin hooks, fat Reikai layer.** A pure wrapper with zero `ReaderViewModel` edits is not achievable (the single-source assumption is distributed, not behind one seam), so the touch points are minimal `// RK` delegations with the real logic in `MergedChapterProvider` / `MergedChapterLoader`. This keeps Mihon's reader structurally upstream and maximizes re-merge ease.
- **Reuse, don't duplicate.** Group math (`MergeGroupAlgebra` via `MangaMergeManager`), chapter stitching (`ChapterAggregation`), and the chapter row (`MangaChapterListItem`) are all reused; the only new shared home is the aggregate + reading-order policy, now called by both reader and details.
- **Per-source tracker on chapter finish** (not the anchor's): trackers are propagated across the group anyway, so this stays consistent after an unmerge, and it's low-consequence.
- **Cache-based downloaded badge** instead of a storage probe: removes the boundary stutter, at the cost of the badge being a beat stale right after a download finishes (acceptable for a transition card).
- **Externally-opened chapters** (history, updates) that were deduped out of the unified list are appended so they still open.
- **Save-image / set-as-cover stay anchored** to the opened manga (least surprise; the group's "face").
- **Not addressed (out of scope):** the webtoon page-settle reflow when a chapter loads over network is inherent Mihon behaviour (a loading page is held at full height, then resizes when the image decodes); it shows up at any network chapter, merged or not, and fixing it means changing Mihon's core page-height estimation.
