# Local series with volumes

## Goal

A local series can hold several volumes and show them as one series: its chapters under **Vol. N** headers on the details screen and in the reader's chapter sheet, with volume chips (All, Vol. 1, Vol. 2, ...) that show one volume, remembered per series. Volumes are put together at import, by converting an existing merge of local volumes, or later from the library or details, and taken apart the same way, without reimporting. Requested in unseensnick/Reikai#86 (items 2 and 6).

## Why

Merging separate local volumes is the only way to keep them together today, and a merge lines chapters up across its sources by number. Volumes reuse numbers and names ("Chapter 1", "Afterword"), so the merged All view hides some chapters and swaps others. Inside one series a chapter's address already names its volume's file and chapters are numbered across the whole series, so nothing collides; what is missing is the volume as something the user can see, order and act on.

## Approach

**The volume on a chapter** is read from its address, which names the EPUB for a novel and the volume folder for manga, so both types answer it by one rule and a refresh cannot lose it; nothing is stored per chapter. Local manga reads volume folders already: a subfolder holding chapter archives or chapter folders is a volume (`Vol 01/Ch 001.cbz`), a folder of only pictures stays one chapter, and the volume leads the chapter's name, as a local novel's does. A volumes table keyed by series and volume holds what the address cannot: the order and the label, filled in listing order, the label defaulting to the book's series number or the folder's number. `novel_chapters.page` is not the volume: the details screen reads any page label as a paged source and shows one page at a time (the spike hit this). The name loses its `Vol. N - ` prefix under a header.

**Showing it.** The shared chapter list (`entryChapterItems`) gains a third row kind beside `Chapter` and `Missing`, a `Volume` header, emitted where the label changes. A header shows the label, the volume's cover, its read count ("3/14"), and actions to mark the volume read or unread and to select it (`EntrySelection.toggleBlock`). Headers only make sense while each volume's chapters stay together, so they show under the source-order sort and hide under the others. The reader's chapter sheet (`ReaderChapterListDialog`) gets the same header row. Volume chips reuse `MergeSourceChips`' look over a neutral row, as a cell of their own, since a source chip also changes the header, web actions and download scope. The picked chip and the collapsed volumes are stored per series. Chips follow volume order whatever the chapter sort; the first reads **All volumes**.

**Reading across volumes.** The chapter after a volume's last is the next volume's first, as now. The transition names the volume ("Next: Vol. 3, Chapter 1") through one more field on `TransitionChapter`, which the manga transition and the native novel seam both render through `TransitionText`; the WebView seam needs its own change. The manga image viewers stay inside their existing island.

**Putting volumes together and apart.** One kernel moves local files and keeps everything that points at them: it renames or copies-then-deletes through the storage folder, then in one database transaction rewrites the series' `url` and each chapter's `url` and carries `chapter_number_override` and `local_novel_language`, so history, progress, words, sessions and tracking (keyed by row id) stay on their rows. A step that fails puts back the files already moved. On top of it:
- **Import** offers to combine the batch's volumes into one series, and asks before adding a volume to an existing series ("Add to *X* as Vol. 6?"), with a series picker.
- **Convert** a merge whose members are all local into one series, from Manage sources.
- **Library selection**: with only local series of one type selected, **Combine as volumes** takes Merge's place in the bar, with Merge kept in the overflow; **Add to series** picks a target. A mixed selection keeps Merge.
- **Details**: **Split out** a volume (⋮ menu and the volume's chip), **Reorder volumes** (a drag sheet built on `rememberSettledReorder`, the pattern of Manage sources), and rename a volume's label.
- Every one of these first shows what will move and how files will be renamed.

**Names.** The series folder is named after the series and files `<Series> - Vol. N.ext`; renaming the series renames the folder through the same kernel. The import's `%02d - ` prefix gives way to this naming.

**Duplicates.** Nothing is decided silently. Two files that claim one volume both show ("Vol. 3", "Vol. 3 (2)") under a notice until the user keeps both or removes one, which asks first. An import clash asks Replace, Keep both or Skip, and dismissing it keeps both. The import's silent name-and-size skip is replaced by that question.

**Merged sources that have volumes.** When two sources of a merge each have volumes (an official and a fan copy), the stitch pairs chapters by volume and chapter, not number alone. A second chip row shows a source's volumes once one source is picked; the source row's first chip then reads **All sources** and the volume row's **All volumes**. In the merged **All sources** list a volume's chapters sit among another source's, where a header cannot go, so each carries its volume in its subtitle ("Vol. 2 • Local source"); a source without volumes gives no basis to pair a local volume's chapters with its numbers, so they stay unpaired there (seen on a device spike of an online novel merged with a three-volume local one).

## Key files

- `app/src/main/java/reikai/novel/local/LocalNovelSource.kt` (`localChapters`, `chapterPath`, `volumeLabel`) and `VolumeOrder.kt` (`inVolumeOrder`).
- `source-local/src/main/kotlin/tachiyomi/source/local/LocalSource.kt` (Mihon's; `getChapterList`).
- `app/src/main/java/reikai/data/local/LocalImport.kt` (`groupSeries`, `groupKey`) and `LocalImportScreen.kt`.
- `app/src/main/java/reikai/presentation/details/EntryDetailsContent.kt` (`entryChapterItems`) and `EntryDetailsScreenState.kt` (`EntryChapterListItem`).
- `app/src/main/java/reikai/presentation/reader/ReaderChapterListDialog.kt`, `ReaderChapterList.kt`.
- `app/src/main/java/eu/kanade/presentation/reader/ChapterTransition.kt` and `reikai/presentation/reader/text/NovelSeam.kt` (`TransitionChapter`).
- `app/src/main/java/reikai/presentation/components/MergeSourceChips.kt`, `ManageMergeSourcesDialog.kt`, `SettledReorder.kt`.
- `app/src/main/java/reikai/domain/download/DownloadFolderRename.kt` (`renameDownloadFolder`), the rename pattern the file kernel extends.

## Decisions & tradeoffs

- **One series, not a better merge.** A merge's job is to line up the same chapters from different places; volumes are different chapters, so the fix is to stop merging them.
- **Duplicates are never resolved automatically** (owner ruling): both copies show until the user chooses.
- **Combine as volumes replaces Merge only for an all-local selection** (owner ruling), with Merge still in the overflow for two local copies of one series.
- **Reorder is a sheet from the ⋮ menu, not a mode** (owner ruling): dragging headers inline would fight the chapter long press.
- **A manga volume is a folder of chapter files** (owner ruling): the local manga source reads one level of volume folders inside a series folder, in an RK island, since that is how volume-split comics are usually stored; a CBZ stays one chapter.
- **Volume order lives in a Reikai table, carried in backups** (owner ruling), not in the folder's `details.json`, which is edited by hand and would reorder silently when stale.
- **A chapter's volume is read from its address, not stored** (owner ruling): manga's `SChapter` is the extension contract and has no field for it, while both types' addresses already name the volume's file or folder.
