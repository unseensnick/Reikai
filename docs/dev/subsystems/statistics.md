# Statistics

## Purpose

Statistics summarises the library and the reader's reading for manga and novels together, behind the same All / Manga / Novels switch: counts, charts of status, tracking status and scores, reading time per day with streaks, and lists of the library by one grouping at a time whose rows open those series in the library.

## How it works

### Loading

`StatsLibraryLoader.load` reads both content types once per screen into a `StatsLibrary`: each type's library as a `StatsSeries` (a merged series is one title, through the merge manager's `rankedSeriesBuckets`, ranked by `mangaLibraryRanking` / `novelLibraryRanking` so the series reads the lead its library row shows, and the stored stitch counts), every member's tracks on the signed-in trackers, the reading log's sessions, each entry's lifetime reading time from history, and the tracker and category names. Each type answers the per-entry fields through `StatsTitleFields`, the shape `libraryItemQueryFields` uses, and `StatsSeries.statsTitles` builds one neutral `StatsTitle` per series. Everything after that point is written once for both types.

`StatsViewModel` (Mihon's, patched) keeps the four overview cards' numbers and adds `StatsCharts`; `StatsGroupViewModel` (one grouping's list, assisted on the `StatsDimension`) and `StatsReadingViewModel` (the Reading time screen) call the same loader. All three read `statsContentType`, so the switch set on the overview applies everywhere.

### Groupings

`groupsBy` (`StatsKernels.kt`) groups titles by a `StatsDimension` into `StatsGroup`s keyed by a typed `StatsGroupKey`, with count, chapters read and total, mean score and reading time. A title sits in one row per value it has, so source, tag, tracker and language rows can sum past the library. Tags group ignoring case, under the first spelling seen. `StatsLibrary.rows` names each key, orders the rows (score, length and year keep their own order; the rest follow `StatsSort`) and attaches the library search from `StatsGroupKey.libraryQuery`.

### Library searches

A row opens the library through `searchLibraryFromDetails` with a query the shared search kernel answers: `status:`, `tracker:""`, `category:"Name"`, a `score` range matching the bar `scoreBar` rounds into, `total` bounds, `srcid:` and so on (see [library.md](library.md), Search). A name goes in whichever quote it lacks (`quotedQueryValue`), since the lexer has no escape. Start-year rows, and a name holding both quotes, have no search and do not open; their card is not a button.

### Reading log

History keeps one row per chapter whose last read is overwritten, so it cannot place time on a day. The reading log (`reading_session` and `novel_reading_session`, `ReadingLogRepository`) stores one row per session: the chapter, when it ended and how long it lasted. Both readers record it beside their history save, after the incognito check. The novel reader's read aloud is reading too: with the reader off screen, its session runs while speech plays and is saved when it stops. `readingTimeByDay` and `readingTimeByDayAndTitle` split a session at local midnight when the stats are computed; the per-title form keys by `EntryId`, since a manga and a novel can share a row id.

Every session counts, library or not: `titleSessions` puts a merged member's under its series, and one on a series outside the library (read from Browse, or left behind by a removal) under that entry and its own title, which the sessions query carries. Only the per-day time, the streaks and the Reading time screen read sessions; the groupings' time comes from history.

Sessions follow history: clearing all history clears them per type, Clear database's removal of reset manga history takes those chapters' sessions, a backup carries them by chapter url (`BackupReadingSession`, field 701 on `BackupManga`, 28 on `BackupNovel`), and a restore puts them on the chapter with that url, dropping one with no time, more than a week of it or an end outside the epoch to the year 3000 (`isLoggableSession`). A migration, copy or replace, and the gallery-version merge move each source chapter's sessions onto one target (`sessionMoves`, the first matched), so the time counts once. `(chapter_id, ended_at)` is unique, so a restore or a move repeated over the same data adds nothing.

A restore keeps the longer of the backup's and the device's history time per chapter, but adds the backup's sessions to the device's. Restoring the same data again changes neither; merging two devices' reading makes the Reading time screen's total for a series exceed its history time.

### Charts

Vico draws the donuts and the column charts (`StatsChartCards.kt`), themed through `rememberM3VicoTheme`. Slice colours are fixed theme roles per value (`statusColor`, `TrackStatus.color`), so a status keeps its colour between chips. The Reading time screen's bars are plain Compose (`WeekBars`) so each day is a selectable target. Source rows show the library's cover badge (`SourceIconBadge`, resolved once per source in the loader through `badgeSource` and `novelSourceBadge`), drawn scaled to list size so every badge case keeps the library's own drawing. Every chart carries a spoken description or, for a donut, leaves it to the legend beside it. The overview is three shelves (`StatsShelf`): the number cards, the charts and the lists. Each shelf picks a column count its cards divide into evenly from the screen width, so a phone gets one column and no shelf titles, and a tablet two columns of numbers and charts and three of lists. Cards in a row stretch to the tallest, and number and chart cards centre their content in the stretch. Mihon's `SectionCard` takes a modifier and an arrangement for this, and its small stat items use the overview's type sizes so the number cards read alike. See all is a plain grid by `STATS_CARD_MIN_WIDTH` (340dp), since its cards are near equal.

## Key files

- `app/src/main/java/reikai/presentation/stats/StatsLibrary.kt`: `StatsLibraryLoader`, `StatsLibrary`, `TitleReadingSession`.
- `app/src/main/java/reikai/presentation/stats/StatsSeries.kt`: `StatsSeries.statsTitles`, `titleIdByMember`.
- `app/src/main/java/reikai/presentation/stats/StatsTitle.kt`: `StatsTitle`, `StatsTitleFields`.
- `app/src/main/java/reikai/presentation/stats/StatsKernels.kt`: `groupsBy`, `readingTimeByDay`, `readingStreaks`, `scoreBar`.
- `app/src/main/java/reikai/presentation/stats/StatsRows.kt`: `StatsLibrary.rows`, `StatsSort`.
- `app/src/main/java/reikai/presentation/stats/StatsLibraryQuery.kt`: `StatsGroupKey.libraryQuery`.
- `app/src/main/java/reikai/presentation/stats/StatsCharts.kt`: `statsCharts`, `OVERVIEW_GROUPINGS`.
- `app/src/main/java/reikai/presentation/stats/StatsChartCards.kt`: `statsChartCards`, `StatsDonut`, `StatsColumns`.
- `app/src/main/java/reikai/presentation/stats/details/StatsGroupScreen.kt`: `StatsGroupScreen`.
- `app/src/main/java/reikai/presentation/stats/details/StatsReadingScreen.kt`: `StatsReadingScreen`.
- `domain/src/main/java/reikai/domain/readinglog/ReadingLogRepository.kt`: `ReadingLogRepository`.
- `data/src/main/sqldelight/tachiyomi/data/reading_session.sq`: the two session tables and their queries.

## Invariants and traps

**A rule here is written once over `StatsTitle`.** Adding a per-type branch below `statsTitles` is the duplication this layer exists to stop; a new per-entry fact goes into `StatsTitleFields`, which both types must answer.

**A row's search must find exactly the series it counts.** `StatsLibraryQueryTest` runs every row's search over the same titles; a new grouping or a grouping rule changed without its query fails it.

**Reading time per day starts when the log does.** Never backfill sessions from history: its last read is overwritten, so a backfill would place a chapter's whole time on one day.

**A session is recorded only where history is.** Recording inside the history upsert would also count migration's and the gallery update's history writes, which are not reading.

## Decisions

- **Merged series count once, except where a series has several values.** Status, categories, score and length read the series as its library row does, through the same lead; sources, tags, trackers and languages list every value, as library search matches any member. A series' tags are one rule for both sides, `seriesGenres`: its Edit info tags when set, else every source's tags.
- **Start year prefers a tracker's start date.** It is a real date where one exists and reaches back before the log; the log's first session covers the rest.
- **`tag:` and `lang:` keep Mihon's part-of-a-value match.** A tag row's search can therefore also find a longer tag holding it; `tracker:` and `category:` are Reikai's and match whole names.

## Upstream divergences

`StatsViewModel`, `StatsScreen`, `StatsScreenContent` and `StatsScreenState` carry `// RK` patches: the loader, the content switch, the chart cards and their links. `ReaderViewModel` records sessions; `HistoryRepositoryImpl`, `RestoreRepositoryImpl`, `MangaBackupCreator`, `MangaRestorer`, `BackupManga`, `RestoredManga` and `MigrateMangaUseCase` carry the log through clear, backup, restore and migrate. `LibrarySearchAst` adds the `status`, `tracker`, `category` and `score` fields. Mihon's `SectionCard` (`presentation-core`) takes a modifier and an arrangement and drops its `LazyItemScope` receiver, and `StatsItem` takes the overview's type sizes and hyphenates a long label, both for the shelves.

## Extending

A new grouping: add a `StatsDimension` and `StatsGroupKey` case, its key in `groupKeys`, its order in `StatsLibrary.rows`, its search in `libraryQuery` (or null), its label in `StatsRowItems.kt`, and list it in `OVERVIEW_GROUPINGS` for a card. `StatsLibraryQueryTest` then covers it.

## Tests

`StatsKernelsTest`, `StatsChartsTest`, `StatsSeriesTest` (builder cases over both types), `StatsLibraryQueryTest` (each row's search), `LibraryQueryMatchTest` (the search fields), `ReadingLogRepositoryTest` (the log over both tables), and `MigrateEngineConformanceTest` (session copies). Run with `./gradlew :app:testDebugUnitTest --tests "reikai.presentation.stats.*"` and `:data:testDebugUnitTest --tests "reikai.data.readinglog.*"`.

## Related

User doc: [statistics.md](../../statistics.md).
