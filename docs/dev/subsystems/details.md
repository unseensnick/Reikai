# Details

## Purpose

The details page is the hub for one series: cover, backdrop and description, the chapter list, downloads, tracking, categories, edit info, merged sources, and the start of every read. Manga and light novels render the same page through one shared body, so a details change written once reaches both.

## How it works

### Screens, models and adapters

Two thin Voyager screens stay separate because their open-keys differ and must stay serializable: `MangaScreen(mangaId: Long)` and `NovelScreen(sourceId: String, novelUrl: String)` (a browsed novel has no row id yet). Each resolves its own model, builds its adapter, and hands the adapter to the one shared body, `EntryDetailsContent`:

- `MangaViewModel` (Mihon's, `// RK`-patched, live and synced) behind `MangaEntryAdapter`.
- `NovelDetailsViewModel` (Reikai's) behind `NovelEntryAdapter`.

Both adapters implement `EntryDetailsBehavior`, the shared action contract (selection, mark read and bookmark, downloads, hide and unhide, chapter-number edits, categories, cover, edit info and tracker autofill, favorite, the merge actions, refresh), and map their model's state to `EntryDetailsScreenState` (`Loading`, `Failed`, `Loaded`). `Loaded` carries the neutral header state (`EntryDetailsUiState`), the chapter list (`EntryChapterListUiState` of `EntryChapterListItem.Chapter` and `Missing` rows), `hasActiveFilter`, `viewedEntryId`, the header's `EntrySourceState` (installed, local, missing) and the typed `EntryCapabilities`. The adapter is where every per-type reconciliation lands, so neither model is made to implement a Reikai interface: the manga adapter resolves neutral chapter ids back to `Chapter`, gathers manga's per-item selection flags, and fans the neutral filter call out to three TriState setters; the novel adapter resolves the String source id, backs per-chapter download state with the disk cache, and pre-formats progress.

Per-type actions that are not shared (opening the reader, the novel page selector, plugin reload, share intents) stay on the concrete screen or adapter, so the shared contract never rots into no-op methods.

### Dialogs

`EntryDetailsDialog` is the union both types can fill: `EditInfo`, `Cover`, `ManageSources`, `TrackSheet`, `DeleteChapters`, `SetFetchInterval`, `ClearDownloads`, `RemoveFromLibrary` and `ChapterNumber`. `Screen.EntryDetailsDialogHost` renders them for both screens. Dialogs one type alone has stay in that screen's own dispatcher (novels: `NovelChapterSettingsDialog` in `NovelDetailsDialogs.kt`, the page selector, word count), so the union never carries a case one side cannot fill.

### What the page shows

- **The viewed member.** On a merged series the header, description, tags, web actions and download gate follow the selected source chip, or the anchor under All (`shownEntry` in `HeaderSource.kt`; manga reads `shownManga` / `shownSource`). A sibling chip keeps its own cover thumbnail (`ownCover`) under the anchor's custom-info overlay. Favorite, categories and notes always act on the anchor, so switching chips never moves library state. Chip flips go through `EntryMergeGroupHost.selectSource`, which refuses an id outside the group and a re-tap, and both models drop their selection through `EntrySelection.afterChipFlip`. The group, its chips and the picked chip are one cell (`GroupState`, `chipsOf`). Merging itself is [merged-series.md](merged-series.md).
- **Source state.** `chaptersDownloadable` (installed only) and `isCoverAnchored` derive from `viewedEntryId` and `EntrySourceState` rather than per adapter. A missing source warns in the header and `rowOffersDownload` hides the indicator and download swipe on rows with nothing on disk or queued. Novels mark a member missing through `novelSourceState` once its plugin lookup has run, so the page does not flash a warning while the host loads.
- **Web actions.** WebView, Share, Copy link, the tag copy and the assistant link come from one `EntryWebPage` (url, `SourceKey`, viewed `EntryId`), built by `Manga.webPageIn` (upstream's `HttpSource.mangaUrlOrNull` rule, which hides the actions when the extension throws) or `NovelSource.webPageOf`, held by each model in a `ShownWebPage` that asks the source only when the shown member changes, and turned into actions by `rememberEntryWebActions`.
- **Theme.** The cover's seed colour themes the page when `themeCoverBased` is on; the backdrop and gradient are `EntryInfoBox`.
- **Status** labels and icons come from `entryStatusRes` / `entryStatusIcon`; novels store SManga's codes (`NovelStatusCode`).
- **Capabilities.** `novelPageSelector` (a paged LN source: the page bar and sheet, named by `novelPageText`; sort and filter are page-scoped), `mangaPagePreviews`, `mangaRelatedCarousel` (inline or as an overflow action, a Settings choice) and `mangaGallery` (namespaced tags and the gallery card for adult sources).
- **Novel-only overflow.** Search across downloaded chapters (`NovelChapterSearchScreen`) and word count (`NovelWordCountDialog`, words split on spaces by `NovelWords.countSpaced`), both over the chapters a tap would open; manga chapters on disk are images, so manga has neither.

### The chapter list

- **Rows.** `chapterRowTitle` gives the number where the entry shows chapters by number and the name otherwise; `chapterRowDate` reads the `UndatedChapterDate` slot (manga "N/A", novels blank, because novel sources rarely date chapters); `progressWhileUnread` hides progress once the row reads as read, against the group's read flag. The reader's chapter sheet uses the same three rules.
- **Missing markers.** `ChapterGap.withMarkers` walks the displayed rows and counts only whole numbers the same owner has nowhere in the series, so a repost, swapped pair, filtered or hidden chapter makes no gap; `ChapterGap.Present` is built from every chapter before hidden rows and filters drop any.
- **Sort.** A tap on the sort page flips the mode shown or starts a new mode ascending (`ChapterSortPick.descendingAfter`); a novel still on the global default flips what it shows. Novel chapter settings render Mihon's `FilterPage`, `SortPage` and `DisplayPage`. "Set as default" with Apply to library writes every entry in one transaction on both types.
- **Reading order.** Resume, download next N and mark previous read ask `ReadingOrder` (always ascending, so next advances whatever the display order). Mark previous read walks the rows the list shows (novels through `novelShownRows`). A tap resolves through `chapterToOpen`: a listed row, else the Resume pick, so Resume still opens a chapter the list hides.
- **Hidden chapters** are keyed by `hiddenChapterKey` (source plus url), resolved by `resolveHiddenChapterView`, and left out of the list, resume and every bulk download on both types.
- **Selection.** `EntrySelection` with `rangeOrToggle` (a long press on a selected row drops it); `chapterSelectionOffers` decides bookmark, mark read and unread, download and delete over `ChapterMarks`, the same rule Recents uses. Mark unread reads stored progress, never the drawn label.
- **Downloads.** The toolbar dropdown and selection pick through `DownloadCandidates` (novels keep `selectChaptersForDownloadAction` for the dropdown). The first download of an entry outside the library offers to add it, once per screen, through `AddToLibraryOffer`.
- **Refresh** is DB-first: the source is hit on first open with no chapters, or on pull-to-refresh. Novels refresh through `refreshNovelFromSource` (see [library.md](library.md)).

### Edit info and custom info

`EntryEditInfoDialog` is one dialog for both types: a Compose `AlertDialog` hosting the native `edit_entry_info.xml` form (cover preview, status, title, author, artist, cover URL, description, tag chips), Fill from tracker, and Reset info, Reset tags and Reset all. A save runs non-cancellable. Edits are stored as a non-destructive overlay, `custom_manga_info` and `custom_novel_info` (both implementing `EntryCustomInfo`), never written to the source row, so Reset restores the source even offline. The overlay is display-only: details, library, Updates, History, Recents and the widget show it (`withCustomInfo`, `overlayCustomInfo`), while merge, grouping, sort, filter, tracker search and download folders read the raw source values. Backups carry the overrides on each entry at Komikku's and Yokai's field numbers (`BackupCustomInfoFields`); the old 0.3.x root sections are read through `LegacyCustomInfo`.

Fill from tracker pulls title, author, artist, cover, description and genres from a bound tracker (`Tracker.getMangaMetadata`, `TrackMangaMetadata`), picking through `TrackerSelectDialog` when several are bound; enhanced (self-hosted) trackers are excluded. Genres are appended through `mergeTrackerGenres`, case- and space-blind, keeping the existing tag's spelling, because the tags may be the user's own.

The cover viewer (`EntryCoverDialog` over `EntryCoverViewModel`, with `MangaEntryCoverViewModel` and `NovelCoverViewModel`) offers Edit and Delete only where `canEditCover` holds: in the library (or a local manga), since removing an entry deletes its custom cover.

### Chapter-number corrections

A user can correct a source's wrong chapter number ("Correct chapter number" in the selection overflow, one chapter selected, or by tapping the out-of-line mark). The corrected number is written on the chapter row, so every rule that reads a number (list, sort, gaps, next chapter, tracker pushes, the merged stitch) reads it with no rule of its own. `manga_chapter_number_override` and `novel_chapter_number_override` remember each correction keyed by owner and url (a re-listed chapter gets a new row id), with the source's number beside it. Both syncs apply the corrections before comparing (`appliedTo`) and record a source renumber (`updateSourceNumbers`), so clearing restores what the source says now. `EditChapterNumber` reads and saves for both models; a save runs inside `ReconcileMergedChapters.afterPass`. Backups write the corrected number where Mihon writes one and the source's number in its own field (`BackupChapter` 701, `BackupNovelChapter` 12); restore writes them back (`backedUpOverrides`).

`ChapterNumberHint.forOwners` marks a chapter out of line with its own source's list (never the merged list): rows chain into runs that break at a jump over 10, and a run of at most 5 rows whose neighbouring runs are within 10 of each other is marked, with the free whole number its neighbours leave as the suggestion. Side content (side story, extra, special, omake, epilogue, prologue, bonus, afterword, illustrations, and unlabelled rows a volume-labelled list interleaves), hidden chapters, and a copy listed in the wrong place (within 2 days of a same-numbered row and over 30 days from both neighbours) are never marked. An unknown date never decides.

## Key files

- `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt` and `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaViewModel.kt`: the manga screen and model, `shownManga`, `shownSource`.
- `app/src/main/java/reikai/presentation/novel/details/NovelScreen.kt` and `app/src/main/java/reikai/presentation/novel/details/NovelDetailsViewModel.kt`: the novel screen and model, `novelSourceState`.
- `app/src/main/java/reikai/presentation/details/EntryDetailsBehavior.kt`, `app/src/main/java/reikai/presentation/details/EntryDetailsScreenState.kt`: the contract, `Loaded`, `EntryCapabilities`, `EntrySourceState`.
- `app/src/main/java/reikai/presentation/details/MangaEntryAdapter.kt` and `app/src/main/java/reikai/presentation/details/NovelEntryAdapter.kt`: the adapters.
- `app/src/main/java/reikai/presentation/details/EntryDetailsContent.kt`, `app/src/main/java/reikai/presentation/details/EntryDetailsScaffold.kt`, `app/src/main/java/reikai/presentation/details/EntryDetailsTwoPaneScaffold.kt`, `app/src/main/java/reikai/presentation/details/EntryInfoBox.kt`, `app/src/main/java/reikai/presentation/details/EntryToolbar.kt`: the shared body.
- `app/src/main/java/reikai/presentation/details/EntryDetailsDialog.kt`: `EntryDetailsDialog`, `EntryDetailsDialogHost`.
- `app/src/main/java/reikai/presentation/details/EntryMergeGroupHost.kt`, `app/src/main/java/reikai/presentation/details/EntryMergeActionHost.kt`, `app/src/main/java/reikai/presentation/details/HeaderSource.kt`: `chipsOf`, `shownEntry`, `unifiedViewMember`.
- `app/src/main/java/reikai/presentation/details/EntryWebPage.kt`: `EntryWebPage`, `webPageIn`, `webPageOf`, `ShownWebPage`, `rememberEntryWebActions`.
- `app/src/main/java/reikai/presentation/details/EntryHiddenChapters.kt` and `app/src/main/java/reikai/presentation/details/EntryLibraryPrompts.kt`: `chapterToOpen`, `AddToLibraryOffer`.
- `app/src/main/java/reikai/presentation/reader/ChapterTitle.kt`, `app/src/main/java/reikai/presentation/components/ChapterRowDate.kt`, `app/src/main/java/reikai/presentation/components/ReadProgressLabel.kt`: the row rules.
- `app/src/main/java/reikai/domain/merge/ChapterGap.kt`, `domain/src/main/java/reikai/domain/chapter/ChapterSortPick.kt`, `app/src/main/java/reikai/domain/chapter/ReadingOrder.kt`, `app/src/main/java/reikai/presentation/novel/details/NovelShownRows.kt`: gaps, sort pick, reading order.
- `app/src/main/java/reikai/presentation/selection/ChapterSelectionOffers.kt`: `chapterSelectionOffers`.
- `app/src/main/java/reikai/presentation/details/EntryEditInfoDialog.kt`, `app/src/main/res/layout/edit_entry_info.xml`, `app/src/main/java/reikai/presentation/details/EntryTrackerAutofill.kt`: the editor, `mergeTrackerGenres`.
- `domain/src/main/java/reikai/domain/entry/EntryCustomInfo.kt` and `domain/src/main/java/reikai/domain/novel/model/CustomNovelInfo.kt`: the overlay.
- `app/src/main/java/reikai/presentation/details/EntryCoverViewModel.kt`: `EntryCoverViewModel`, `canEditCover`.
- `domain/src/main/java/reikai/domain/chapter/ChapterNumberOverride.kt`, `data/src/main/sqldelight/tachiyomi/data/chapter_number_override.sq`, `app/src/main/java/reikai/domain/chapter/EditChapterNumber.kt`, `app/src/main/java/reikai/presentation/details/ChapterNumberDialog.kt`, `domain/src/main/java/reikai/domain/chapter/ChapterNumberHint.kt`: corrections and the hint.
- `app/src/main/java/reikai/presentation/novel/details/NovelPageSelectorSheet.kt` and `app/src/main/java/reikai/presentation/novel/details/NovelWordCountDialog.kt`: the novel-only pieces.

## Invariants and traps

- **`MangaViewModel` is never made to implement the shared contract.** The adapter reads it and forwards; a renamed upstream field breaks `MangaEntryAdapter` at compile time.
- **Derive the rows, chips and picked chip from one snapshot.** Reading the chips from the live host while the rows came from an earlier snapshot wrote an ungrouped list under the merged switcher; the novel chapter flows take all three from the `GroupState` they were built for. `MangaViewModel` still mirrors the chips and the picked chip through two collectors, so they can disagree for one emission.
- **Description expansion is a layout input too.** The two-pane layout opens the synopsis expanded; a single adapter field once dropped that.
- **A one-shot flag on a rebuilt state does not survive.** `NovelDetailsState.Loaded` is rebuilt whenever the chapter list changes; the first-download prompt lives in `AddToLibraryOffer` for that reason.
- **A web page lookup in the adapter would run per download tick.** Resolve it in the model through `ShownWebPage`.
- **The custom-info overlay never touches the source row.** Tracker search, refresh, duplicate detection, download folder names and merge read raw values.
- **The partial novel update must be able to write null** for author, artist, description, cover and genre, which `RepairNovelDetails` relies on to clear a neighbour's details.
- **The reader's chapter sheet hides the control by the same rule, `offersDownload`, and needs to be told which copies an installed source holds.** Its targets know that only on a stitched merge, so each reader passes `canFetch` (`ReaderViewModel.fetchableChapterIds`, the set `NovelReaderViewModel.chapterRows` builds); a row builder left on the default draws a control that does nothing on an uninstalled source's row.
- **A sibling's own custom-info row is not loaded** on a merged page: its header shows the raw cover while the viewer applies that row.

## Decisions

- **One shared body, two screens.** Voyager screen arguments must be serializable, so one screen through a lambda opener is out; the body is written once.
- **The merged display convention is the anchor.** `Loaded` carries one always-populated display entry plus a source name, so shared code has no null branch for "primary".
- **The corrected chapter number is written on the row**, not overlaid at read time, which would need every number reader to apply it. Keyed by owner and url, one table per type, so a merged series' other sources keep their numbers and a migration does not carry it.
- **Custom info is an overlay table, display-only.** A rename must not reshuffle or unmerge the library, and Reset must restore the source offline.
- **Genres from a tracker are appended, never replaced.** Only genre lists, never the noisy tag or category dumps (AniList tags, MangaUpdates categories).
- **Edit only where a custom cover is kept.** Letting an entry outside the library keep one would need an `// RK` change to Mihon's `editCover` and still orphan files.
- **The novel related carousel stays empty** until a novel recommendation source exists.
- **The Upcoming screen and the interval library filter stay manga-only**, by ruling rather than mechanism: novels predict `next_update` through `ReleaseInterval` and share the Set-interval dialog.
- **Tag chips stay Mihon's flat outlined `SuggestionChip`.** Its outline keeps contrast under AMOLED; Komikku's filled chip would need an AMOLED branch.
- **Word count splits on spaces** as Tsundoku does, so a Chinese or Japanese chapter reports few words.

## Upstream divergences

`// RK` islands in `MangaScreen` and `MangaViewModel` (adapter wiring, merge host, chapter gaps, selection through `EntrySelection`, web page, chapter-number hint, recommendations), `SetMangaChapterFlags` (`ChapterSortPick`), `UpdateMangaFromRemote` (kept details), `SyncChaptersWithSource` (number corrections, `ReleaseInterval`), `FetchInterval`, and the backup models and restorers. Mihon's `MangaScreen` composable, `MangaInfoHeader`, `MangaToolbar` and `MangaCoverViewModel` are deleted and manifested in [off-path-manifest.md](../off-path-manifest.md). The web actions and the missing-source rows are recorded in [upstream-sync.md](../upstream-sync.md) "Deliberate divergences".

## Extending

- **A new action**: add it to `EntryDetailsBehavior`, implement it in both adapters, render it in `EntryDetailsContent`. A type that cannot do it leaves it hidden through state, never a no-op.
- **A per-type section**: add a typed slot to `EntryCapabilities`, filled by the one adapter that can.
- **A new shared dialog**: add a case to `EntryDetailsDialog` only if both types can fill it; otherwise keep it in that screen's dispatcher.
- **A new chapter-row rule**: put it in the row kernels so the reader's chapter sheet follows.

## Tests

`EntryDetailsScreenStateTest`, `MangaSourceStateTest`, `ViewedNovelSourceTest`, `NovelDetailsMissingSourceTest`, `DetailsChipViewConformanceTest`, `ShownEntryTest`, `HeaderSourceTest`, `EntryMergeGroupHostTest`, `NovelDetailsGroupSnapshotTest`, `EntryWebPageTest`, `NovelDetailsWebPageTest`, `ChapterToOpenTest`, `EntryLibraryPromptsTest`, `NovelDetailsAddToLibraryPromptTest`, `ChapterRowProgressConformanceTest`, `ChapterGapTest`, `DetailsGapConformanceTest`, `ChapterSortPickTest`, `SetNovelChapterFlagsTest`, `NovelMarkPreviousReadTest`, `ReadingOrderConformanceTest`, `ChapterSelectionOffersTest`, `CoverEditCapabilityConformanceTest`, `MergeTrackerGenresTest`, `EntryCustomInfoOverlayTest`, `NovelStatusCodeTest`, `ChapterNumberOverrideConformanceTest`, `ChapterNumberOverrideBackupTest`, `ChapterNumberDialogTest`, `ChapterNumberHintTest`, `NovelDetailsNumberHintTest`. Run one with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`. There is no manga details harness: changes inside `MangaViewModel`'s islands are checked on device.

## Related

- [merged-series.md](merged-series.md) for the group, chips and Manage sources; [library.md](library.md) for the novel refresh; [content-layer.md](content-layer.md) for the shared vocabulary.
