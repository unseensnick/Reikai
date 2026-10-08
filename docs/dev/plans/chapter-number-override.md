# Correcting a chapter's number

## Goal

A user can correct a chapter number the source got wrong, on manga and novels, and the correction holds through refreshes and backups until they put the source's number back.

## Why

A wrong number breaks every rule that reads one: the missing-chapter markers, the sort by number, next chapter, duplicate marking and tracker progress. Sources do get numbers wrong (a misnumbered repost, a typo, a volume restart), and before this the user could only hide the chapter. Owner ruling G4 (2026-10-04) put three layers in 0.4.0: hidden chapters never count toward gaps (folded into the gap fix), this correction, and a hint marker that suggests one.

## Approach

The corrected number is written on the chapter row itself, so everything that reads a chapter's number reads the correction with no rule of its own: the list, sort, gaps, next chapter, tracker pushes and a merged series' display. A second table per content type (`manga_chapter_number_override`, `novel_chapter_number_override`, `58.sqm`) remembers each correction, keyed by the chapter's owner and url rather than its row id, because a sync that drops a chapter and lists it again re-creates the row. Each row also keeps the number the source gave, which clearing writes back.

Both syncs read the owner's corrections before comparing the source's list with the stored one and put each correction in place of the source's number (`appliedTo`), so a refresh whose source still says 5 writes nothing. When the source has renumbered a corrected chapter, the sync records the new source number beside the correction (`updateSourceNumbers`), so a later clear restores what the source says now.

The action is "Correct chapter number" in the details selection toolbar's overflow, shown while exactly one chapter is selected (Q22: long press selects, so there is no long-press menu to put it in). The dialog reads the correction when it opens, through `EditChapterNumber`, which both details models call. Saving the source's own number, or Reset, clears the correction. A save runs inside `ReconcileMergedChapters.afterPass`, because a renumbered chapter leaves a merged series' stored stitch stale.

A chapter whose number is out of line with its source's list carries a warning mark before its title (after it, a long title's ellipsis would hide it). Tapping or holding the mark opens the same dialog, filled with the whole number its neighbours leave free, or with the chapter's own number when none fits; the overflow action on a marked chapter opens on the suggestion too. `ChapterNumberHint.forOwners` decides, once for both types. It reads each owner's own stored list in source order, never the merged list, because a merged list restamps the order and keeps one copy per chapter. In that list it chains rows into runs, where a run breaks at a jump of more than 10, and marks a run of at most 5 rows whose two neighbouring runs are within 10 of each other. The suggestion reads the neighbours lowest first, so a source that lists newest first gets the same number. Side content is left out of the runs and never marked: a name with a side-content word (side story, extra, special, omake, epilogue, prologue, bonus, afterword, illustrations), and a row with no leading volume label that a volume-labelled list interleaves (a bonus part numbered by its volume, "Chapter 3.1" after "Vol.3 Chapter 15"). A chapter the user hid is left out too, so it is never marked and never shapes a shown chapter's run. A marked row whose upload date shows it is a copy listed in the wrong place goes unmarked, since its number is right: within 2 days of another row with the same number and more than 30 days from both rows the source lists it between. An unknown date (0, as most novel plugins give) never decides. A novel's source chip on a paged source judges the page it shows.

A backup writes the corrected number where Mihon writes a chapter's number and the source's number in a field of its own (`BackupChapter` 701, `BackupNovelChapter` 12), only for a corrected chapter. A restore stores the corrections after the chapters and writes each onto its row (`restore`). A backup restored into Mihon keeps the corrected number until Mihon's next refresh re-parses it.

## Key files

- [ChapterNumberOverride.kt](../../../domain/src/main/java/reikai/domain/chapter/ChapterNumberOverride.kt): `ChapterNumberOverrideRepository`, `appliedTo` (both syncs), `backedUpOverrides` (both restores).
- [chapter_number_override.sq](../../../data/src/main/sqldelight/tachiyomi/data/chapter_number_override.sq) and [ChapterNumberOverrideRepositoryImpl.kt](../../../data/src/main/java/reikai/data/chapter/ChapterNumberOverrideRepositoryImpl.kt).
- [EditChapterNumber.kt](../../../app/src/main/java/reikai/domain/chapter/EditChapterNumber.kt) and [ChapterNumberDialog.kt](../../../app/src/main/java/reikai/presentation/details/ChapterNumberDialog.kt).
- [SyncChaptersWithSource.kt](../../../app/src/main/java/eu/kanade/domain/chapter/interactor/SyncChaptersWithSource.kt) (`// RK` island) and [NovelChapterSync.kt](../../../app/src/main/java/reikai/data/novel/NovelChapterSync.kt) (`syncChaptersWithNovelSource`).
- [ChapterNumberHint.kt](../../../domain/src/main/java/reikai/domain/chapter/ChapterNumberHint.kt): `forOwners`, the out-of-line mark and its suggestion; each details model computes it where it still has every source's own list (`MangaViewModel.MergedChapters`, `NovelDetailsViewModel.unifiedChapters` and `singleChapters`).
- Tests: `ChapterNumberOverrideConformanceTest` (both syncs and the editor), `ChapterNumberOverrideBackupTest` (both backups and restores), `ChapterNumberDialogTest`, `ChapterNumberHintTest` (the rule, on real rows), `NovelDetailsNumberHintTest` (the novel page's marks and dialog).

## Status

Built for 0.4.0. Device checks owed: an AniList push after a correction, a merged series re-stitching on screen, a restore, and the hint's marks on a manga and a novel page (the manga wiring has no unit test).

## Decisions & tradeoffs

- **The row carries the correction, not a read-time overlay.** An overlay would have had to be applied at every place a number is read, which is the duplication the content layer exists to prevent; writing the row means none of them change.
- **Keyed by owner and url, one table per type.** A correction belongs to one source's chapter, so a merged series' other sources keep their own numbers, and a migration does not carry it to the new source.
- **The novel sync takes the repository as a required parameter** through every function that reaches it, rather than a default that a caller could forget and silently drop corrections.
- **No interactor beyond `EditChapterNumber`**: the dialog's state is the chapter it opened on, and the details rows carry no "corrected" mark.
- **The hint's skip rule was ruled in by the owner (2026-10-08) on a measured library.** Without it the rule made 37 marks on 11 series, about 25 of them side stories, extras, epilogues and volume-interleaved bonus runs. With it the same library (emulator copy, 2026-10-08) gives 12 marks on 5 series, all real: two unnumbered parts and their neighbours in one novel (8), a misnumbered chapter (1335 for 1135), and three reposted or mistyped manga chapters. The ruling was to drop the hint if the skip left it noisy. The skip only ever removes marks, so a misnumbered chapter whose name says "bonus" or "special", or one inside a volume-labelled list's interleaving, goes unmarked.
- **A misplaced copy is not marked (owner, 2026-10-08).** On the same library this drops one mark, The Legendary Mechanic's "Chapte 177" (2 hours from Chapter 177, 798 days from its place), and keeps the rest; matching any number within 2 instead of the same number would also have dropped Martial Peak's "Chapter497", a day from 496 and 498 but 817 days from its twin.
- **A corrected chapter still out of line stays marked.** The mark reads the stored number, so saving the suggestion clears it, while a correction the user chose to keep out of line keeps the mark.
- **E-Hentai gallery versions** are numbered from the version chain by `EHentaiUpdateHelper`, which writes numbers outside the sync; a correction there is overwritten when a new version merges and comes back at the next sync.
