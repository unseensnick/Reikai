# Statistics

## Purpose

Statistics summarises the library and the reader's reading for manga and novels together, behind the same All / Manga / Novels switch: counts, charts of status, tracking status and scores, reading time per day with streaks, lists of the library by one grouping at a time whose rows open the series they count, and the series read longest.

## How it works

### Loading

`StatsLibraryLoader.load` reads both content types into a `StatsLibrary`: each type's library as a `StatsSeries` (a merged series is one title, through the merge manager's `rankedSeriesBuckets`, ranked by `mangaLibraryRanking` / `novelLibraryRanking` so the series reads the lead its library row shows, and the stored stitch counts), every member's tracks on the signed-in trackers, the reading log's sessions, each entry's lifetime reading time from history, and the tracker and category names. Each type answers the per-entry fields through `StatsTitleFields`, the shape `libraryItemQueryFields` uses, and `StatsSeries.statsTitles` builds one neutral `StatsTitle` per series. Everything after that point is written once for both types.

`StatsViewModel` (Mihon's, patched) keeps the four overview cards' numbers and adds `StatsCharts`; `StatsGroupViewModel` (one grouping's list, assisted on the `StatsDimension`) and `StatsReadingViewModel` (the Reading time screen) call the same loader. All three read `statsContentType`, so the switch set on the overview applies everywhere. Every Statistics screen, the overview and its four detail screens, loads again each time it comes back on screen (`StatsRefreshOnResume`: a tab switch back, a pushed screen popped, a return from the reader), since the tab's model lives all session; `StatsReload` keeps the last figures shown until the new ones land, so a reload never flashes the loading state.

### Groupings

`groupsBy` (`StatsKernels.kt`) groups titles by a `StatsDimension` into `StatsGroup`s keyed by a typed `StatsGroupKey`, with count, chapters read and total, mean score and reading time. A title sits in one row per value it has, so source, tag, tracker and language rows can sum past the library. Tags group ignoring case, under the first spelling seen. `StatsLibrary.rows` names each key, orders the rows (score, length and year keep their own order; the rest follow `StatsSort`).

### A row's series

Every row opens `StatsSeriesScreen` on top of Statistics, listing the series it counts. `titlesIn` reads them from the same `titlesByGroup` map `groupsBy` counts from, so the list and the number cannot disagree. The rows are the library's own (`MangaLibraryRows`, `NovelLibraryRows`), before its filters and search, with the custom-info overlay applied, so the badges and merged counts match the library card. The list follows the library's display mode, columns, global sort and continue-reading setting, drawn through `LibraryItemsLayout`, but never its filters: a filter would leave fewer series than the row counts. A library row is matched to its series through `StatsLibrary.titleOf`, which knows every member of a merged series, so a row the library leads on another member than Statistics still lands. `StatsGroupKey` is `Serializable`, since the screen takes one; the length key holds its two ends rather than a `LongRange`.

### Reading log

History keeps one row per chapter whose last read is overwritten, so it cannot place time on a day. The reading log (`reading_session` and `novel_reading_session`, `ReadingLogRepository`) stores one row per session: the chapter, when it ended and how long it lasted. Both readers record it beside their history save, after the incognito check. The novel reader's read aloud is reading too: with the reader off screen, its session runs while speech plays and is saved when it stops. `readingTimeByDay` and `readingTimeByDayAndTitle` split a session at local midnight when the stats are computed; the per-title form keys by `EntryId`, since a manga and a novel can share a row id.

Every session counts, library or not: `titleSessions` puts a merged member's under its series, and one on a series outside the library (read from Browse, or left behind by a removal) under that entry and its own title, which the sessions query carries. Only the per-day time, the streaks and the Reading time screen read sessions; the groupings' time comes from history. Most read ranks history's per-entry time under the same keys (`StatsLibrary.mostRead`, through `titleReadDurations`), so a series read and then removed keeps its place there too, under its own title.

Sessions follow history: clearing all history clears them per type, Clear database's removal of reset manga history takes those chapters' sessions, a backup carries them by chapter url (`BackupReadingSession`, field 701 on `BackupManga`, 28 on `BackupNovel`), and a restore puts them on the chapter with that url, dropping one with no time, more than a week of it or an end outside the epoch to the year 3000 (`isLoggableSession`). A migration, copy or replace, and the gallery-version merge move each source chapter's sessions onto one target (`sessionMoves`, the first matched), so the time counts once. `(chapter_id, ended_at)` is unique, so a restore or a move repeated over the same data adds nothing.

A restore keeps the longer of the backup's and the device's history time per chapter, but adds the backup's sessions to the device's. Restoring the same data again changes neither; merging two devices' reading makes the Reading time screen's total for a series exceed its history time.

### Charts

The overview's figures are one grid of icon tiles (`StatsStrip`, `statsOverviewFigures`), two to six across by its shelf's width. Publishing and Tracking status are share bars (`StatsShareBars`: each status, its count and share, sorted by count), and Scores is a breakdown, one row per score given (`StatsScoreBreakdown`). Where the charts shelf is two columns wide, Publishing status adds a Vico pie (`StatsDonut`) with the total in its hole; it would only add height on a phone. Vico also draws the week's columns (`StatsColumns`), themed through `rememberM3VicoTheme`. Bar and slice colours are fixed theme roles per value (`statusColor`, `TrackStatus.color`), so a status keeps its colour between chips. The Reading time screen's bars are plain Compose (`Bars`) so each is a selectable target. Its periods are `ReadingWindow` (`StatsReadingPeriods.kt`): a week is the seven days ending today, a month and a year are calendar ones, a custom range is any dates up to today, and the arrows step by the window's own length, a week's or a custom range's last step forward stopping at today. A window gets a bar per day under two months, per month under three years and per year past that, so a bar stays wide enough to tap. Status and score rows open the series behind them; the pie takes no taps. Source rows show the library's cover badge (`SourceIconBadge`, resolved once per source in the loader through `badgeSource` and `novelSourceBadge`), drawn scaled to list size so every badge case keeps the library's own drawing. Every chart carries a spoken description or, for the pie, leaves it to the rows under it. The overview is three shelves (`StatsShelf`): the figures, the charts and the lists. Each shelf picks a column count its cards divide into evenly from the width it is given (320dp a chart or list card, less the navigation rail when Statistics is a tab), and is titled once it is wide enough for two of its cards, so a phone gets one column and no shelf titles and a tablet titles even the one-card figures shelf. By default the charts run Publishing, Reading this week, Tracking, Scores, so a two-column row pairs cards of similar height. Cards in a row stretch to the tallest. A card that fits its content to the width (the tile grid, the pie) reads the shelf's width through `LocalStatsShelfWidth`, never the screen's, since it cannot measure itself inside a row sized by intrinsic height. Mihon's `SectionCard` takes a modifier and an arrangement for this, and its small stat items use the overview's type sizes so the number cards read alike. See all is a plain grid by `STATS_CARD_MIN_WIDTH` (340dp), since its cards are near equal.

### Arranging and shortcuts

Every overview card has a `StatsCardKind`, whose declaration order is the default layout and whose key is what a saved order stores (`statsCardOrder`). `statsCardLayout` reads that order through `inSavedOrder`, the rule the reader's bottom-bar buttons also use, so a card added later goes last and an unknown key is ignored, then keeps each shelf's cards together, since the shelf decides how many fit across. The top bar's Arrange cards swaps the overview for `StatsArrangeList`: shelves drag as blocks (`withShelves`) and fold with their arrow, and each shelf's cards drag within it only, in a `ReorderableColumn` of their own (`withShelfCards`). A card's row hides or shows it (`statsHiddenCards`), a shelf's row all of its cards; a hidden card stays in the arrange list, and a shelf with every card hidden draws nothing. Reset deletes the saved order and the hidden set.

Statistics opens from More unless it is a tab, which takes its row out of the list (`MoreViewModel.showStats`). The Statistics shortcut setting in Appearance (`StatsShortcut`, off by default) adds it to the Library, Updates and History overflow menus, or makes it a bottom-bar tab (`StatsTab`) before More. The tab and the screen share `StatsContent`; only the screen has a way up. Turning the tab off while it is showing moves to More, where the setting is reached from.

### Reading pace

The reading card ends with each shown type's overall pace (`StatsLibrary.paces`, from the same `learnPace` the reader's time left uses, with no series): seconds a page for manga and words a minute for novels (`StatsPace.measure`), or Learning. Novels show two: the reading pace and the listening pace, learned from chapters mostly read aloud. A row of `StatsItem`s with anything under it sizes itself with `IntrinsicSize.Min`, since a `StatsItem` fills the height it is given and would leave the rows under it none.

## Key files

- `app/src/main/java/reikai/presentation/stats/StatsLibrary.kt`: `StatsLibraryLoader`, `StatsLibrary`, `TitleReadingSession`.
- `app/src/main/java/reikai/presentation/stats/StatsSeries.kt`: `StatsSeries.statsTitles`, `titleIdByMember`.
- `app/src/main/java/reikai/presentation/stats/StatsTitle.kt`: `StatsTitle`, `StatsTitleFields`.
- `app/src/main/java/reikai/presentation/stats/StatsKernels.kt`: `groupsBy`, `titlesIn`, `readingTimeByDay`, `readingStreaks`, `scoreBar`.
- `app/src/main/java/reikai/presentation/stats/StatsRows.kt`: `StatsLibrary.rows`, `StatsSort`.
- `app/src/main/java/reikai/presentation/stats/StatsSeriesRows.kt`: `titleOf`, `standingFor`.
- `app/src/main/java/reikai/presentation/stats/details/StatsSeriesScreen.kt`: `StatsSeriesScreen`.
- `app/src/main/java/reikai/presentation/stats/details/StatsSeriesViewModel.kt`: `StatsSeriesViewModel`.
- `app/src/main/java/reikai/presentation/stats/StatsCharts.kt`: `statsCharts`, `OVERVIEW_GROUPINGS`.
- `app/src/main/java/reikai/presentation/stats/StatsChartCards.kt`: `statsChartCards`, `statsReadingCard`, `StatsShareBars`, `StatsScoreBreakdown`, `StatsDonut`, `StatsColumns`.
- `app/src/main/java/reikai/presentation/stats/StatsStrip.kt`: `StatsStrip`, `statsOverviewFigures`.
- `app/src/main/java/reikai/presentation/stats/StatsShelf.kt`: `StatsShelf`, `StatsShelfContent`.
- `app/src/main/java/reikai/presentation/stats/StatsCardKind.kt`: `StatsCardKind`, `statsCardLayout`, `withShelves`, `withShelfCards`.
- `app/src/main/java/reikai/presentation/stats/StatsArrangeList.kt`: `StatsArrangeList`.
- `app/src/main/java/reikai/presentation/stats/StatsTab.kt`: `StatsTab`.
- `app/src/main/java/reikai/domain/stats/StatsShortcut.kt`: `StatsShortcut`.
- `app/src/main/java/reikai/presentation/stats/details/StatsGroupScreen.kt`: `StatsGroupScreen`.
- `app/src/main/java/reikai/presentation/stats/details/StatsReadingScreen.kt`: `StatsReadingScreen`.
- `app/src/main/java/reikai/presentation/stats/details/StatsMostReadScreen.kt`: the Most read card in full, ranked by `mostRead`.
- `app/src/main/java/reikai/presentation/stats/StatsReadingPeriods.kt`: `ReadingWindow`, `ReadingPeriod`.
- `domain/src/main/java/reikai/domain/readinglog/ReadingLogRepository.kt`: `ReadingLogRepository`.
- `data/src/main/sqldelight/tachiyomi/data/reading_session.sq`: the two session tables and their queries.

## Invariants and traps

**A rule here is written once over `StatsTitle`.** Adding a per-type branch below `statsTitles` is the duplication this layer exists to stop; a new per-entry fact goes into `StatsTitleFields`, which both types must answer.

**A row's list must hold exactly the series it counts.** Both read `titlesByGroup`; a grouping rule written anywhere else would let them drift. `StatsSeriesListTest` checks every dimension.

**Reading time per day starts when the log does.** Never backfill sessions from history: its last read is overwritten, so a backfill would place a chapter's whole time on one day.

**A card in a shelf must answer intrinsic measurement.** A shelf row is `IntrinsicSize.Min`, so `BoxWithConstraints` or any `SubcomposeLayout` inside a card crashes the screen; size from `LocalConfiguration` instead, as `StatsStrip` does. `StatsStripTest` draws the strip inside its shelf.

**A session is recorded only where history is.** Recording inside the history upsert would also count migration's and the gallery update's history writes, which are not reading.

## Decisions

- **Merged series count once, except where a series has several values.** Status, categories, score and length read the series as its library row does, through the same lead; sources, tags, trackers and languages list every value, as library search matches any member. A series' tags are one rule for both sides, `seriesGenres`: its Edit info tags when set, else every source's tags.
- **Start year prefers a tracker's start date.** It is a real date where one exists and reaches back before the log; the log's first session covers the rest.
- **A row lists its series in Statistics rather than searching the library.** The library search lost Statistics from the back stack, could not express a start year, and with `tag:` and `lang:` matching part of a value it could find more than the row counted.

## Upstream divergences

`StatsViewModel`, `StatsScreenContent` and `StatsScreenState` carry `// RK` patches: the loader and its reload, the content switch, the chart cards and their links. `StatsScreen`'s body moved to Reikai's `StatsContent`, which `StatsTab` shares, so its `Content` is one call under an `// RK:` note. `ReaderViewModel` records sessions; `HistoryRepositoryImpl`, `RestoreRepositoryImpl`, `MangaBackupCreator`, `MangaRestorer`, `BackupManga`, `RestoredManga` and `MigrateMangaUseCase` carry the log through clear, backup, restore and migrate. `LibrarySearchAst` adds the `status`, `tracker`, `category` and `score` fields. `LibraryToolbar` (through `LibraryTab`) adds the optional Statistics item to the Library's overflow menu; Updates and History get theirs from `RecentsScreen`. `UiPreferences` holds `statsShortcut`, `SettingsAppearanceScreen` shows it, and `HomeScreen` adds `StatsTab` to the tab list. Mihon's `SectionCard` (`presentation-core`) takes a modifier and an arrangement and drops its `LazyItemScope` receiver, and `StatsItem` takes the overview's type sizes and hyphenates a long label, both for the shelves.

## Extending

A new grouping: add a `StatsDimension` and `StatsGroupKey` case, its key in `groupKeys`, its order in `StatsLibrary.rows`, its case in `StatsGroupKey.dimension`, its label in `StatsRowItems.kt`, and give it a `StatsCardKind` for a card. `StatsSeriesListTest` then covers it.

## Tests

`StatsKernelsTest`, `StatsReadingPeriodsTest` (windows, bars and steps), `StatsChartsTest`, `StatsSeriesTest` (builder cases over both types), `StatsSeriesListTest` (a row's list against its count, and row matching), `StatsTitleSessionsTest` (sessions and Most read keyed to their series), `StatsCoversTest` (covers over both types), `ReadingLogRepositoryTest` (the log over both tables), and `MigrateEngineConformanceTest` (session copies). `StatsPaceTest` (the pace measures), `StatsReadingCardTest` (a UI test: today's time, the pace and the logged-since line show in a stretched card), `StatsScoresCardTest` (a score's row lists its series), `StatsStripTest` (the strip draws inside its shelf, and a wide screen titles it), `StatsCardKindTest` (a saved order, shelves kept together, shelf and card moves), `StatsArrangeListTest` (a UI test: hiding a card or a shelf, folding a shelf), `StatsDonutWidthTest` (a UI test: the pie follows the shelf's width, not the screen's). Run with `./gradlew :app:testDebugUnitTest --tests "reikai.presentation.stats.*"` and `:data:testDebugUnitTest --tests "reikai.data.readinglog.*"`.

## Related

User doc: [statistics.md](../../statistics.md).
